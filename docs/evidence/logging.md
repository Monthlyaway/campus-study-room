# 结构化日志真实进程验证

2026-10-22，REQ-D-07 / REQ-X-03 / REQ-ENG-08。实现说明见[日志模块](../modules/observability.md)。

- `RequestTelemetryTest`：3/3 通过（0.146 秒），验证 JSON 白名单、敏感值不输出、非法 requestId 处理及异常后的线程上下文恢复。
- `tests/integration/logging_http.py`：1/1 通过（1.123 秒），使用真实 core/repair 进程与 PostgreSQL 会话。请求 core 当前用户、repair 本人列表、缺少 CSRF 的 repair 写入（403），响应包、响应头和访问日志 requestId 一致；成功请求内部用户 UUID 正确，错误日志包含安全拒绝码。
- 检查本轮新增日志为 JSON，包含必需字段、非负耗时；未发现测试密码、Cookie、CSRF、传入内部密钥/加密密钥。测试对秘密仅作布尔断言，失败不输出其值。
- 原始本机日志结果：忽略目录 `output/logging-http.txt`；CI 设置真实进程日志路径并执行相同断言。

该结果覆盖上述请求与日志窗口，不代替全部历史日志秘密扫描，也不证明所有业务审计关联均已验收。
