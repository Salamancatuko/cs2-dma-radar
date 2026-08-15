/**
 * CS2 DMA Radar - fake push client (dev tool)
 *
 * Simulates the Java radar client without DMA hardware: connects to the
 * server's /push endpoint and streams synthetic radar frames with a few
 * players moving in circles on de_mirage. Useful to test the web UI and
 * the whole pipeline before the real hardware is available.
 *
 * Usage:  node test/simulate-client.js [ws://host:port/push]
 */
import WebSocket from 'ws';

const url = process.argv[2] || 'ws://127.0.0.1:8080/push?name=simulator';
const INTERVAL_MS = 10;
const TAU = Math.PI * 2;

const players = [
    { name: 'local',  team: 2, cx: -100, cy: -80, r: 40, speed: 0.6, local: true, color: 1 },
    { name: 'enemy1', team: 3, cx: 60,   cy: 40,  r: 60, speed: 0.5, local: false, color: -1 },
    { name: 'enemy2', team: 3, cx: -40,  cy: 120, r: 35, speed: 0.8, local: false, color: -1 },
    { name: 'mate1',  team: 2, cx: 120,  cy: -90, r: 50, speed: 0.4, local: false, color: 0 },
    { name: 'mate2',  team: 2, cx: -160, cy: 60,  r: 30, speed: 0.7, local: false, color: 2 }
];

const ws = new WebSocket(url);

ws.on('open', () => {
    console.log(`[sim] connected to ${url}`);
    let t = 0;
    setInterval(() => {
        const list = players.map((p, i) => {
            const a = (t / 1000) * p.speed * TAU + i * 1.7;
            return {
                entityPawnAddress: 0x1000 + i,
                entityAddress: 0x2000 + i,
                teamId: p.team,
                health: 100 - ((t / 5000) | 0) % 30,
                armor: 50,
                alive: true,
                localPlayer: p.local,
                enemy: p.team === 3,
                sameLevel: true,
                compTeammateColor: p.color,
                x: p.cx + Math.cos(a) * p.r,
                y: p.cy + Math.sin(a) * p.r,
                z: 0,
                angles: (a * 180 / Math.PI) % 360
            }
        });
        const frame = { map: 'de_mirage', players: list, t: Date.now(), dt: INTERVAL_MS, game: true };
        ws.send(JSON.stringify(frame));
        t += INTERVAL_MS;
    }, INTERVAL_MS);
});

ws.on('close', () => {
    console.log('[sim] connection closed');
    process.exit(0);
});
ws.on('error', (e) => {
    console.error('[sim] error:', e.message);
    process.exit(1);
});
