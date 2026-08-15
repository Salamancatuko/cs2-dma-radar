/**
 * CS2 DMA Radar - viewer test (dev tool)
 *
 * Connects to the server's /ws endpoint like a browser would, prints frame
 * rate / status info for N seconds, then exits.
 *
 * Usage:  node test/viewer-test.js [ws://host:port/ws] [seconds]
 */
import WebSocket from 'ws';

const url = process.argv[2] || 'ws://127.0.0.1:8080/ws';
const seconds = parseInt(process.argv[3] || '5', 10);

const ws = new WebSocket(url);
let frames = 0;
let statusFrames = 0;
let lastStatus = null;

ws.on('open', () => {
    console.log(`[viewer] connected to ${url}, collecting for ${seconds}s...`);
    setTimeout(() => {
        console.log(`[viewer] done: ${frames} data frames, ${statusFrames} status frames`);
        if (lastStatus) {
            console.log(`[viewer] last status: clientConnected=${lastStatus.clientConnected} viewers=${lastStatus.viewers}`);
        }
        ws.close();
        process.exit(0);
    }, seconds * 1000);
});

ws.on('message', (data) => {
    let msg;
    try {
        msg = JSON.parse(data.toString());
    } catch {
        return;
    }
    if (msg.type === 'status') {
        statusFrames++;
        lastStatus = msg;
    } else if (msg.players) {
        frames++;
        if (frames === 1 || frames % 50 === 0) {
            console.log(`[viewer] frame #${frames} map=${msg.map} players=${msg.players.length} dt=${msg.dt}`);
        }
    }
});

ws.on('close', () => console.log('[viewer] closed'));
ws.on('error', (e) => {
    console.error('[viewer] error:', e.message);
    process.exit(1);
});
