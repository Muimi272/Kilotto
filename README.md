<p align="center">
  <img src="src/main/resources/static/favicon.svg" width="128" height="128" alt="kilotto">
</p>
<h1 align="center">Kilotto</h1>

基于 Java 21、Spring Boot 4、Spring Security、原生 WebSocket 和 SQLite 的免登录抽奖项目。自带静态页面，不需要 Node.js、Redis 或邮件服务。

## 运行

需要 JDK 21 或以上版本。在 PowerShell 中首次启动：

```powershell
$env:KILOTTO_ADMIN_USERNAME = 'admin'
$env:KILOTTO_ADMIN_PASSWORD = Read-Host '首位管理员密码（至少 12 位，不超过 72 字节）' -MaskInput
.\mvnw.cmd spring-boot:run
```

打开 <http://localhost:8080>。首次管理员仅在用户表为空时创建；之后不再使用初始化配置修改账户，也没有默认密码。完成初始化后可从运行环境移除初始化密码。

登录后创建房间，复制邀请链接。游客可输入 8 位房间码，或直接打开 `/room/XXXXXXXX` 加入（兼容原有 `/?room=XXXXXXXX` 链接）。昵称选填；房主与管理员进入房间时自动旁观，不占用抽奖名额。

管理员可以创建房主账户或其他管理员、查看所有房间、结束任意房间、禁用和重新启用其他用户。禁用会中断该用户主持的全部房间，并断开其在其他房间中的连接；已有登录会话会永久撤销，重新启用也不会使旧会话恢复。管理员不能禁用自己。

页面采用朱橙、荧光黄绿与票券排版，适配手机与桌面；支持浅色、深色和跟随系统，记住选择并同步到同源标签页。箭头、关闭、增减、密码显隐等图形均使用内置 SVG。中文使用本地托管的 Noto Sans SC 可变字体，按字形范围加载，许可见 `src/main/resources/static/fonts/noto-sans-sc/OFL.txt`。加入页包含星芒与轨道缓动；品牌开屏分入场 700 毫秒、停留 650 毫秒和退场 450 毫秒，共约 1.8 秒，从页面就绪并可显示后计时，每个标签页首次进入加入页时播放，直接邀请链接跳过；减少动态效果时关闭，隐藏标签页暂停开屏计时与持续动效。加入、登录、房间管理、创建房间、用户管理、创建用户与幸运记录使用独立 URL；记录页与现场之间切换保留实时连接。房间码输入框可直接粘贴完整邀请链接；控制台可搜索和筛选房间；房间内可查找参与者、查看自己的中奖状态和按轮次整理的幸运记录。结束房间、禁用账户会先确认，复制权限受限时会提供可手动复制的链接。抽奖响应未确认时，页面锁定原人数并提供同一请求重试，避免重复抽取；刷新页面后应先核对幸运记录。

## 抽奖规则与存储

- 每轮抽取 1–100 人，只从在线且尚未中奖的游客中等概率选择，由服务端 `SecureRandom` 完成抽样。
- 同一房间内的加入、断线、抽奖与关闭操作互斥，避免并发抽奖重复中奖。房主可抽奖，管理员只能查看和中断其他人的房间。
- 游客身份由服务端生成，绑定浏览器 HTTP 会话。同一房间同一会话只保留一个连接，刷新、重连不会重置中奖状态或昵称；不同浏览器、隐私窗口或清除 Cookie 会被视为不同游客。
- 页面每 5 分钟更新 HTTP 会话；WebSocket 每 25 秒发送心跳，75 秒未收到 Pong 的游客不再进入抽奖池。HTTP 会话空闲超时为 30 分钟。
- 登录、注销和 HTTP 会话过期会断开该浏览器会话已有的 WebSocket；页面会重新同步身份。意外断线最多自动尝试重连 10 次，之后可手动重连。
- **用户持久化到 `kilotto.db`，房间、名单和中奖记录仅存在当前进程内存中，重启会清空。当前版本只支持单实例部署。**
- 每人最多同时主持 10 个房间、保留 50 个房间（含已结束的房间）；全局最多保留 500 个房间；每房间最多累计 1000 位游客、1200 条连接。房间最长开放 24 小时，关闭 1 小时后清除，清理间隔为 25 秒；访问时也会检查到期状态。
- WebSocket 发送队列上限为 512 KiB / 128 条消息，写入超过 5 秒会关闭连接；网络写入在房间状态锁之外进行。

默认使用本地 `kilotto.db` 和 `users` 表；不会自动迁移旧邮箱账户或角色表。数据库、备份、日志、构建产物、IDE 设置、`.env` 及 `application-local.*` / `application-secrets.*` 私有配置已加入 Git 忽略规则。配置示例、源码、测试及字体资源正常纳入版本管理。

## 项目目录

```text
.mvn/wrapper/             Maven Wrapper 配置
docs/                    安全复核与依赖审计记录
src/main/java/           后端代码
src/main/resources/      公共配置与静态页面（static/）
src/test/java/           后端集成与边界测试
src/test/js/             前端回归测试
src/test/resources/      测试配置
pom.xml                  Maven 构建配置
mvnw / mvnw.cmd          Maven Wrapper 启动脚本
```

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `PORT` | `8080` | HTTP/WebSocket 端口 |
| `KILOTTO_DATABASE_URL` | `jdbc:sqlite:kilotto.db?busy_timeout=5000&foreign_keys=on` | SQLite JDBC URL |
| `KILOTTO_COOKIE_SECURE` | `false` | HTTPS 部署设置为 `true` |
| `KILOTTO_ADMIN_USERNAME` | 空 | 首次初始化管理员用户名 |
| `KILOTTO_ADMIN_PASSWORD` | 空 | 首次初始化管理员密码 |

前端与 API 使用同一域名。反向代理需转发 WebSocket Upgrade 请求，并将连接空闲超时设为大于 25 秒。HTTP Cookie 为 HttpOnly、SameSite=Lax；生产环境使用 HTTPS 并启用 Secure Cookie。SQLite 连接池限制为 1，避免本项目的并发写锁竞争。

`kilotto.limits.logins-per-minute`（默认 60）和 `kilotto.limits.upgrades-per-minute`（默认 600）按远端 IP 限制登录与握手请求，使用可补充的令牌桶。`kilotto.limits.connections`（默认 5000）限制总 WebSocket 数，`kilotto.limits.guest-connections`（默认 16）限制每个游客会话的连接数。可通过 Spring 配置或对应环境变量覆盖。共享出口的大型活动应按规模调整；应用默认不相信客户端自行填写的 `X-Forwarded-For`，反向代理需限制流量并正确配置可信代理。JSON 请求体上限为 16 KiB，覆盖已知长度和分块传输。

## HTTP API

请求与响应均为 JSON。失败使用 HTTP 状态码与 `application/problem+json`（安全过滤器返回等价的 `status` / `detail` JSON）。用户响应不包含密码或哈希。

| 方法 | 路径 | 权限 / 用途 |
| --- | --- | --- |
| GET | `/health` | 公开健康检查 |
| GET | `/api/auth/csrf` | 公开，获取 `{headerName, token}` |
| POST | `/api/auth/login` | `{username, password}`，创建登录会话 |
| POST | `/api/auth/logout` | 注销当前会话 |
| GET | `/api/auth/me` | 当前登录用户 |
| GET | `/api/rooms` | 当前用户主持的房间 |
| POST | `/api/rooms` | 登录用户，`{title}` |
| GET | `/api/rooms/{code}` | 房主或管理员查看房间完整状态 |
| POST | `/api/rooms/{code}/draw` | 仅房主，`{count, requestId?}`，可选 UUID 用于幂等重试 |
| POST | `/api/rooms/{code}/close` | 房主或管理员，结束房间 |
| GET | `/api/admin/rooms` | 管理员，全部房间 |
| GET | `/api/admin/users?page=0` | 管理员，用户列表，每页最多 100 人 |
| POST | `/api/admin/users` | 管理员，`{username, password, role: "USER"或"ADMIN"}` |
| PATCH | `/api/admin/users/{id}/enabled` | 管理员，`{enabled: false或true}` |

所有写请求（包括登录与注销）需要先获取 CSRF 令牌，携带返回的 Header，并保留会话 Cookie。登录会轮换会话 ID 并使旧 CSRF 令牌失效，登录成功后重新获取令牌。没有注册、邮箱验证、找回密码入口。

房间列表返回概要（含 `onlineCount`、`participantCount` 和 `winnerCount`），不重复传输完整名单与中奖记录；完整状态通过单个房间查询或 WebSocket 获取。

内置页面的每次抽奖携带 UUID。同一房间重复提交同一 `requestId` 和人数，返回当前状态且不会再次抽奖；改用同一 ID 提交不同人数返回 409。未提供 ID 的外部客户端不享有该重试保证。页面请求在 15 秒后超时，同页面重试未确认的抽奖会复用 ID；同一页面内离开房间再返回时也会保留未确认请求；整页刷新前应先核对实时中奖记录。

## WebSocket 协议

先调用 `/api/auth/csrf` 建立游客会话并保存 Cookie，再连接：

```text
ws://localhost:8080/ws/rooms/XXXXXXXX?nickname=URL编码后的昵称
```

HTTPS 部署使用 `wss://`。默认只接受同源浏览器连接。握手成功后，服务端发送：

```json
{"type":"joined","participantId":"服务端生成的游客ID或空字符串","observer":false}
```

加入、断线、抽奖时广播完整状态：

```json
{"type":"room","room":{"code":"XXXXXXXX","title":"周五抽奖","ownerId":1,"ownerName":"host","status":"OPEN","closeReason":null,"createdAt":"2026-11-03T12:00:00Z","version":1,"participants":[{"id":"guest-id","nickname":"游客","online":true,"won":false}],"winners":[]}}
```

`winners` 的每项为 `{participantId, nickname, round, drawnAt}`。`version` 在同一房间内递增，客户端应忽略旧版本状态。

房间无效、已关闭或请求无权旁观时，发送 `{type: "error", detail: "原因"}` 后以 `1008` 关闭；重复标签页的旧连接以 `4001` 关闭；登录会话改变时以 `4002` 关闭（容器注销认证连接时也可能为 `1008`）；房间正常结束会广播 `CLOSED` 状态后以 `1000` 关闭。客户端不发送业务消息；创建、抽奖和管理操作统一走有认证和 CSRF 校验的 HTTP 接口。

## 检查与打包

```powershell
.\mvnw.cmd verify
java -jar target/kilotto-0.1.0.jar
```

## 发布 Release

将待发布版本的 `pom.xml` 版本号更新并提交后，推送以 `v` 开头的标签即可触发 `.github/workflows/release.yml`：

```powershell
git tag v0.1.0
git push origin v0.1.0
```

工作流使用 Java 21，运行前端测试和 Maven `clean verify`，将包含依赖的可执行 JAR 与 `SHA256SUMS` 校验文件上传到对应的 GitHub Release，并自动生成发布说明。包含 `-` 的标签（如 `v0.2.0-rc.1`）会标记为预发布。

也可以在 GitHub Actions 的 **Release → Run workflow** 中填写已有标签重新发布；构建使用该标签对应的源码，同名附件会被替换。发布使用内置 `GITHUB_TOKEN`，无需额外配置令牌。

## 测试

开屏逻辑的前端回归测试使用 Node.js 内置测试工具（仅运行此项检查需要 Node.js）：

```powershell
node --test src/test/js/opening.test.cjs
```

集成测试使用随机端口和独立的内存 SQLite，通过真实 HTTP 和 WebSocket 验证登录/注销、CSRF、访客加入、重连、同会话多标签页、离线排除、抽奖并发、房间权限、管理员中断、禁用/启用用户及同源限制，不连接或修改本地数据库。
