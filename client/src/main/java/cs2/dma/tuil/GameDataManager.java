package cs2.dma.tuil;

import cs2.dma.entry.PlayerInfo;
import vmm.IVmm;
import vmm.IVmmMemScatterMemory;
import vmm.IVmmProcess;

import java.io.FileReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.alibaba.fastjson.parser.DefaultJSONParser;

/**
 * Game data reader. Resolves the game process through VMM and produces the list
 * of player entities for one "frame", using VMM scatter reads (one native call
 * per phase instead of hundreds of single reads) so the achievable frame rate
 * is only limited by the DMA link, not by per-read overhead or thread churn.
 *
 * <p>Phases:
 * <ul>
 *   <li>{@link #initializeGameData()} - blocking; waits until cs2.exe is attached.
 *       Run from a dedicated watcher thread.</li>
 *   <li>{@link #readFrame()} - non-blocking; reads one frame of player data.
 *       Run from the main push loop.</li>
 * </ul>
 */
public class GameDataManager {

    // ---- offsets (loaded from offsets.json, auto-updated) ----
    private static long dwLocalPlayerPawn = 0x0;
    private static long dwEntityList = 0x0;
    private static long dwGameTypes = 0x0;
    private static long dwGlobalVars = 0x0;

    private static long m_iHealth = 0x0;
    private static long m_iPawnArmor = 0x0;
    private static long m_lifeState = 0x0;
    private static long m_angEyeAngles = 0x0;
    private static long m_iTeamNum = 0x0;
    private static long m_hPlayerPawn = 0x0;
    private static long m_vOldOrigin = 0x0;
    private static long m_iCompTeammateColor = 0x0;
    private static long m_iszPlayerName = 0x0;

    // kill feed chain
    private static long m_pDamageServices = 0x0;
    private static long m_DamageList = 0x0;
    private static long m_hPlayerControllerDamager = 0x0;
    private static long m_hPlayerControllerRecipient = 0x0;
    private static long m_szPlayerRecipientName = 0x0;
    private static long m_szPlayerDamagerName = 0x0;

    private static final long GLOBAL_VARS_MAPNAME_OFFSET = 0x0188;
    private static final int MAX_SLOTS = 64;
    private static final int SLOT_STRIDE = 0x70;
    /** re-probe the entity list slot pointers every N frames (they are stable during a match) */
    private static final int SLOT_CACHE_FRAMES = 30;

    static {
        try {
            FileReader reader = new FileReader("offsets.json");
            char[] buf = new char[1024];
            int len = 0;

            StringBuilder sb = new StringBuilder();
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            reader.close();
            if (!applyOffsets(sb.toString())) {
                System.exit(1);
            }
        } catch (Exception e) {
            System.out.println("[-] Failed to read offsets.json file: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Reload offsets.json at runtime (after the auto-updater rewrites the file). */
    public static synchronized boolean reloadOffsets() {
        try {
            String json = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File("offsets.json").toPath()), StandardCharsets.UTF_8);
            return applyOffsets(json);
        } catch (Exception e) {
            System.out.println("[-] Failed to reload offsets.json: " + e.getMessage());
            return false;
        }
    }

    private static boolean applyOffsets(String json) {
        try {
            DefaultJSONParser parser = new DefaultJSONParser(json);
            Map<String, String> map = parser.parseObject(Map.class, Long.class);
            parser.close();

            dwLocalPlayerPawn = hex(map, "dwLocalPlayerPawn");
            dwEntityList = hex(map, "dwEntityList");
            dwGameTypes = hex(map, "dwGameTypes");
            dwGlobalVars = hex(map, "dwGlobalVars");
            m_iHealth = hex(map, "m_iHealth");
            m_iPawnArmor = hex(map, "m_iPawnArmor");
            m_lifeState = hex(map, "m_lifeState");
            m_angEyeAngles = hex(map, "m_angEyeAngles");
            m_iTeamNum = hex(map, "m_iTeamNum");
            m_hPlayerPawn = hex(map, "m_hPlayerPawn");
            m_vOldOrigin = hex(map, "m_vOldOrigin");
            m_iCompTeammateColor = hex(map, "m_iCompTeammateColor");
            m_iszPlayerName = hex(map, "m_iszPlayerName");
            m_pDamageServices = hex(map, "m_pDamageServices");
            m_DamageList = hex(map, "m_DamageList");
            m_hPlayerControllerDamager = hex(map, "m_hPlayerControllerDamager");
            m_hPlayerControllerRecipient = hex(map, "m_hPlayerControllerRecipient");
            m_szPlayerRecipientName = hex(map, "m_szPlayerRecipientName");
            m_szPlayerDamagerName = hex(map, "m_szPlayerDamagerName");
            return true;
        } catch (Exception e) {
            System.out.println("[-] Failed to parse offsets.json: " + e.getMessage());
            return false;
        }
    }

    private static long hex(Map<String, String> map, String key) {
        String v = map.get(key);
        return v == null ? 0 : Long.parseLong(v.replace("0x", ""), 16);
    }

    private final String knowMap = "de_ancient,de_dust2,de_inferno,de_mirage,de_nuke,de_overpass,de_train,de_vertigo,de_anubis";
    private static String[] argvMemProcFS = { "", "-device", "FPGA" };

    private static IVmmProcess gameProcess;
    private static MemoryTool memoryTool;

    private static long clientAddress;
    private long EntityList;                 // player controller list base
    private long pawnListP;                  // P = *(client.dll + dwEntityList); pawn entries live at P + 0x10
    private long LocalPlayerController;      // actually the local player's PAWN address (dwLocalPlayerPawn)
    private long mapNameAddress;
    private static List<PlayerInfo> playerInfoList;

    private String mapName = "";

    private IVmm vmm;
    private GameProcessMonitor processMonitor;

    private IVmmMemScatterMemory scatter;

    private static final long PROCESS_RETRY_DELAY = 5000;

    // ---- per-frame caches ----
    private final long[] slotCache = new long[MAX_SLOTS];
    private int frameCounter = 0;
    private int refreshFailCount = 0;
    private int attachFailStreak = 0;

    /**
     * Escalating failure handling: a few bad frames -> re-attach to cs2.exe;
     * a sustained failure burst -> rebuild the whole VMM connection (DMA 断流恢复).
     */
    private void onRefreshFailure() {
        if (++refreshFailCount < 5) {
            return;
        }
        refreshFailCount = 0;
        if (++attachFailStreak >= 6) {
            attachFailStreak = 0;
            reinitializeVmm();
        } else {
            System.out.println("[-] Game data unreadable - forcing re-attach...");
            invalidateProcess();
        }
    }

    // ---- kill feed tracking ----
    private final Map<Long, Boolean> prevAlive = new HashMap<>();
    private final List<KillEvent> pendingKills = new ArrayList<>();
    /** controller address -> { damagerName, damagerHandle } of the latest damage record.
     *  The victim's damage list is cleared on death, so we cache it continuously. */
    private final Map<Long, String[]> lastDamagers = new HashMap<>();
    /** damage record array layout (empirically verified): count at +0x48, ptr at +0x50, stride 0x80 */
    private static final long DMG_LIST_COUNT_OFF = 0x48;
    private static final long DMG_LIST_PTR_OFF = 0x50;
    private static final int DMG_RECORD_STRIDE = 0x80;

    /** A single death/kill event reported to the web frontend. */
    public static class KillEvent {
        private String name;         // victim
        private int team;            // victim team (2=CT, 3=T)
        private String killer = "";
        private int killerTeam;
        private long t;

        public KillEvent() {
        }

        public KillEvent(String name, int team, long t) {
            this.name = name;
            this.team = team;
            this.t = t;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getTeam() { return team; }
        public void setTeam(int team) { this.team = team; }
        public String getKiller() { return killer; }
        public void setKiller(String killer) { this.killer = killer; }
        public int getKillerTeam() { return killerTeam; }
        public void setKillerTeam(int killerTeam) { this.killerTeam = killerTeam; }
        public long getT() { return t; }
        public void setT(long t) { this.t = t; }
    }

    /** Initialize the VMM native library (vmm.dll / leechcore.dll in ./vmm). */
    public boolean initializeVmm() {
        String os = System.getProperty("os.name", "").toLowerCase();
        System.out.println("[*] Detected OS: " + os);
        boolean isLinux = os.contains("linux");
        String baseDir = System.getProperty("user.dir");
        String vmmPath = baseDir + (isLinux ? "/vmm" : "\\vmm");

        String memmapPath = baseDir + (isLinux ? "/memmap.txt" : "\\memmap.txt");
        if (new java.io.File(memmapPath).exists()) {
            System.out.println("[*] Using existing memmap.txt at: " + memmapPath);
            argvMemProcFS = new String[] { "", "-device", "FPGA", "-memmap", "memmap.txt" };
        }

        try {
            this.vmm = IVmm.initializeVmm(vmmPath, argvMemProcFS);
        } catch (Throwable t) {
            System.out.println("[-] Failed to load VMM native library from: " + vmmPath);
            System.out.println("    " + t.getMessage());
            System.out.println("    Make sure setup.bat was run and the folder contains");
            System.out.println("    vmm.dll, leechcore.dll and FTD3XX.dll.");
            return false;
        }
        try {
            vmm.setConfig(IVmm.VMMDLL_OPT_REFRESH_FREQ_FAST, 1);
        } catch (Throwable t) {
            System.out.println("[-] VMM loaded but configuration failed: " + t.getMessage());
            return false;
        }
        if (vmm.isValid()) {
            this.processMonitor = new GameProcessMonitor(vmm);
            return true;
        }
        return false;
    }

    public IVmm getVmm() {
        return vmm;
    }

    public String getMapName() {
        return mapName;
    }

    /** True while the monitored cs2.exe process is attached and valid. */
    public boolean isGameRunning() {
        return processMonitor != null && processMonitor.isCurrentProcessValid();
    }

    /**
     * One-shot attach attempt: look up cs2.exe, bind a fresh process handle and
     * scatter, resolve client.dll. Returns true when the game is readable.
     * Non-blocking - callers poll at their own cadence.
     */
    public boolean tryAttachOnce() {
        try {
            if (processMonitor == null) {
                return false;
            }
            IVmmProcess p = processMonitor.pollProcess();
            if (p == null) {
                return false;
            }
            gameProcess = p;
            memoryTool = new MemoryTool(p);
            if (scatter != null) {
                try {
                    scatter.close();
                } catch (Exception ignored) {
                }
                scatter = null;
            }
            scatter = gameProcess.memScatterInitialize(0);
            clientAddress = 0;
            if (refreshGameData()) {
                Arrays.fill(slotCache, 0);
                frameCounter = 0;
                refreshFailCount = 0;
                attachFailStreak = 0;
                System.out.println("[+] Attached to cs2.exe (pid=" + p.getPID() + ")");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Force a re-attach: the cached process handle is stale or the DMA link
     * hiccupped. After several consecutive failures the VMM connection itself is
     * rebuilt (see {@link #reinitializeVmm()}).
     */
    public void invalidateProcess() {
        gameProcess = null;
        memoryTool = null;
        clientAddress = 0;
        if (processMonitor != null) {
            processMonitor.reset();
        }
        if (scatter != null) {
            try {
                scatter.close();
            } catch (Exception ignored) {
            }
            scatter = null;
        }
    }

    /**
     * Tear down and rebuild the whole VMM/leechcore connection. Used to recover
     * from a dropped DMA link ("断流"): when the link comes back, a fresh
     * VMMDLL_Initialize reconnects to the FPGA card.
     */
    public synchronized void reinitializeVmm() {
        System.out.println("[-] DMA link issue detected - reinitializing VMM...");
        try {
            if (vmm != null) {
                vmm.close();
            }
        } catch (Exception ignored) {
        }
        vmm = null;
        processMonitor = null;
        invalidateProcess();
        initializeVmm();
        System.out.println(vmm != null && vmm.isValid()
                ? "[+] VMM reinitialized."
                : "[!] VMM reinit failed - will retry on next failure burst.");
    }

    // ------------------------------------------------------------------
    // Scatter helpers
    // ------------------------------------------------------------------
    private static final class Batch {
        final IVmmMemScatterMemory sc;
        final List<Long> addrs = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();

        Batch(IVmmMemScatterMemory sc) {
            this.sc = sc;
        }

        void prep(long va, int size) {
            addrs.add(va);
            sizes.add(size);
        }

        void run() {
            sc.clear();
            for (int i = 0; i < addrs.size(); i++) {
                sc.prepare(addrs.get(i), sizes.get(i));
            }
            sc.execute();
        }

        byte[] get(int i) {
            return sc.read(addrs.get(i), sizes.get(i));
        }

        void reset() {
            addrs.clear();
            sizes.clear();
        }
    }

    private static long u64(byte[] b, int off) {
        if (b == null || b.length < off + 8) return 0;
        long v = 0;
        for (int i = 0; i < 8; i++) v |= (b[off + i] & 0xFFL) << (8 * i);
        return v;
    }

    private static int u32(byte[] b, int off) {
        if (b == null || b.length < off + 4) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static float f32(byte[] b, int off) {
        if (b == null || b.length < off + 4) return 0f;
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    // ------------------------------------------------------------------
    // Frame reading
    // ------------------------------------------------------------------
    public void readFrame() {
        try {
            if (!refreshGameData()) {
                playerInfoList = new ArrayList<>();
                mapName = "";
                prevAlive.clear();
                pendingKills.clear();
                onRefreshFailure();
                return;
            }
            refreshFailCount = 0;

            frameCounter++;
            // player names barely change; refresh the cache every 30 frames
            boolean refreshNames = frameCounter % 30 == 0;
            mapName = readCurrentMapName();
            boolean isKnowMap = mapName != null && !"".equals(mapName) && knowMap.contains(mapName);

            List<PlayerInfo> list = new ArrayList<>();

            // ---- phase 1: local pawn + slot pointers ----
            Batch b1 = new Batch(scatter);
            b1.prep(clientAddress + dwLocalPlayerPawn, 8);
            boolean needSlotProbe = frameCounter % SLOT_CACHE_FRAMES == 1;
            if (needSlotProbe) {
                for (int i = 0; i < MAX_SLOTS; i++) {
                    b1.prep(EntityList + (i + 1) * SLOT_STRIDE, 8);
                }
            }
            b1.run();

            long localPawn = u64(b1.get(0), 0);
            if (needSlotProbe) {
                for (int i = 0; i < MAX_SLOTS; i++) {
                    slotCache[i] = u64(b1.get(1 + i), 0);
                }
            }
            b1.reset();

            if (localPawn == 0) {
                playerInfoList = list;
                return;
            }
            LocalPlayerController = localPawn;

            // ---- phase 2: per-slot controller fields + local player fields ----
            // NB: scatter reads fail on non-4-byte-aligned addresses (e.g. m_iTeamNum
            // = 0x3e7). All 4-byte reads below are done as 8-byte reads; the low
            // 4 bytes hold the value.
            Batch b2 = new Batch(scatter);
            // local player (pawn) fields used by the unknown-map math
            b2.prep(localPawn + m_vOldOrigin + 0x8, 8);       // localZ
            b2.prep(localPawn + m_angEyeAngles + 0x4, 8);     // local yaw
            b2.prep(localPawn + m_iTeamNum, 8);               // local team
            int[] slotIndex = new int[MAX_SLOTS];
            int validSlots = 0;
            for (int i = 0; i < MAX_SLOTS; i++) {
                long slot = slotCache[i];
                if (slot == 0) continue;
                slotIndex[validSlots++] = i;
                b2.prep(slot + m_hPlayerPawn, 8);
                b2.prep(slot + m_iTeamNum, 8);
                b2.prep(slot + m_iCompTeammateColor, 8);
            }
            b2.run();

            float localZ = f32(b2.get(0), 0);
            float localYaw = f32(b2.get(1), 0);
            int localTeam = u32(b2.get(2), 0);
            long[] pawnHandle = new long[validSlots];
            int[] team = new int[validSlots];
            int[] color = new int[validSlots];
            for (int s = 0; s < validSlots; s++) {
                pawnHandle[s] = u64(b2.get(3 + s * 3), 0);
                team[s] = u32(b2.get(4 + s * 3), 0);
                color[s] = u32(b2.get(5 + s * 3), 0);
            }
            b2.reset();

            // ---- phase 3: pawn list entries (P + 0x10 + 8*bucket) ----
            Batch b3 = new Batch(scatter);
            int[] pawnOf = new int[validSlots]; // validSlots index per pawn
            int pawnCount = 0;
            for (int s = 0; s < validSlots; s++) {
                if (pawnHandle[s] == 0) continue;
                long entryAddr = pawnListP + 0x10 + 8 * ((pawnHandle[s] & 0x7FFF) >> 9);
                b3.prep(entryAddr, 8);
                pawnOf[pawnCount++] = s;
            }
            if (pawnCount == 0) {
                playerInfoList = list;
                return;
            }
            b3.run();
            long[] entryVal = new long[pawnCount];
            long[] pawnSlot = new long[pawnCount];
            for (int p = 0; p < pawnCount; p++) {
                entryVal[p] = u64(b3.get(p), 0);
                pawnSlot[p] = entryVal[p] == 0 ? 0 : entryVal[p] + SLOT_STRIDE * (pawnHandle[pawnOf[p]] & 0x1FF);
            }
            b3.reset();

            // ---- phase 3b: dereference the pawn pointer stored at the slot ----
            Batch b3b = new Batch(scatter);
            int slotOf = 0; // pawn index -> position in b3b
            int[] pawnOf2 = new int[pawnCount];
            for (int p = 0; p < pawnCount; p++) {
                if (pawnSlot[p] == 0) continue;
                b3b.prep(pawnSlot[p], 8);
                pawnOf2[slotOf++] = p;
            }
            long[] pawnPtr = new long[pawnCount];
            if (slotOf > 0) {
                b3b.run();
                for (int q = 0; q < slotOf; q++) {
                    pawnPtr[pawnOf2[q]] = u64(b3b.get(q), 0);
                }
                b3b.reset();
            }

            // ---- phase 4: pawn life state (all) ----
            Batch b4 = new Batch(scatter);
            int aliveCount = 0;
            int[] aliveOf = new int[pawnCount]; // index into phase-4 results for alive pawns
            int[] aliveSlot = new int[pawnCount];
            for (int p = 0; p < pawnCount; p++) {
                if (pawnPtr[p] == 0) continue;
                b4.prep(pawnPtr[p] + m_lifeState, 8);
                aliveSlot[aliveCount] = p;
                aliveOf[aliveCount] = aliveCount;
                aliveCount++;
            }
            if (aliveCount == 0) {
                playerInfoList = list;
                return;
            }
            b4.run();
            int[] lifeState = new int[aliveCount];
            boolean[] alive = new boolean[aliveCount];
            for (int a = 0; a < aliveCount; a++) {
                lifeState[a] = u32(b4.get(a), 0);
                alive[a] = lifeState[a] == 256;
            }
            b4.reset();

            // ---- phase 5: remaining fields for alive pawns ----
            Batch b5 = new Batch(scatter);
            int liveCount = 0;
            for (int a = 0; a < aliveCount; a++) {
                if (!alive[a]) continue;
                b5.prep(pawnPtr[aliveSlot[a]] + m_iHealth, 8);
                b5.prep(pawnPtr[aliveSlot[a]] + m_iPawnArmor, 8);
                b5.prep(pawnPtr[aliveSlot[a]] + m_angEyeAngles, 8);
                b5.prep(pawnPtr[aliveSlot[a]] + m_vOldOrigin, 12);
                liveCount++;
            }
            byte[][] healthB = null, armorB = null, angleB = null, originB = null;
            if (liveCount > 0) {
                b5.run();
                healthB = new byte[liveCount][];
                armorB = new byte[liveCount][];
                angleB = new byte[liveCount][];
                originB = new byte[liveCount][];
                for (int l = 0; l < liveCount; l++) {
                    healthB[l] = b5.get(l * 4);
                    armorB[l] = b5.get(l * 4 + 1);
                    angleB[l] = b5.get(l * 4 + 2);
                    originB[l] = b5.get(l * 4 + 3);
                }
                b5.reset();
            }

            // ---- assemble PlayerInfo ----
            int liveIdx = 0;
            for (int a = 0; a < aliveCount; a++) {
                int s = aliveSlot[a];
                int slot = slotIndex[pawnOf[s]];
                long ctrlAddr = slotCache[slot];
                long pawn = pawnPtr[aliveSlot[a]];

                int health = 0, armor = 0;
                float yaw = 0, pX = 0, pY = 0, pZ = 0;
                if (alive[a] && liveIdx < liveCount) {
                    health = u32(healthB[liveIdx], 0);
                    armor = u32(armorB[liveIdx], 0);
                    yaw = f32(angleB[liveIdx], 4);
                    pY = f32(originB[liveIdx], 0);
                    pX = f32(originB[liveIdx], 4);
                    pZ = f32(originB[liveIdx], 8);
                    liveIdx++;
                }

                float levelDv = Math.abs(pZ - localZ);
                boolean isLocal = localPawn == pawn;
                boolean isEnemy = localTeam != team[pawnOf[s]];
                float x = pX, y = pY, angles;
                if (isKnowMap) {
                    angles = 90 - yaw;
                } else {
                    double rad = Math.toRadians(localYaw - 90);
                    float nx = pX * (float) Math.cos(rad) - pY * (float) Math.sin(rad);
                    float ny = pX * (float) Math.sin(rad) + pY * (float) Math.cos(rad);
                    x = nx;
                    y = ny;
                    angles = 90 - yaw + (localYaw - 90);
                }

                PlayerInfo info = new PlayerInfo(
                        ctrlAddr, pawn, team[pawnOf[s]], health, armor,
                        alive[a], isLocal, isEnemy, x, y, pZ, angles,
                        levelDv < 250, color[pawnOf[s]]);
                info.setName(playerName(ctrlAddr, refreshNames));
                list.add(info);
            }

            playerInfoList = list;
            trackKills();
            updateDamageCache(list);
        } catch (Exception e) {
            System.out.println("[-] Error reading frame: " + e.getMessage());
            playerInfoList = new ArrayList<>();
            mapName = "";
        }
    }

    /**
     * Continuously cache the latest damage records of alive players. On death the
     * victim's list is cleared, so the killer must come from this cache.
     * Stores { firstName, lastName, firstHandle, lastHandle }.
     */
    private void updateDamageCache(List<PlayerInfo> list) {
        try {
            if (m_pDamageServices == 0 || m_DamageList == 0 || m_hPlayerControllerDamager == 0) {
                return;
            }
            for (PlayerInfo p : list) {
                if (!p.isAlive()) continue;
                long c = p.getEntityAddress();
                long dmgSvc = memoryTool.readAddress(c + m_pDamageServices, 8);
                if (dmgSvc == 0) continue;
                byte[] hdr = memoryTool.readBytes(dmgSvc + DMG_LIST_COUNT_OFF, 16);
                if (hdr == null) continue;
                int count = u32(hdr, 0);
                long ptr = u64(hdr, 8);
                if (ptr == 0 || count <= 0) continue;
                String first = readDamagerName(ptr);
                String last = readDamagerName(ptr + (count - 1) * DMG_RECORD_STRIDE);
                String best = !last.isEmpty() ? last : first;
                if (!best.isEmpty()) {
                    lastDamagers.put(c, new String[] { best, String.valueOf(0) });
                }
            }
            if (lastDamagers.size() > 128) {
                lastDamagers.clear();
            }
        } catch (Exception ignored) {
        }
    }

    private String readDamagerName(long rec) {
        try {
            long namePtr = u64(memoryTool.readBytes(rec + m_szPlayerDamagerName, 8), 0);
            if (namePtr == 0) return "";
            return cleanName(memoryTool.readString(namePtr, 32));
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanName(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\p{Cc}\\u001b\\[\\]{}|]", "").trim();
    }

    // ------------------------------------------------------------------
    // Kill feed
    // ------------------------------------------------------------------
    private void trackKills() {
        long now = System.currentTimeMillis();
        for (PlayerInfo p : playerInfoList) {
            long key = p.getEntityAddress();
            Boolean wasAlive = prevAlive.get(key);
            if (wasAlive != null && wasAlive && !p.isAlive()) {
                pendingKills.add(resolveKill(p, now));
            }
            prevAlive.put(key, p.isAlive());
        }
        if (prevAlive.size() > 128) {
            prevAlive.clear();
        }
    }

    /**
     * Best-effort kill resolution: victim (known), killer (from the victim's
     * damage list) and weapon (from the killer's active weapon). Every step is
     * defensive - on any failure the event still carries the victim.
     */
    private KillEvent resolveKill(PlayerInfo victim, long now) {
        KillEvent ev = new KillEvent(readPlayerName(victim.getEntityAddress()), victim.getTeamId(), now);
        try {
            if (m_pDamageServices == 0 || m_DamageList == 0 || m_hPlayerControllerDamager == 0) {
                return ev;
            }

            String killerName = "";
            long killerHandle = 0;

            // 1) live damage list (usually cleared at death, but try first)
            long dmgSvc = memoryTool.readAddress(victim.getEntityAddress() + m_pDamageServices, 8);
            if (dmgSvc != 0) {
                byte[] hdr = memoryTool.readBytes(dmgSvc + DMG_LIST_COUNT_OFF, 16);
                if (hdr != null) {
                    int count = u32(hdr, 0);
                    long ptr = u64(hdr, 8);
                    if (ptr != 0 && count > 0) {
                        long rec = ptr + (count - 1) * DMG_RECORD_STRIDE;
                        killerHandle = u32(memoryTool.readBytes(rec + m_hPlayerControllerDamager, 4), 0);
                        long namePtr = u64(memoryTool.readBytes(rec + m_szPlayerDamagerName, 8), 0);
                        killerName = namePtr == 0 ? "" : cleanName(memoryTool.readString(namePtr, 32));
                    }
                }
            }

            // 2) fall back to the cached latest damager (list is cleared on death)
            if (killerName.isEmpty()) {
                String[] cached = lastDamagers.get(victim.getEntityAddress());
                if (cached != null && !cached[0].isEmpty()) {
                    killerName = cached[0];
                }
            }

            if (killerName.isEmpty()) {
                return ev;
            }

            ev.setKiller(killerName);
            ev.setKillerTeam(-1);
            // find the killer controller by name to attach the killer's team
            long kCtrl = findControllerByName(killerName);
            if (kCtrl != 0) {
                ev.setKillerTeam(memoryTool.readInt(kCtrl + m_iTeamNum, 4));
            }
        } catch (Exception e) {
            System.out.println("[kill] resolve failed: " + e);
        }
        System.out.println("[KILL] " + ev.getKiller() + " -> " + ev.getName());
        return ev;
    }

    /** Find a player controller whose name matches (used to attach killer team). */
    private long findControllerByName(String name) {
        if (name.isEmpty()) return 0;
        for (int i = 0; i < MAX_SLOTS; i++) {
            long ctrl = slotCache[i];
            if (ctrl == 0) continue;
            if (name.equals(readPlayerName(ctrl))) {
                return ctrl;
            }
        }
        return 0;
    }

    private String readPlayerName(long controllerAddress) {
        try {
            String name = memoryTool.readString(controllerAddress + m_iszPlayerName, 32);
            if (name == null || name.isEmpty()) {
                return "";
            }
            return name.replaceAll("[\\p{Cc}\\u001b\\[\\]{}|]", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- player name cache (names barely change; refresh every 30 frames) ----
    private final Map<Long, String> nameCache = new HashMap<>();

    private String playerName(long ctrl, boolean refresh) {
        String n = nameCache.get(ctrl);
        if (n != null && !refresh) {
            return n;
        }
        n = readPlayerName(ctrl);
        if (n.isEmpty()) {
            n = "?";
        }
        nameCache.put(ctrl, n);
        if (nameCache.size() > 128) {
            nameCache.clear();
        }
        return n;
    }

    /** Returns and clears the kill events accumulated since the last call. */
    public List<KillEvent> drainKillFeed() {
        if (pendingKills.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<KillEvent> out = new ArrayList<>(pendingKills);
        pendingKills.clear();
        return out;
    }

    public List<PlayerInfo> getPlayerInfoList() {
        return playerInfoList;
    }

    private boolean refreshGameData() {
        try {
            if (memoryTool == null || gameProcess == null) {
                return false;
            }
            // client.dll base is resolved once per attach and cached - resolving it
            // on every frame (as the original did) intermittently fails at high fps.
            if (clientAddress == 0) {
                clientAddress = memoryTool.getModuleAddress("client.dll");
                if (clientAddress == 0) {
                    logRefreshFail("module client.dll");
                    return false;
                }
            }
            long globalVarsPtr = memoryTool.readAddress(clientAddress + dwGlobalVars, 8);
            if (globalVarsPtr == 0) {
                logRefreshFail("dwGlobalVars");
                return false;
            }
            mapNameAddress = globalVarsPtr;
            pawnListP = memoryTool.readAddress(clientAddress + dwEntityList, 8);
            if (pawnListP == 0) {
                logRefreshFail("dwEntityList(P)");
                return false;
            }
            EntityList = memoryTool.readAddress(pawnListP + 0x10, 8);
            if (EntityList == 0) {
                logRefreshFail("dwEntityList(list)");
                return false;
            }
            return true;
        } catch (Exception e) {
            logRefreshFail(e.toString());
            return false;
        }
    }

    private int refreshFailLogged = 0;

    private void logRefreshFail(String what) {
        if (refreshFailLogged % 25 == 0) {
            System.out.println("[-] refreshGameData failed at: " + what
                    + (gameProcess != null ? " pid=" + gameProcess.getPID() : ""));
        }
        refreshFailLogged++;
    }

    private String readCurrentMapName() {
        String result = attemptReadMapName(mapNameAddress + GLOBAL_VARS_MAPNAME_OFFSET);
        return isValidMapName(result) ? result : "";
    }

    private String attemptReadMapName(long mapNamePtrAddress) {
        try {
            long namePtr = memoryTool.readAddress(mapNamePtrAddress, 8);
            if (namePtr == 0) {
                return "";
            }
            String raw = memoryTool.readString(namePtr, 64);
            if (raw == null || raw.isEmpty()) {
                return "";
            }
            return raw.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isValidMapName(String name) {
        if (name == null || name.isEmpty() || "undefined".equals(name)) {
            return false;
        }
        return knowMap.contains(name);
    }

}
