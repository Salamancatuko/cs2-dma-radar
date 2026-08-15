# CS2 DMA Radar — Client (采集/推送端)

本目录是雷达的**客户端**：运行在插着 DMA 卡的雷达机上，只做两件事——

1. **读取**：通过 `vmm.dll` / `leechcore.dll`（来自 `C:\Users\Salam\Desktop\DMA`）读取游戏机中 CS2 的内存数据（玩家坐标、血量、队伍、视角等）。
2. **传输**：把每帧数据打包成 JSON，通过 WebSocket 推送到雷达**服务器**（`/push` 端点）。

它**不包含**任何网页/服务端代码 —— 网页端由 `server` + `web` 提供，部署在服务器上。

```
游戏机(CS2 + DMA卡) ──PCIe──► 雷达机[本客户端] ──WebSocket──► 服务器(网页端)
```

## 目录结构

```
client/
├── pom.xml                 # Maven 构建（JNA + fastjson + Java-WebSocket）
├── mvnw / mvnw.cmd         # Maven wrapper（无需预装 Maven）
├── config.json             # 服务器地址 / 推送频率等配置
├── offsets.json            # CS2 偏移量（游戏更新后需更新）
├── setup.bat               # 从 C:\Users\Salam\Desktop\DMA 复制 DLL 到 .\vmm
├── run.bat / run.sh        # 启动脚本
└── src/main/java/
    ├── cs2/dma/main/       # Application（主循环）、RadarPushClient、Config
    ├── cs2/dma/tuil/       # GameDataManager / MemoryTool / GameProcessMonitor
    ├── cs2/dma/entry/      # PlayerInfo
    ├── vmm/  leechcore/    # MemProcFS 的 JNA 绑定（原样保留）
```

## 快速开始（Windows）

1. **准备 DLL**：运行 `setup.bat`，它会从 `C:\Users\Salam\Desktop\DMA` 复制
   `FTD3XX.dll`（FTDI USB3 驱动，DMA 卡通信必需）到本目录的 `vmm\` 子文件夹。
   `vmm.dll` / `leechcore.dll` 使用随项目提供的**标准 MemProcFS/LeechCore 版本**，
   与本客户端的 JNA 绑定匹配。
2. **配置**：编辑 `config.json`，把 `serverHost` 改成雷达服务器的地址
   （本机调试用 `127.0.0.1`；服务器在别处则填服务器 IP / 域名）。
3. **构建**：`mvnw.cmd package`（需要 JDK 17+，首次会自动下载 Maven 与依赖）。
   产物为 `target\cs2-dma-client.jar`。
4. **运行**：`run.bat`（以**管理员**身份运行，和原版一致；DMA 卡需已插入）。

> ⚠️ **关于 vmm.dll / leechcore.dll 的版本兼容性（重要）**
> 实测：部分 DMA 卡随附工具包（如 `C:\Users\Salam\Desktop\DMA` 里的 Neko 工具）
> 自带的 `vmm.dll` / `leechcore.dll` 是旧版/定制版，与本客户端使用的
> MemProcFS JNA 绑定不兼容，会在 `VMMDLL_ConfigGet` 处直接崩溃
> （`Invalid memory access`）。解决方案：保留随附的 `FTD3XX.dll`（驱动，通用），
> 而 `vmm.dll` / `leechcore.dll` 使用标准 MemProcFS 构建
> （本项目 `vmm\` 目录已内置可用的标准版本；如需更新，可到
> [MemProcFS releases](https://github.com/ufrisk/MemProcFS/releases) 下载配套的
> `vmm.dll` + `leechcore.dll`，二者版本必须匹配）。
> 请勿用随附工具包中的 `vmm.dll`/`leechcore.dll` 覆盖 `vmm\` 目录下的标准版本。

> 注意：`config.json`、`offsets.json`、`vmm\` 必须与 jar 在同一目录下运行
> （`run.bat` 已自动 `cd` 到脚本所在目录）。

## config.json 说明

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `serverHost` | `127.0.0.1` | 雷达服务器地址（IP 或域名） |
| `serverPort` | `27081` | 雷达服务器端口 |
| `pushIntervalMs` | `33` | 推送间隔（毫秒）。默认 33 ≈ 30fps：把 DMA 读取量控制在卡的安全范围（~5000 读/秒内），避免压垮链路导致「断流」。如果你的卡实测速率更高，可调小（如 `16` ≈ 60fps） |
| `clientName` | `radar-1` | 本客户端的名字，显示在服务器状态里 |
| `device` | `FPGA` | VMM 设备类型，DMA 卡通常为 `FPGA` |
| `autoUpdateOffsets` | `true` | 启动客户端及每次挂接/重挂接 cs2.exe 时，自动从 a2x/cs2-dumper 拉取最新偏移量并更新 `offsets.json`（网络不可用则沿用旧值，不影响运行） |
| `offsetsUpdateIntervalMs` | `600000` | 两次自动更新检查之间的最小间隔（毫秒） |

## 击杀/阵亡记录

客户端通过相邻两帧的存活状态跳变（alive → dead）检测击杀，然后：
- **被击杀者**：昵称（`m_iszPlayerName`）+ 阵营，可靠；
- **击杀者**：持续缓存每名存活玩家的伤害记录（`DamageServices` → `CDamageRecord`，
  含施害者昵称；该列表在死亡瞬间会被游戏清空，因此必须提前缓存），死亡时从缓存取
  最近一次伤害的施害者。若受害者在死前没有伤害记录（如一击必杀/环境伤害），则只显示
  被击杀者；
- **武器**：尽力通过击杀者当前武器（`m_pWeaponServices` → `m_hActiveWeapon` →
  武器实体 → 物品定义索引）解析；不同游戏构建的物品定义链可能不同，解析失败时武器
  留空，不影响击杀者/被击杀者显示；
- 随下一帧推送到服务器（`killFeed` 字段），网页端右侧以「击杀者 [武器] → 被击杀者」+
  双方阵营颜色显示，并带开关。

说明：伤害记录中的「自杀」（摔落/自己雷）会正确显示为 X → X。

## 性能（DMA 读取优化与稳定性）

采集端使用 **VMM scatter 批量读取**：把每帧上百次单点读取合并为几个原生批量调用，
并移除了原来每帧创建 64 个线程的模型；实体槽地址按帧缓存。

- 读取本身可达到 **55~80+ 帧/秒**，但**默认把推送限流到 ~30fps**
  （`pushIntervalMs: 33`）：实测更高的速率会把 DMA 卡的读取量推到 ~8000+/秒，
  超过其稳定上限（~5000/秒，见 `测试说明.txt`），导致**链路断流**（网页地图消失、
  玩家消失、采集端显示连接异常）。
- **断流自动恢复**：客户端检测到持续读取失败时会自动重建 VMM/leechcore 连接
  （`reinitializeVmm`），链路恢复后自动继续采集，无需人工干预。
- 如果你的卡/线材实测能稳定超过 5000 读/秒，可把 `pushIntervalMs` 调小获得更高帧率。

## 偏移量（offsets.json）

游戏更新后偏移量会失效。更新方法见根目录 README（cs2-dumper 等工具）。

## 说明

- 客户端在游戏未运行时也会每秒向服务器发送一次“心跳帧”，所以网页端能区分
  “采集端离线”和“采集端在线但没进游戏”。
- 服务器重启后客户端会自动重连。
- 客户端仅用于数据读取与传输，不包含任何渲染/网页逻辑。
