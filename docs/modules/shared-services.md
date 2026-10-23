# 身份、幂等、通知与期限任务

这页解释 D/M1/M2/M3 共用机制，适合修改事务、后台任务和跨服务协议时阅读。需求关联：REQ-D-01/D-03/D-06/D-07、REQ-X-01/X-03、REQ-ENG-08。调用示例见[API 指南](../development/api.md)，部署关系见[架构](../architecture/README.md)。

## 公共代码放在哪里

| 位置 | 当前职责 |
| --- | --- |
| [api-contracts](../../modules/api-contracts/src/main/java/com/campus/studyroom/api) | 响应、错误、分页及 M1/M2 命令模型，原始 JSON 快照；不实现业务或跨库仓储 |
| [security-common](../../modules/security-common/src/main/java/com/campus/studyroom/security) | 可序列化稳定会话 Principal、关联日志、业务请求计数；不缓存永久业务授权 |
| core 的 [Idempotency](../../services/core-service/src/main/java/com/campus/studyroom/core/Idempotency.java)、[Events](../../services/core-service/src/main/java/com/campus/studyroom/core/Events.java) | 幂等事务、审计和通知事件 |
| core 的 [Notifications](../../services/core-service/src/main/java/com/campus/studyroom/core/Notifications.java)、[Jobs](../../services/core-service/src/main/java/com/campus/studyroom/core/Jobs.java)、[TaskExecutor](../../services/core-service/src/main/java/com/campus/studyroom/core/TaskExecutor.java) | 邮件租约/投递和持久期限调度 |
| repair 的 [RepairWorker](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairWorker.java) | 自己的 outbox/期限；通过 HTTP 请求 core 通知，不跨库调用公共仓储 |

“公共机制”不代表一个万能公共服务。业务实体留在自己的服务，只有稳定的协议/身份模型抽进共享模块；复杂权限投影、分页和行锁仍由各服务参数化查询实现。

## 幂等与审计

用户写请求按账号、方法、规范路径和 UUID key 串行化，同事务保存业务效果与成功响应，至少保留 24 小时。参与哈希的命令体相同则重放原状态码/data，不同则返回 409；业务异常回滚不会占用成功键。M1/M2 保留完整原始 JSON，其他入口可能先规范化，当前例外见 [API 指南](../development/api.md)。调用方仍须保留原 key/body 重试。内部资源/通知命令使用 commandId 永久保存内容哈希和结果；与公开幂等保留期不同。

审计、通知和期限任务与业务写入同事务。审计区分 USER、SERVICE、JOB；实体不可变，运行角色仅有审计表 INSERT/SELECT。运行日志用于排障，不能取代审计，也不能记录请求正文、Cookie、CSRF、密码、签到码或邮件正文。

## 通知与真实 SMTP

通知按来源、事件、周期、接收人和通道去重。core 每 30 秒领取到期/租约过期记录，短事务领取后在事务外发 SMTP，租约 120 秒。每次领取产生独立令牌，过期发送者不能覆盖新租约结果。失败前七次指数退避，第八次起持续每 300 秒重试并输出通用错误事件；管理员重试原记录，不能复制成新通知。

已读时间与投递状态独立，阅读通知不取消邮件。列表由数据库按本人或管理范围过滤、计数和分页，管理端可按状态、来源、房间筛选；无房间系统通知只对 ALL 管理范围开放。维修通知只接受固定事件和模板字段，收件人由 core 解析；必须收件人缺失时不能消费命令。

本机使用 Mailpit 进行真实 SMTP 验证；`MAIL_MODE=capture` 只适用于需要该模式的隔离测试，不能证明实际邮件投递。正式外部 SMTP 收信按[交付决定](../adr/0002-delivery-and-gitlab.md)延期，不能把 Mailpit 的成功写成外部收信验收。

## 期限、身份与密钥

期限任务持久化到 PostgreSQL，执行时重新取得任务/业务锁并判断当前状态，失败保留次数与下一次尝试。停机恢复先处理 NO_SHOW，过时提醒不补发；重复扫描不能重复违约。M1 时间与资源规则见[core](core.md)，M3 超时只通知且不释放资源，见[repair](repair.md)。

会话 Cookie 为 `STUDYROOMSESSION`，Principal 保存 userId、authVersion 和登录时间；两服务每次请求验证账号和版本。账号/角色变化让旧会话失效。每日签到码 BCrypt 校验、AES-GCM 加密展示；部署时必须安全保留 `checkin_key`，数据库 dump 不包含这个外部密钥。备份与迁移秘密由[运维手册](../operations/runbook.md)管理。

## 暂停、排空与验证

`CONTROL_DIR/jobs-paused` 阻止领取新后台工作。私有 `/actuator/jobs` 报告 paused、active、activeRequests；发布先维护、再暂停、等双服务任务和业务请求排空，超时不能强行迁移。公开代理拒绝 `/actuator` 和 `/internal`。暂停不是删除任务，恢复后仍按当前业务状态处理持久工作。

按[测试指南](../development/testing.md)运行 SharedPersistence 检验通知/审计回滚、租约所有权、8 次失败持续退避、8 线程同 key 去重、失败后重试、审计权限、分页与限流；CoreSmtpAcceptance 用实际 Mailpit 和拒收连接证明投递与恢复。对应结果和环境边界见[期限与 SMTP 证据](../evidence/repair-acceptance.md)、[停机恢复证据](../evidence/core/restart-recovery.md)。历史测试与当前交付结论分离维护，最新身份见[交付台账](../evidence/delivery-ledger-2026-10-23.md)。
