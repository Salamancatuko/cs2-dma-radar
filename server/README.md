# CS2 DMA Radar — Server (服务器/中继端)

服务器负责三件事：

1. **接收**：雷达客户端通过 WebSocket 连接到 `/push` 并持续推送数据帧。
2. **转发**：把最新一帧以固定频率（默认 ~30 FPS，`BROADCAST_INTERVAL_MS`）广播给所有浏览器观看者（`/ws`），并对慢客户端做背压丢弃，保证网页流畅。
3. **托管网页**：把构建好的网页端（`web/` 构建产物，位于 `server/public/`）以静态文件方式提供。

```
雷达客户端 ──ws:/push──► [本服务器] ──ws:/ws（≤30fps）──► 浏览器网页端
                           └─ 静态托管 GET /（Vue 构建产物）
```

## 本地运行

```bash
# 1. 构建网页端（产物输出到 server/public/）
cd web && npm install && npm run build

# 2. 运行服务器
cd ../server && npm install && npm start
```

打开 http://127.0.0.1:27081 即可看到雷达网页；`http://127.0.0.1:27081/api/status` 可查看连接状态。

## Docker 部署（推荐）

在项目根目录（包含 `web/`、`server/`、`docker-compose.yml`）：

```bash
docker compose up -d --build
```

或者单独构建镜像：

```bash
docker build -f server/Dockerfile -t cs2-dma-radar-server .
docker run -d --name radar-server -p 27081:27081 --restart unless-stopped cs2-dma-radar-server
```

镜像为多阶段构建：第一阶段编译 Vue 前端，第二阶段只运行 Node 中继，
最终镜像只包含产物，不包含源码与依赖缓存。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `27081` | HTTP/WebSocket 端口 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `BROADCAST_INTERVAL_MS` | `33` | 向浏览器广播的间隔（33ms ≈ 30fps，16ms ≈ 60fps） |
| `STATUS_INTERVAL_MS` | `2000` | 状态帧（采集端在线/离线）的推送间隔 |
| `PUBLIC_DIR` | `./public` | 静态网页目录 |

## 接口一览

| 端点 | 用途 |
| --- | --- |
| `GET /` | 网页端（Vue 构建产物） |
| `GET /api/status` | 服务器/客户端/观看者状态（JSON） |
| `WS /push` | 雷达客户端推送数据帧（`?name=客户端名`） |
| `WS /ws` | 浏览器订阅雷达数据帧与状态帧 |

## 数据流协议

- 客户端 → 服务器（`/push`）：一帧一个 JSON 对象
  `{ "map": "de_mirage", "players": [ {x,y,z,angles,health,alive,enemy,localPlayer,sameLevel,compTeammateColor,entityPawnAddress,...} ], "t": <ms>, "dt": <ms>, "game": true }`
- 服务器 → 浏览器（`/ws`）：
  - 数据帧：上面的对象原样 + `seq` / `receivedAt` / `client` 字段；
  - 状态帧：`{ "type": "status", "clientConnected": true, "viewers": 2, ... }`

帧里坐标单位是游戏单位（1 单位 = 1/10 米），网页端除以 10 后按 Leaflet CRS.Simple 渲染。
