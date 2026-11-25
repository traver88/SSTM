# 研途智排

考研阶段规划和月度执行计划工具。仓库包含 Supabase 数据库迁移、Node.js MCP 服务和 Android 客户端。

## 当前实现

- `packages/mcp-core` 提供 11 个 MCP 工具，供不同传输层复用。
- `apps/mcp-stdio` 提供本机 MCP stdio 入口。
- `apps/mcp-http` 提供带 Bearer API Key 的 Streamable HTTP MCP 服务。
- `deploy/vps` 保存 VPS 自托管部署及数据库/认证/MCP 冒烟测试配置。
- `supabase/migrations` 保存数据库 schema、RLS 和事务函数。
- `apps/android` 是 Android 客户端工程。

## MCP stdio 配置

需要 Node.js 20+、pnpm 以及一个已配置 Auth 用户的 Supabase 项目。

1. 复制 `.env.example` 为 `.env`，填入 Supabase 项目 URL、service-role key 和目标 Auth 用户 UUID。
2. 执行 `pnpm install` 和 `pnpm build`。
3. 在 MCP 客户端中配置 `apps/mcp-stdio/dist/index.js`，并将 `.env` 中的值注入该进程环境。

service-role key 仅用于本地 MCP 进程，不能放入 Android 客户端、源码管理或 MCP 对话内容。

## 远程 MCP HTTP 配置

先应用 `supabase/migrations/004_api_key_management.sql`。已登录的 Android 用户可调用 `rpc_create_user_api_key` 生成个人 key，该调用仅返回一次完整 key，后续只在数据库中保存 SHA-256 哈希。使用 `rpc_revoke_user_api_key(p_key_id)` 吊销 key。MCP HTTP 服务由 `apps/mcp-http` 提供，读取 `.env` 中的 Supabase URL/service-role key、端口、绑定地址及读写限流配置。生产部署应由 HTTPS 反向代理终止 TLS，应用默认绑定 `127.0.0.1`。完整部署说明在 [apps/mcp-http/README.md](apps/mcp-http/README.md)。

## 校验

在仓库根目录运行以下命令检查 TypeScript MCP 工程。

```powershell
pnpm install
pnpm typecheck
pnpm test
pnpm build
```

## 数据库

使用 Supabase CLI 将 `supabase/migrations` 应用到项目。客户端数据访问必须使用 Supabase Auth 用户会话；MCP 本地模式由 `TARGET_USER_ID` 显式绑定单一用户。

## Android

Android 工程位于 `apps/android`。客户端包含 Compose 双 Tab、Room 本地数据、时间戳计时、Supabase Auth 邮箱密码登录、REST 前台同步与最近 AI 变更撤回入口。需安装 Android SDK 36 和 Android Studio，之后可使用工程内 Gradle Wrapper 执行构建。远端 Supabase URL 与 anon key 通过 `apps/android/local.properties` 配置，不提交密钥。

## 当前部署与验证状态

- stdio 与 HTTP MCP 已通过类型检查和构建，核心契约及 HTTP 鉴权限流测试通过。
- Android `:app:assembleDebug` 和 `:app:testDebugUnitTest` 通过，APK 已安装至 Pixel 4 XL Android 13，无启动崩溃。当前没有定义 Android 单元测试（NO-SOURCE）。
- 4 份 SQL 迁移已应用到 VPS 自托管 Supabase，并通过了 Auth 注册/登录、MCP 会话初始化、路线图读取和幂等学习记录的内部端到端测试。
- Android APK 已在 Pixel 4 XL Android 13 上安装启动，在线门禁和远程 MCP Key 配置页已纳入。
- Supabase 和 MCP 现运行于 VPS 回环网络，并经 HTTPS 代理对外提供 8443。桌面 HTTPS 健康检查和端到端 Auth/MCP smoke test 通过。
- 2026-11-25：Pixel 4 XL 真机注册、登录和完整云同步已验证通过。原 Conscrypt 登录 Connection reset 已定位为公网路径对特定 TLS ClientHello 的选择性 RST，Android 网络层已切换到嵌入式 Cronet（Chromium 500.x，与 Chrome 同代 TLS 指纹）；同时 VPS 证书已重签为 RSA/ISRG Root X1 链（原 ECDSA/X2 链不被嵌入式 Cronet 信任，且 Android 13 系统根仓无 ISRG Root X2）。
- Android Realtime 与离线冲突选择界面尚未实现。阶段、月度、任务和单科计时提醒已接入本地通知调度。
