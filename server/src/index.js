/**
 * CS2 DMA Radar - Server (中继 + 网页托管)
 *
 * Responsibilities:
 *   1. Accept frames pushed by radar clients over WebSocket at `/push`.
 *   2. Re-broadcast the latest frame to browser viewers over WebSocket at `/ws`,
 *      rate-limited to BROADCAST_INTERVAL_MS (default 33ms ~ 30 fps) so the web
 *      stays smooth without flooding the network.
 *   3. Serve the built Vue web frontend from ./public.
 *   4. Expose GET /api/status for connection diagnostics.
 *
 * Runs standalone (`node src/index.js`) or in Docker (see Dockerfile).
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');
const PUBLIC_DIR = process.env.PUBLIC_DIR || path.join(ROOT, 'public');

const PORT = parseInt(process.env.PORT || '27081', 10);
const HOST = process.env.HOST || '0.0.0.0';
// Broadcast cadence to browsers. 33ms ~ 30 fps, 16ms ~ 60 fps.
const BROADCAST_INTERVAL_MS = parseInt(process.env.BROADCAST_INTERVAL_MS || '33', 10);
const STATUS_INTERVAL_MS = parseInt(process.env.STATUS_INTERVAL_MS || '2000', 10);
const VIEWER_HEARTBEAT_MS = 30000;

const startedAt = Date.now();

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
/** name -> { name, ws, connectedAt, lastFrameAt, frames } */
const clients = new Map();
/** latest frame received from any push client (with seq + receivedAt added) */
let latestFrame = null;
let frameSeq = 0;
/** browser viewer sockets */
const viewers = new Set();

// ---------------------------------------------------------------------------
// HTTP: static files + /api/status
// ---------------------------------------------------------------------------
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
};

function statusJson() {
  const now = Date.now();
  const clientList = [...clients.values()].map((c) => ({
    name: c.name,
    connectedAt: c.connectedAt,
    lastFrameAgeMs: c.lastFrameAt ? now - c.lastFrameAt : null,
    frames: c.frames,
    // frames per second, measured on the server from the received push stream
    fps: c.fpsCalc
      ? Math.round(((c.frames - c.fpsCalc.frames) * 1000) / Math.max(1, now - c.fpsCalc.at))
      : null,
  }));
  return {
    ok: true,
    time: now,
    uptimeSec: Math.round((now - startedAt) / 1000),
    clients: clientList,
    clientConnected: clients.size > 0,
    viewers: viewers.size,
    lastFrame: latestFrame
      ? {
          ageMs: now - latestFrame.receivedAt,
          map: latestFrame.map || '',
          players: Array.isArray(latestFrame.players) ? latestFrame.players.length : 0,
        }
      : null,
  };
}

function serveStatic(req, res) {
  let urlPath;
  try {
    urlPath = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  } catch {
    urlPath = '/';
  }
  if (urlPath === '/') {
    urlPath = '/index.html';
  }

  const filePath = path.normalize(path.join(PUBLIC_DIR, urlPath));
  if (filePath !== PUBLIC_DIR && !filePath.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('forbidden');
    return;
  }

  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      // SPA fallback
      fs.readFile(path.join(PUBLIC_DIR, 'index.html'), (e2, html) => {
        if (e2) {
          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
          res.end('not found. Did you build the web frontend? (cd web && npm run build)');
          return;
        }
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
        res.end(html);
      });
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const type = MIME[ext] || 'application/octet-stream';
    const cache = urlPath.startsWith('/assets/') ? 'public, max-age=31536000, immutable' : 'no-cache';
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': cache });
    fs.createReadStream(filePath).pipe(res);
  });
}

const server = http.createServer((req, res) => {
  const pathname = new URL(req.url, 'http://x').pathname;
  if (pathname === '/api/status' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(statusJson()));
    return;
  }
  serveStatic(req, res);
});

// ---------------------------------------------------------------------------
// WebSockets
// ---------------------------------------------------------------------------
const wssPush = new WebSocketServer({ noServer: true });
const wssView = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const pathname = new URL(req.url, 'http://x').pathname;
  if (pathname === '/push') {
    wssPush.handleUpgrade(req, socket, head, (ws) => wssPush.emit('connection', ws, req));
  } else if (pathname === '/ws') {
    wssView.handleUpgrade(req, socket, head, (ws) => wssView.emit('connection', ws, req));
  } else {
    socket.destroy();
  }
});

// --- push clients (radar clients) ---
wssPush.on('connection', (ws, req) => {
  let name = 'radar-1';
  try {
    name = new URL(req.url, 'http://x').searchParams.get('name') || `client-${Date.now()}`;
  } catch {
    /* keep default */
  }
  const client = { name, ws, connectedAt: Date.now(), lastFrameAt: 0, frames: 0 };
  clients.set(name, client);
  console.log(`[push] client "${name}" connected (${clients.size} total)`);

  ws.on('message', (data) => {
    let frame;
    try {
      frame = JSON.parse(data.toString());
    } catch {
      return;
    }
    if (!frame || typeof frame !== 'object') return;
    if (!Array.isArray(frame.players)) frame.players = [];
    frame.receivedAt = Date.now();
    frame.seq = ++frameSeq;
    frame.client = name;
    client.lastFrameAt = frame.receivedAt;
    client.frames += 1;
    // refresh the fps sample every ~2s
    if (!client.fpsCalc || frame.receivedAt - client.fpsCalc.at >= 2000) {
      client.fpsCalc = { at: frame.receivedAt, frames: client.frames };
    }
    latestFrame = frame;
  });

  ws.on('close', () => {
    if (clients.get(name)?.ws === ws) {
      clients.delete(name);
    }
    console.log(`[push] client "${name}" disconnected (${clients.size} total)`);
  });

  ws.on('error', () => {
    /* ignore; close handler cleans up */
  });
});

// --- browser viewers ---
wssView.on('connection', (ws) => {
  viewers.add(ws);
  ws.isAlive = true;
  console.log(`[view] browser connected (${viewers.size} total)`);

  ws.on('pong', () => {
    ws.isAlive = true;
  });
  ws.on('message', (data) => {
    const m = String(data).trim();
    if (m === 'ping') {
      ws.send(JSON.stringify({ type: 'pong', t: Date.now() }));
    }
  });
  ws.on('close', () => {
    viewers.delete(ws);
    console.log(`[view] browser disconnected (${viewers.size} total)`);
  });
  ws.on('error', () => {
    viewers.delete(ws);
  });
});

// ---------------------------------------------------------------------------
// Broadcast loops
// ---------------------------------------------------------------------------
let lastBroadcastSeq = 0;
let lastStatusSentAt = 0;

function sendToViewers(payload) {
  for (const v of viewers) {
    // backpressure: skip sockets that are still busy sending the previous frame
    if (v.readyState === WebSocket.OPEN && v.bufferedAmount === 0) {
      try {
        v.send(payload);
      } catch {
        /* ignore */
      }
    }
  }
}

setInterval(() => {
  // 1) latest radar frame, rate-limited
  if (latestFrame && latestFrame.seq !== lastBroadcastSeq) {
    lastBroadcastSeq = latestFrame.seq;
    sendToViewers(JSON.stringify(latestFrame));
  }

  // 2) periodic status frame so the UI can show connection state
  if (Date.now() - lastStatusSentAt >= STATUS_INTERVAL_MS) {
    lastStatusSentAt = Date.now();
    sendToViewers(JSON.stringify({ type: 'status', ...statusJson() }));
  }
}, BROADCAST_INTERVAL_MS);

// viewer keepalive / dead-socket cleanup
setInterval(() => {
  for (const v of viewers) {
    if (v.isAlive === false) {
      viewers.delete(v);
      try {
        v.terminate();
      } catch {
        /* ignore */
      }
      continue;
    }
    v.isAlive = false;
    try {
      v.ping();
    } catch {
      /* ignore */
    }
  }
}, VIEWER_HEARTBEAT_MS);

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------
server.listen(PORT, HOST, () => {
  console.log(`[server] CS2 DMA Radar server listening on http://${HOST}:${PORT}`);
  console.log(`[server]   web UI        http://<host>:${PORT}/`);
  console.log(`[server]   client push   ws://<host>:${PORT}/push`);
  console.log(`[server]   browser view  ws://<host>:${PORT}/ws`);
  console.log(`[server]   status        http://<host>:${PORT}/api/status`);
});

function shutdown() {
  console.log('\n[server] shutting down...');
  for (const v of viewers) {
    try {
      v.close();
    } catch {
      /* ignore */
    }
  }
  for (const c of clients.values()) {
    try {
      c.ws.close();
    } catch {
      /* ignore */
    }
  }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
