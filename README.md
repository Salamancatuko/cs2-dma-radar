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

**需要三台设备**：游戏机（插 DMA 卡，跑 CS2）、雷达机（连 DMA 卡，跑 `client/`）、
服务器（跑 `server/`，任何有公网/局域网 IP 的机器，可用 Docker）。也可把服务器
和雷达机放在同一台机器（用 `127.0.0.1`）。

---

## 零、别人 Clone 后：从零到能用（5 步）

> 假设你已有一台跑 CS2 的游戏机（DMA 卡已插）、一台连 DMA 卡的雷达机（Windows）、
> 一台服务器（可选 Docker）。

```bash
git clone git@github.com:Salamancatuko/cs2-dma-radar.git
cd cs2-dma-radar
```

### 第 0 步：准备环境

| 设备 | 需要 | 备注 |
| --- | --- | --- |
| 服务器 | Docker（推荐）或 Node.js ≥ 18 | 无 Docker 也能直接跑 |
| 雷达机 | JDK 17+、DMA 工具包（`vmm.dll`/`leechcore.dll`/`FTD3XX.dll`） | 工具包路径可在 `client/setup.bat` 里改 |
| 浏览器设备 | 无 | 手机/电脑都能看 |

### 第 1 步：启动服务器

```bash
docker compose up -d --build
# 网页:  http://<服务器IP>:27081
# 状态:  http://<服务器IP>:27081/api/status
```

先开浏览器确认网页能打开（此时显示「采集端离线」是正常的）。

### 第 2 步：配置客户端（雷达机）

```bat
cd client
setup.bat                 REM 复制 FTD3XX.dll 到 .\vmm\（注意：vmm/leechcore 用仓库内置标准版）
REM 用记事本编辑 config.json：
REM   "serverHost": 改成服务器的 IP（局域网/公网/域名，同机用 127.0.0.1）
mvnw.cmd package          REM 构建（首次会自动下载 Maven 与依赖，需联网）
run.bat                   REM 以管理员身份运行
```

### 第 3 步：防火墙放行 27081

服务器防火墙（及云服务器安全组）放行 **TCP 27081**，否则客户端/浏览器连不上。

### 第 4 步：验证

- 客户端日志出现 `Connected to radar server: ws://<服务器IP>:27081/push?name=radar-1`
  且 `ws=true`、`fps` 有数值；
- 打开 `http://<服务器IP>:27081/api/status`，`"clientConnected": true`；
- 游戏内进入对局，网页即显示地图与玩家。

> 详细排查见「三、重新配置 IP 完整教程」。

---

## 一、部署服务器（server/，端口 27081）

### 方式 A：Docker（推荐）

```bash
docker compose up -d --build
```

- 网页：`http://<服务器IP>:27081`；状态：`http://<服务器IP>:27081/api/status`
- 停止：`docker compose down`；日志：`docker logs -f cs2-dma-radar-server`

> 多阶段构建：容器内先编译 Vue 前端（`server/web/`）再运行 Node 中继，服务器无需装 Node。

### 方式 B：直接运行（需要 Node.js ≥ 18）

```bash
cd server/web && npm install && npm run build   # 前端产物输出到 server/public
cd ../..
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
- DMA 工具包：`C:\Users\Salam\Desktop\DMA`（含 `vmm.dll`、`leechcore.dll`、`FTD3XX.dll`；
  路径不同时改 `setup.bat` 顶部的 `SRC`）

### 1. 准备 DLL

```bat
cd client
setup.bat
```

`setup.bat` 把工具包里的 `FTD3XX.dll`（FTDI USB3 驱动）复制到 `client\vmm\`。
`vmm.dll` / `leechcore.dll` 使用仓库内置的**标准 MemProcFS 版本**（已匹配 JNA 绑定），
**不要**用工具包自带的版本覆盖（旧版/定制版会在 `VMMDLL_ConfigGet` 崩溃）。

### 2. 配置 config.json

```json
{
  "serverHost": "127.0.0.1",   // 服务器 IP/域名
  "serverPort": 27081,
  "pushIntervalMs": 25,        // 推送间隔（25≈40fps；断流时调大到 33/50）
  "clientName": "radar-1",
  "device": "FPGA",
  "autoUpdateOffsets": true    // 自动从 a2x/cs2-dumper 更新偏移量
}
```

### 3. 构建 & 运行

```bat
mvnw.cmd package        # 产物：client/target/cs2-dma-client.jar
run.bat                 # 以管理员身份运行
```

正常日志示例：

```
[+] VMM initialized successfully!
[+] Connected to radar server: ws://<服务器IP>:27081/push?name=radar-1
[+] Attached to cs2.exe (pid=19600)
[+] push=520 fps=29.2 ws=true game=true map=de_dust2 players=10
```

> 客户端不包含任何网页代码；游戏未运行时会发送心跳帧，网页可区分「在线但无游戏」。

---

## 三、重新配置 IP 完整教程

> 适用：换服务器、换网络、局域网改公网/云服务器、或同机部署。只需改**客户端**的
> `serverHost`；**服务器无需改任何 IP**（监听 0.0.0.0）。

### 第 1 步：确定服务器 IP

- **Linux**：`ip addr` 或 `hostname -I`；
- **Windows**：`ipconfig`（IPv4 地址）；
- **云服务器/公网**：用公网 IP 或域名；
- **同机**：`127.0.0.1`。

### 第 2 步：修改 client/config.json

```json
{ "serverHost": "192.168.1.100", "serverPort": 27081, ... }
```

保存后**重启客户端**，日志出现 `Connected to radar server: ws://<新IP>:27081/...` 即成功。

### 第 3 步：防火墙放行 27081

- **Linux ufw**：`sudo ufw allow 27081/tcp`
- **firewalld**：`sudo firewall-cmd --permanent --add-port=27081/tcp && sudo firewall-cmd --reload`
- **Windows**：防火墙高级设置 → 入站规则 → 新建 → 端口 TCP 27081 → 允许
- **云服务器**：控制台安全组放行 TCP 27081（入方向）

### 第 4 步：验证

1. 浏览器打开 `http://<服务器IP>:27081`（应看到雷达页面）；
2. 打开 `/api/status`，客户端连接后 `"clientConnected": true`；
3. 客户端日志 `ws=true` 且 fps 正常。

### 常见问题

| 现象 | 原因与解决 |
| --- | --- |
| 网页打不开 | 服务器未启动 / 防火墙未放行；`docker ps` 确认容器在运行 |
| 网页能开但「采集端离线」 | `serverHost` 写错、客户端没重启、两台机器网络不通（先互相 `ping`） |
| 日志反复 connect failed | 检查 IP/端口/防火墙 |
| 局域网能看、外网看不了 | 路由器端口转发 27081 到服务器，或用云服务器公网 IP |

---

## 四、网页端功能

- 雷达地图（9 张竞技图，含 nuke/vertigo 双层切换）、旋转、范围调整；
- 玩家标记：头顶昵称（CT 蓝 / T 黄）、血量（低血变红）、本地/队友/敌人图标；
- 击杀记录面板（右侧，可开关，显示「击杀者 → 被击杀者 + 阵营」）；
- 视角跟随：自由视角 / 跟随本人 / 跟随任意玩家（被跟随者带绿色光环）；
- 渲染优化：rAF 帧合并、标记复用、200ms 插值平滑（数据帧率不变更流畅）；
- 手机端适配：左侧设置面板可折叠。

---

## 五、常见问题

- **断流（网页地图/玩家消失）**：多为 DMA 读取量超过卡片上限，把 `pushIntervalMs`
  调大到 33/50 降低帧率；客户端会自动重连恢复；
- **客户端提示 VMM 初始化失败**：确认 `client/vmm\` 下有 `vmm.dll`、`leechcore.dll`、
  `FTD3XX.dll`（运行 `setup.bat`），DMA 卡已插入且以管理员运行；
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