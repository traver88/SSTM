# Android 客户端

## Supabase 配置

在 `apps/android/local.properties` 添加项目 URL 和 anon key。该文件已被 Git 忽略。VPS TLS 验证完成后的地址为 `https://supabase.iizjj.asia:8443`。未提供配置或无法完成联网同步时，应用会停留在在线门禁页。

```properties
sdk.dir=C\\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
supabase.url=https://<project-ref>.supabase.co
supabase.anonKey=<supabase-anon-key>
mcp.url=https://mcp.iizjj.asia:8443/mcp
```

只把 anon key 放入客户端构建配置。Supabase Auth 的 RLS 策略负责限制用户数据访问，service-role key 不能放在 Android 工程。

## 网络传输层

客户端全部 Supabase HTTP 请求通过嵌入式 Cronet（`org.chromium.net:cronet-embedded:500.1.0`，Chromium 500.x，见 `data/CronetHttpClient.kt`）发送，不使用平台 Conscrypt 的 `HttpURLConnection`。原因：2026-11 验证发现部分公网路径会对非当前主流 TLS ClientHello 指纹（Conscrypt、旧版 Cronet、Windows SChannel）注入 TCP RST，而与 Chrome 同代的 Cronet 可正常握手。VPS 证书链必须是 RSA/ISRG Root X1（Android 13 系统根仓和 Chromium 内置信任库均原生信任 X1；ECDSA 的 ISRG Root X2 链会被嵌入式 Cronet 拒绝）。超时默认 20 秒，超时异常信息中会附带 Cronet 请求阶段探针结果便于排查。

## 首次运行

1. VPS 自托管配置启用邮箱密码登录，首版使用自动邮箱确认以适配无外部 SMTP 的安装。
2. 在电脑端通过 MCP 建立档案、科目、全程阶段和月计划。
3. 在此目录执行 `./gradlew :app:assembleDebug`，Windows 使用 `gradlew.bat :app:assembleDebug`。
4. 启动应用后在在线门禁页或设置中注册并登录 Supabase Auth 邮箱账号。首次同步会把档案日期、科目、阶段、月计划、前后约四个月的任务、近一年学习记录和最近 20 条 AI 变更下载至本机。

应用需要有效互联网连接、Supabase 登录和一次成功云端同步后才能进入计划页。网络断开或同步失败时会显示联网门禁页面，提供重试与登录入口。登录用户的学习记录用客户端 UUID 幂等写入 Supabase，新任务通过 `rpc_mobile_add_plan_task_tx` 校验休息日和容量后写入，已存在任务的状态更新会按云端版本递增 `version`。应用恢复前台或网络重新可用时触发同步。阶段里程碑勾选用带当前版本条件的更新写回，AI 变更历史可在设置页查看并撤回最新一项。

设置页的远程 MCP Key 通过 `rpc_create_user_api_key` 创建，明文只在生成成功后的界面内存中短暂展示，不保存到 Room 或 SharedPreferences。可一次性复制含 `Authorization` Bearer header 的 MCP 客户端 JSON 配置，或只复制原始 Key。复制或关闭界面时清除内存中的明文。本机仅保存吊销所需的 Key ID。新生成的 Key 会自动吊销该账号已有的活动 Key。MCP 地址通过 `mcp.url` 配置。VPS TLS 验证完成后的入口为 `https://mcp.iizjj.asia:8443/mcp`，见 `deploy/vps/README.md`。

当前客户端使用 Supabase REST API 在应用恢复前台或网络恢复时拉取和推送数据，尚未接入 Supabase Realtime 和离线冲突选择界面。阶段里程碑与任务状态写回带版本条件，冲突时保留本地待同步状态并报告冲突。本地通知包含阶段预热/开始、月计划续期、日计划开始、活动计时科目的超时提示和计时结束，支持开机/时区变化后重建，精确闹钟无权限时降级调度。设置页提供通知、精准闹钟和电池优化状态检查及系统设置入口。远程 Key 管理 UI 已就绪，TLS DNS 验证完成后可连接远程 MCP HTTP 网关。
