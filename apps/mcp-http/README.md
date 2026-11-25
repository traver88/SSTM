# MCP Streamable HTTP 服务

`apps/mcp-http` 复用 `packages/mcp-core` 的工具定义，使用 Supabase service-role 查询 API key 哈希，并以 Supabase 用户 ID 限流。完整 API key 仅由已认证用户调用 SQL RPC 时返回一次，服务端数据库只持久化 SHA-256 哈希。

需要 Node.js 20+、pnpm、已应用 `supabase/migrations/004_api_key_management.sql` 的 Supabase 项目，并设置环境变量：

```text
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<service-role-key>
PORT=8787
HOST=127.0.0.1
MCP_READ_LIMIT_PER_MINUTE=60
MCP_WRITE_LIMIT_PER_MINUTE=20
MCP_SESSION_IDLE_TIMEOUT_MS=3600000
MCP_MAX_SESSIONS=500
```

运行 `pnpm install`、`pnpm --filter @kaoyan/mcp-http build` 和 `pnpm --filter @kaoyan/mcp-http start`。服务提供 `GET /healthz` 和 `POST/GET/DELETE /mcp`。Node 默认只绑定回环地址，公网部署时应放在已配置 HTTPS 的反向代理之后，并保持服务端口不直接暴露。

限流使用进程内窗口计数，只适用于单实例。多实例部署时需切换到共享限流存储。
