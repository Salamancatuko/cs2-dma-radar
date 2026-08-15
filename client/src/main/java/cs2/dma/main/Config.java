package cs2.dma.main;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Client configuration, loaded from {@code config.json} next to the jar.
 * All fields have sane defaults so the client also runs without the file.
 */
public class Config {

    /** Radar server host (IP or hostname). Set to the server's public/LAN address. */
    public String serverHost = "127.0.0.1";
    /** Radar server port. */
    public int serverPort = 8080;
    /**
     * Minimum interval between two pushed frames in milliseconds.
     * Default 50ms (~20 fps): keeps the read rate well inside the DMA card's
     * comfortable range to avoid link drops ("断流"). Faster cards may lower this.
     */
    public int pushIntervalMs = 50;
    /** Name this client is registered under on the server (shown in status). */
    public String clientName = "radar-1";
    /** VMM device, normally "FPGA" for a DMA card. */
    public String device = "FPGA";
    /** Fetch and apply the latest CS2 offsets from a2x/cs2-dumper on startup and after each game (re)attach. */
    public boolean autoUpdateOffsets = true;
    /** Minimum interval between two offset-update checks (milliseconds). */
    public long offsetsUpdateIntervalMs = 10 * 60 * 1000L;

    public static Config load() {
        Config cfg = new Config();
        File f = new File("config.json");
        if (!f.exists()) {
            System.out.println("[!] config.json not found next to the client - using defaults.");
            return cfg;
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject o = JSON.parseObject(text);
            if (o == null) {
                System.out.println("[!] config.json is empty - using defaults.");
                return cfg;
            }
            cfg.serverHost = o.getString("serverHost") == null ? cfg.serverHost : o.getString("serverHost");
            Integer serverPort = o.getInteger("serverPort");
            if (serverPort != null) {
                cfg.serverPort = serverPort;
            }
            Integer pushIntervalMs = o.getInteger("pushIntervalMs");
            if (pushIntervalMs != null) {
                cfg.pushIntervalMs = pushIntervalMs;
            }
            cfg.clientName = o.getString("clientName") == null ? cfg.clientName : o.getString("clientName");
            cfg.device = o.getString("device") == null ? cfg.device : o.getString("device");
            Boolean auto = o.getBoolean("autoUpdateOffsets");
            if (auto != null) {
                cfg.autoUpdateOffsets = auto;
            }
            Long interval = o.getLong("offsetsUpdateIntervalMs");
            if (interval != null) {
                cfg.offsetsUpdateIntervalMs = interval;
            }
            System.out.println("[+] config.json loaded: server=" + cfg.serverHost + ":" + cfg.serverPort
                    + " pushIntervalMs=" + cfg.pushIntervalMs + " clientName=" + cfg.clientName
                    + " autoUpdateOffsets=" + cfg.autoUpdateOffsets);
        } catch (Exception e) {
            System.out.println("[-] Failed to parse config.json: " + e.getMessage());
        }
        return cfg;
    }
}
