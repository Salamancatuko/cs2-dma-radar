package cs2.dma.main;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket client that pushes radar frames to the radar server ({@code /push} endpoint).
 * Runs a background reconnection loop: if the server is unreachable it keeps retrying
 * with a short backoff, so the client tolerates server restarts.
 */
public class RadarPushClient {

    private final String uri;
    private final Object lock = new Object();
    private volatile WebSocketClient client;
    private volatile boolean stop = false;
    private long framesSent = 0;
    private long lastSentAt = 0;

    public RadarPushClient(String host, int port, String clientName) {
        String name = URLEncoder.encode(clientName == null || clientName.isEmpty() ? "radar-1" : clientName,
                StandardCharsets.UTF_8);
        this.uri = String.format("ws://%s:%d/push?name=%s", host, port, name);
    }

    /** Start the background connection/reconnection loop. */
    public void start() {
        Thread t = new Thread(this::runLoop, "radar-push-connector");
        t.setDaemon(true);
        t.start();
    }

    private void runLoop() {
        while (!stop) {
            synchronized (lock) {
                if (client != null && client.isOpen()) {
                    // connected - just wait
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
            }
            try {
                connectOnce();
                // connected: wait for the socket to die before reconnecting
                synchronized (lock) {
                    if (!stop) {
                        try {
                            lock.wait(1000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[-] WebSocket connect failed (" + uri + "): " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void connectOnce() throws Exception {
        WebSocketClient c = new WebSocketClient(new URI(uri)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("[+] Connected to radar server: " + uri);
            }

            @Override
            public void onMessage(String message) {
                // the server does not send us anything meaningful
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("[-] WebSocket closed (" + code + "): " + reason);
                synchronized (lock) {
                    if (client == this) {
                        client = null;
                    }
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(Exception ex) {
                // connectBlocking() throws on connection errors; nothing to do here
            }
        };
        c.setConnectionLostTimeout(30);
        synchronized (lock) {
            if (stop) {
                return;
            }
            client = c;
        }
        c.connectBlocking();
    }

    /** Send one JSON frame. Silently drops it when the connection is not open. */
    public void send(String json) {
        WebSocketClient c;
        synchronized (lock) {
            c = client;
        }
        if (c != null && c.isOpen()) {
            c.send(json);
            framesSent++;
            lastSentAt = System.currentTimeMillis();
        }
    }

    public boolean isOpen() {
        synchronized (lock) {
            return client != null && client.isOpen();
        }
    }

    public long getFramesSent() {
        return framesSent;
    }

    public long getLastSentAt() {
        return lastSentAt;
    }

    public void stop() {
        stop = true;
        synchronized (lock) {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
                client = null;
            }
            lock.notifyAll();
        }
    }
}
