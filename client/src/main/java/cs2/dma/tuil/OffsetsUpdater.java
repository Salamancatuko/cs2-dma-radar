package cs2.dma.tuil;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automatically keeps offsets.json in sync with the latest CS2 offsets from
 * a2x/cs2-dumper. Called at client startup and after each game (re)attach,
 * rate-limited so it does not hit GitHub too often.
 *
 * Never fatal: on any network/parse error the existing offsets.json stays
 * untouched and the client keeps running with the old values.
 */
public class OffsetsUpdater {

    private static final String OFFSETS_URL =
            "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/offsets.json";
    private static final String CLIENT_URL =
            "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/client_dll.json";
    private static final String OFFSETS_FILE = "offsets.json";

    private final long minCheckIntervalMs;
    private volatile long lastCheckAt = 0;

    public OffsetsUpdater(long minCheckIntervalMs) {
        this.minCheckIntervalMs = minCheckIntervalMs;
    }

    public OffsetsUpdater() {
        this(10 * 60 * 1000L);
    }

    /** Returns true when the file was actually rewritten. */
    public synchronized boolean checkAndUpdate() {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < minCheckIntervalMs) {
            return false;
        }
        lastCheckAt = now;

        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest reqOffsets = HttpRequest.newBuilder(URI.create(OFFSETS_URL))
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpRequest reqClient = HttpRequest.newBuilder(URI.create(CLIENT_URL))
                    .timeout(Duration.ofSeconds(20)).GET().build();

            HttpResponse<String> resOffsets = http.send(reqOffsets, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> resClient = http.send(reqClient, HttpResponse.BodyHandlers.ofString());
            if (resOffsets.statusCode() != 200 || resClient.statusCode() != 200) {
                System.out.println("[!] Offsets fetch failed (HTTP " + resOffsets.statusCode()
                        + "/" + resClient.statusCode() + ") - keeping current offsets.");
                return false;
            }

            Map<String, String> fresh = buildOffsets(resOffsets.body(), resClient.body());
            if (fresh == null) {
                return false;
            }

            Map<String, String> current = readCurrent();
            if (fresh.equals(current)) {
                System.out.println("[+] Offsets are up to date.");
                return false;
            }

            // merge: preserve any extra local keys, overwrite with fresh values
            JSONObject out = new JSONObject(new LinkedHashMap<>());
            if (current != null) {
                out.putAll(current);
            }
            out.putAll(fresh);

            Files.write(new File(OFFSETS_FILE).toPath(),
                    JSON.toJSONString(out, SerializerFeature.PrettyFormat).getBytes(StandardCharsets.UTF_8));
            System.out.println("[+] Offsets updated: " + diffSummary(current, fresh));
            return true;
        } catch (Exception e) {
            System.out.println("[!] Offsets update failed: " + e.getMessage() + " - keeping current offsets.");
            return false;
        }
    }

    private Map<String, String> readCurrent() {
        try {
            File f = new File(OFFSETS_FILE);
            if (!f.exists()) {
                return null;
            }
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject o = JSON.parseObject(text);
            Map<String, String> m = new LinkedHashMap<>();
            if (o != null) {
                for (String k : o.keySet()) {
                    m.put(k, o.getString(k));
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> buildOffsets(String offsetsBody, String clientBody) throws Exception {
        JSONObject o = JSON.parseObject(offsetsBody);
        JSONObject c = JSON.parseObject(clientBody);
        JSONObject clientDll = o.getJSONObject("client.dll");
        JSONObject mmDll = o.getJSONObject("matchmaking.dll");
        JSONObject classes = c.getJSONObject("client.dll").getJSONObject("classes");
        if (clientDll == null || mmDll == null || classes == null) {
            throw new IllegalStateException("unexpected cs2-dumper JSON layout");
        }

        Map<String, String> m = new LinkedHashMap<>();
        m.put("dwLocalPlayerPawn", hex(clientDll.getLong("dwLocalPlayerPawn")));
        m.put("dwEntityList", hex(clientDll.getLong("dwEntityList")));
        m.put("dwGameTypes", hex(mmDll.getLong("dwGameTypes")));
        m.put("dwGlobalVars", hex(clientDll.getLong("dwGlobalVars")));
        m.put("m_iHealth", hex(field(classes, "C_BaseEntity", "m_iHealth")));
        m.put("m_iPawnArmor", hex(field(classes, "CCSPlayerController", "m_iPawnArmor")));
        m.put("m_lifeState", hex(field(classes, "C_BaseEntity", "m_lifeState")));
        m.put("m_angEyeAngles", hex(field(classes, "C_CSPlayerPawn", "m_angEyeAngles")));
        m.put("m_iTeamNum", hex(field(classes, "C_BaseEntity", "m_iTeamNum")));
        m.put("m_hPlayerPawn", hex(field(classes, "CCSPlayerController", "m_hPlayerPawn")));
        m.put("m_vOldOrigin", hex(field(classes, "C_BasePlayerPawn", "m_vOldOrigin")));
        m.put("m_iCompTeammateColor", hex(field(classes, "CCSPlayerController", "m_iCompTeammateColor")));
        m.put("m_iszPlayerName", hex(field(classes, "CBasePlayerController", "m_iszPlayerName")));
        // kill feed chain
        m.put("m_pDamageServices", hex(field(classes, "CCSPlayerController", "m_pDamageServices")));
        m.put("m_DamageList", hex(field(classes, "CCSPlayerController_DamageServices", "m_DamageList")));
        m.put("m_hPlayerControllerDamager", hex(field(classes, "CDamageRecord", "m_hPlayerControllerDamager")));
        m.put("m_hPlayerControllerRecipient", hex(field(classes, "CDamageRecord", "m_hPlayerControllerRecipient")));
        m.put("m_szPlayerRecipientName", hex(field(classes, "CDamageRecord", "m_szPlayerRecipientName")));
        m.put("m_szPlayerDamagerName", hex(field(classes, "CDamageRecord", "m_szPlayerDamagerName")));
        return m;
    }

    private static long field(JSONObject classes, String cls, String field) {
        JSONObject cl = classes.getJSONObject(cls);
        if (cl == null) {
            throw new IllegalStateException("class not found in cs2-dumper dump: " + cls);
        }
        Long v = cl.getJSONObject("fields").getLong(field);
        if (v == null) {
            throw new IllegalStateException("field not found in cs2-dumper dump: " + cls + "." + field);
        }
        return v;
    }

    private static String hex(long v) {
        return String.format("0x%x", v);
    }

    private static String diffSummary(Map<String, String> oldMap, Map<String, String> fresh) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fresh.entrySet()) {
            String old = oldMap == null ? null : oldMap.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append(" ").append(old == null ? "new" : old).append(" -> ").append(e.getValue());
            }
        }
        return sb.length() == 0 ? "no visible diff" : sb.toString();
    }
}
