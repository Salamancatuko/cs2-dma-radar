# CS2 DMA Radar — 客户端 / 网页端分离版

基于 [rabume/cs2-dma-radar](https://github.com/rabume/cs2-dma-radar) 的逻辑重构：
把「读内存」与「看雷达」彻底分离。本仓库包含两个独立文件夹：

- **`client/`**：采集客户端（Java/Maven）——只做 DMA 数据读取与传输，**在雷达机（副机）上运行**；
- **`server/`**：服务器（Node.js 中继 + Vue 网页端）——接收客户端推送并广播给浏览器，
  **在服务器上运行（支持 Docker）**。

> ⚠️ 仅用于学习研究，请勿在游戏中使用。

```
┌──────────────────┐   PCIe   ┌───────────────────────┐  WebSocket  ┌──────────────────────┐
│ 游戏机 (CS2)      │◄────────►│ 雷达机【client/】      │──/push 帧──►│ 服务器【server/】      │
│ (安装 DMA 卡)     │          │ vmm/leechcore 读内存   │             │ 中继 + 网页（Docker）  │
└──────────────────┘          │ 仅读取 + 传输          │             │ 端口 27081            │
                              └───────────────────────┘             └──────────┬───────────┘
                                                                              │ /ws 广播
                                                                     ┌────────┴────────┐
                                                                     │  浏览器网页端      │
                                                                     └─────────────────┘
```

---

## 一、部署服务器（server/，端口 27081）

### 方式 A：Docker（推荐）

在仓库根目录（含 `server/` 与 `docker-compose.yml`）：

```bash
docker compose up -d --build
```

- 网页：`http://<服务器IP>:27081`
- 状态接口：`http://<服务器IP>:27081/api/status`
- 停止：`docker compose down`；查看日志：`docker logs -f cs2-dma-radar-server`

> 镜像为多阶段构建：先在容器内编译 Vue 前端（`server/web/`），再运行 Node 中继；
> 无需在服务器上安装 Node。

### 方式 B：直接运行（需要 Node.js ≥ 18）

```bash
cd server/web && npm install && npm run build   # 前端产物输出到 server/public
cd ../..                                        # 回到 server/
cd server && npm install && npm start           # 默认监听 27081
```

### 服务器环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | `27081` | HTTP/WebSocket 端口 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `BROADCAST_INTERVAL_MS` | `16` | 浏览器广播间隔（16≈60fps，33≈30fps） |

---

## 二、运行采集客户端（client/，雷达机 Windows）

### 0. 环境要求

- JDK 17+（[Temurin](https://adoptium.net/temurin/releases/)）
- DMA 卡已插入游戏机，雷达机通过 USB3.0 与卡连接
- DMA 工具包：`C:\Users\Salam\Desktop\DMA`（含 `vmm.dll`、`leechcore.dll`、`FTD3XX.dll`）

### 1. 准备 DLL

```bat
cd client
setup.bat
```

脚本会把 `C:\Users\Salam\Desktop\DMA\FTD3XX.dll`（FTDI USB3 驱动）复制到 `client\vmm\`。
`vmm.dll` / `leechcore.dll` 使用仓库内置的**标准 MemProcFS 版本**（已匹配 JNA 绑定），
**不要**用 DMA 工具包自带的版本覆盖（旧版/定制版会在 `VMMDLL_ConfigGet` 崩溃）。

### 2. 配置 config.json

```json
{
  "serverHost": "192.168.10.150",  // 改成你的服务器 IP/域名
  "serverPort": 27081,
  "pushIntervalMs": 25,            // 推送间隔（25≈40fps；断流时调大到 33/50）
  "clientName": "radar-1",
  "device": "FPGA",
  "autoUpdateOffsets": true        // 自动从 a2x/cs2-dumper 更新偏移量
}
```

### 3. 构建

```bat
mvnw.cmd package        # 产物：client/target/cs2-dma-client.jar
```

（也可 `mvn package`。构建需要联网下载依赖；`mvnw` 会自动下载 Maven。）

### 4. 运行

```bat
run.bat                 # 以管理员身份运行
```

正常日志示例：

```
[+] VMM initialized successfully!
[+] Connected to radar server: ws://192.168.10.150:27081/push?name=radar-1
[+] Attached to cs2.exe (pid=19600)
[+] push=520 fps=29.2 ws=true game=true map=de_dust2 players=10
```

> 客户端不包含任何网页代码，只读取内存并推送；游戏未运行时会发送心跳帧，
> 网页可区分「采集端在线但无游戏」。

---

## 三、网页端功能

- 雷达地图（9 张竞技图，含 nuke/vertigo 双层切换）、旋转、范围调整；
- 玩家标记：头顶昵称（CT 蓝 / T 黄）、血量（低血变红）、本地/队友/敌人图标；
- 击杀记录面板（右侧，可开关，显示「击杀者 → 被击杀者 + 阵营」）；
- 视角跟随：自由视角 / 跟随本人 / 跟随任意玩家（被跟随者带绿色光环）；
- 渲染优化：rAF 帧合并、标记复用、200ms 插值平滑（数据帧率不变更流畅）；
- 手机端适配：左侧设置面板可折叠。

---

## 四、常见问题

- **网页显示「采集端离线」**：检查 `config.json` 的 `serverHost`/`serverPort`，
  服务器防火墙放行 27081；
- **客户端提示 VMM 初始化失败**：确认 `client/vmm\` 下有 `vmm.dll`、`leechcore.dll`、
  `FTD3XX.dll`（运行 `setup.bat`），DMA 卡已插入且以管理员运行；
- **断流（网页地图/玩家消失）**：多为 DMA 读取量超过卡片上限，把 `pushIntervalMs`
  调大到 33/50 降低帧率；客户端会自动重连恢复；
- **偏移量失效**：`autoUpdateOffsets` 会自动更新 `client/offsets.json`，
  也可手动从 [cs2-dumper](https://github.com/a2x/cs2-dumper) 获取；
- **AMD CPU 需要 memmap**：把 `memmap.txt` 放到 `client/` 目录（见原仓库说明）；
- **没有硬件预览网页**：`cd server && npm install && node test/simulate-client.js`
  会模拟推送移动玩家。

---

## 许可

本仓库为原项目 [rabume/cs2-dma-radar](https://github.com/rabume/cs2-dma-radar)
的架构重构。包含第三方开源组件：MemProcFS/LeechCore（AGPL/GPL）、CS2 React HUD
与 Boltobserv 的地图素材。仅供学习研究。
