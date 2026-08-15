package cs2.dma.main;

import com.alibaba.fastjson.JSONObject;
import cs2.dma.entry.PlayerInfo;
import cs2.dma.tuil.GameDataManager;
import cs2.dma.tuil.OffsetsUpdater;

import java.util.ArrayList;
import java.util.List;

/**
 * CS2 DMA Radar - Client (采集/推送端).
 *
 * This process ONLY does two things:
 *   1. reads CS2 memory through the DMA hardware (vmm.dll / leechcore.dll),
 *   2. transmits the resulting frames to the radar server over WebSocket.
 *
 * There is deliberately no web server and no UI here - the web frontend is
 * hosted by the server (see the "server" and "web" folders).
 */
public class Application {

    public static void main(String[] args) throws InterruptedException {
        Config config = Config.load();
        System.out.println("==============================================");
        System.out.println(" CS2 DMA Radar - Client (data read + transmit)");
        System.out.println("==============================================");

        // 0. Auto-update offsets.json from a2x/cs2-dumper (optional, never fatal)
        final OffsetsUpdater offsetsUpdater =
                config.autoUpdateOffsets ? new OffsetsUpdater(config.offsetsUpdateIntervalMs) : null;
        if (offsetsUpdater != null) {
            reloadOffsetsIfUpdated(offsetsUpdater);
        }

        // 1. Initialize the DMA connection through vmm.dll/leechcore.dll
        GameDataManager manager = new GameDataManager();
        if (!manager.initializeVmm()) {
            System.err.println("[-] Failed to initialize VMM. Check that ./vmm contains "
                    + "vmm.dll, leechcore.dll and FTD3XX.dll (run setup.bat) and that "
                    + "the DMA card is plugged in.");
            System.exit(1);
        }
        System.out.println("[+] VMM initialized successfully!");

        // 2. Start the WebSocket pusher (reconnects automatically)
        RadarPushClient pusher = new RadarPushClient(config.serverHost, config.serverPort, config.clientName);
        pusher.start();

        // 3. Watcher thread: blockingly waits for / holds the cs2.exe attachment.
        //    Kept separate so the push loop can keep sending heartbeats while the
        //    game is not running.
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (manager.tryAttachOnce()) {
                    // game started: refresh offsets (rate-limited inside the updater)
                    if (offsetsUpdater != null) {
                        reloadOffsetsIfUpdated(offsetsUpdater);
                    }
                    while (manager.isGameRunning() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    System.out.println("[-] cs2.exe process lost - waiting to re-attach...");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "game-watcher");
        watcher.setDaemon(true);
        watcher.start();

        // 4. Main push loop
        long lastHeartbeat = 0;
        long statsStart = System.currentTimeMillis();
        long framesInWindow = 0;

        while (true) {
            long t0 = System.currentTimeMillis();
            try {
                if (manager.isGameRunning()) {
                    manager.readFrame();
                    JSONObject frame = new JSONObject();
                    frame.put("map", manager.getMapName() == null ? "" : manager.getMapName());
                    frame.put("players", manager.getPlayerInfoList());
                    frame.put("killFeed", manager.drainKillFeed());
                    frame.put("t", System.currentTimeMillis());
                    frame.put("dt", (int) (System.currentTimeMillis() - t0));
                    pusher.send(frame.toJSONString());
                    framesInWindow++;
                } else {
                    // heartbeat so the server/web still see this client as alive
                    // while no game is attached
                    if (System.currentTimeMillis() - lastHeartbeat >= 1000) {
                        lastHeartbeat = System.currentTimeMillis();
                        JSONObject frame = new JSONObject();
                        frame.put("map", "");
                        frame.put("players", new ArrayList<PlayerInfo>());
                        frame.put("t", System.currentTimeMillis());
                        frame.put("dt", 0);
                        frame.put("game", false);
                        pusher.send(frame.toJSONString());
                    }
                }
            } catch (Exception e) {
                System.out.println("[-] Frame error: " + e.getMessage());
            }

            long elapsed = System.currentTimeMillis() - t0;
            long sleep = Math.max(0, config.pushIntervalMs - elapsed);
            if (sleep > 0) {
                Thread.sleep(sleep);
            }

            long now = System.currentTimeMillis();
            if (now - statsStart >= 5000) {
                double fps = framesInWindow * 1000.0 / (now - statsStart);
                List<PlayerInfo> list = manager.getPlayerInfoList();
                System.out.printf("[+] push=%d fps=%.1f ws=%b game=%b map=%s players=%d%n",
                        pusher.getFramesSent(), fps, pusher.isOpen(), manager.isGameRunning(),
                        manager.getMapName() == null ? "" : manager.getMapName(),
                        list == null ? 0 : list.size());
                framesInWindow = 0;
                statsStart = now;
            }
        }
    }

    /**
     * Check the remote offsets; when the file was rewritten, reload the static
     * offsets in-memory so the change applies without restarting the client.
     */
    private static void reloadOffsetsIfUpdated(OffsetsUpdater updater) {
        if (!updater.checkAndUpdate()) {
            return;
        }
        boolean ok = GameDataManager.reloadOffsets();
        System.out.println(ok ? "[+] Offsets reloaded in-memory." : "[!] Offsets file updated but reload failed.");
    }
}
