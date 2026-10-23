# 请求关联与结构化运行日志

对应技术方案 §12、REQ-D-07、REQ-X-03、REQ-ENG-08。core 与 repair 使用 Spring Boot 的 `StructuredLogFormatter` 扩展统一输出 JSON；业务审计仍保存在各自数据库表，不以运行日志代替审计。

每条运行日志包含 `timestamp`、`level`、`service`、`releaseId`、`requestId`、`actorId`、`event`、`errorCode`、`durationMs`。没有用户或请求的启动/后台记录使用空身份字段；用户身份只输出内部 UUID。请求结束记录额外包含 HTTP `status`。后台同步保留白名单中的 `commandId`、`taskId`、`orderId`，可与数据库审计关联。

两个服务最外层 filter 验证入站 `X-Request-ID` 为 UUID，否则生成新值；响应头与 JSON 成功/错误包共享该编号。repair 的 CSRF 和未登录拒绝也在关联 filter 之后执行，避免响应头和错误包编号不同。请求结束记录耗时和状态，401/403 产生最小安全拒绝日志，5xx 产生 ERROR；`finally` 恢复线程原 MDC，防止线程池复用时串身份。

格式化器只输出允许的结构化元数据，不输出请求体、query string、Cookie、CSRF、密码、签到码、收信内容、自由消息参数或异常正文/堆栈。异常保留类型用于定位；数据库内容和具体对象应通过受权审计查询，不能把包含业务字段的异常直接写日志。Spring/Hibernate 启动日志也使用相同格式，未知事件归为 `runtime`。健康轮询不逐次产生日常访问记录。

验证入口：

```sh
./mvnw -pl modules/security-common -am test
python3 tests/integration/logging_http.py
```

真实 HTTP 验证需要 `STUDYROOM_TEST_PASSWORD`、`STUDYROOM_CORE_LOG`、`STUDYROOM_REPAIR_LOG`，并针对正在运行的隔离环境。CI 自动设置这三个值，检查 core/repair 成功和 repair CSRF 拒绝响应编号、日志字段、已认证内部 UUID 与秘密未进入新日志。无实际日志文件时失败，不跳过。

单元测试已通过 3 项：JSON 字段与秘密排除、非法关联 ID/异常路径上下文恢复、原后台日志元数据兼容。真实进程 HTTP 日志验收结果记录到 `docs/evidence/`。

实现依据：[Spring Boot structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)、[StructuredLogFormatter 扩展接口](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/logging/structured/StructuredLogFormatter.html)。
