# VPS 部署

部署使用独立 Supabase、自有 MCP HTTP 网关和 Node.js TLS 反向代理。现有 VPS 的 80、443、2017 端口保持不变。公网 TLS 入口使用 TCP 8443。

## DNS 和安全组

阿里云 DNS 中设置以下 A 记录并允许安全组 TCP 8443 入站。

- `supabase.iizjj.asia` 指向 `39.106.248.156`
- `mcp.iizjj.asia` 指向 `39.106.248.156`
- Alibaba Cloud 安全组允许 TCP 8443 入站。

证书采用 ACME DNS-01 验证。`_acme-challenge.mcp.iizjj.asia` 与 `_acme-challenge.supabase.iizjj.asia` 两条 TXT 已用于签发，证书现由 `kaoyan-edge.service` 使用。证书到期日为 2027-02-23，chain preference 设置为 ISRG Root X1，但 Certbot 实际返回链的中间证书仍经 ISRG Root YE / Root X2。手工 DNS 证书后续续期需重新完成 DNS 验证。

## 服务布局

- Supabase Kong 的 HTTP/HTTPS 与数据库池端口只绑定到 VPS 回环接口。
- Node.js TLS 反向代理对外提供 `https://supabase.iizjj.asia:8443` 和 `https://mcp.iizjj.asia:8443`。反向代理由 `/opt/kaoyan-ai-planner/edge-proxy.mjs` 运行，systemd 单元为 `kaoyan-edge.service`。
- MCP HTTP 容器只加入 Supabase Compose 网络，通过服务角色连接 Supabase；服务本身仅在 VPS 回环地址 `127.0.0.1:8787` 发布。服务角色只保存在 VPS 上的权限受限 `.env`。
- 生成并保存用户 API Key 前，先应用 `supabase/migrations/001` 至 `004`。应用本地全部迁移时使用下面的命令范例。

```sh
cat /opt/kaoyan-ai-planner/migrations/001_init_tables_rls_realtime.sql \
    /opt/kaoyan-ai-planner/migrations/002_rpc_analytics.sql \
    /opt/kaoyan-ai-planner/migrations/003_rpc_planning_tx.sql \
    /opt/kaoyan-ai-planner/migrations/004_api_key_management.sql \
    | docker exec -i supabase-db psql -v ON_ERROR_STOP=1 -U postgres -d postgres
```

Supabase 和 MCP 服务已启动在回环地址并通过内外部 smoke test，Node 代理监听公网 TCP 8443。

`edge-proxy.mjs` 使用 Node 内置 HTTPS 服务代理 API 请求和 WebSocket 升级。TLS 证书更新后需重启 `kaoyan-edge.service`。由于当前采用手工 DNS 验证，证书续期时需再次添加 ACME TXT 记录。

桌面 Node.js HTTPS 客户端能够验证两个子域名。Supabase Auth `/auth/v1/health` 返回预期的 401 `No API key found in request`，MCP `/healthz` 返回 200。Pixel 4 XL Android 13 对 8443 TLS 握手被客户端侧 Connection reset，详见 `sheji/项目设计与开发交接.md`，目前不能将真机云登录描述为已完成。

签发成功后，将 edge 反向代理文件安装到 `/opt/kaoyan-ai-planner/edge-proxy.mjs`，安装 `kaoyan-edge.service` 到 `/etc/systemd/system/`，然后执行 `systemctl daemon-reload && systemctl enable --now kaoyan-edge`。启动应用服务使用 `docker compose -f docker-compose.yml -f docker-compose.edge.yml up -d mcp-http`。

Android 本机配置使用 `supabase.url=https://supabase.iizjj.asia:8443` 和 Supabase `.env` 中生成的 `ANON_KEY`。远程 MCP 客户端连接地址是 `https://mcp.iizjj.asia:8443/mcp`，使用手机设置页生成的一次性 API Key。

端到端 smoke test 会创建临时 Auth 用户，验证登录、MCP 会话、路线图读取、学习记录幂等和 API Key 撤销，再删除该用户。脚本不输出测试密码、访问令牌或 API Key。

```sh
set +x
export SUPABASE_URL=http://127.0.0.1:18000
export MCP_URL=http://127.0.0.1:8787/mcp
export ANON_KEY="$(grep '^ANON_KEY=' /opt/kaoyan-ai-planner/stack/.env | cut -d= -f2-)"
export SUPABASE_SERVICE_ROLE_KEY="$(grep '^SERVICE_ROLE_KEY=' /opt/kaoyan-ai-planner/stack/.env | cut -d= -f2-)"
python3 /opt/kaoyan-ai-planner/smoke-test.py
unset ANON_KEY SUPABASE_SERVICE_ROLE_KEY
```
