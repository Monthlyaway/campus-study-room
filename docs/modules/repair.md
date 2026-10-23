# 维修与验收

这页用于修改 M3 状态、派单、验收、期限和跨服务同步。首次开发先读[后端指南](../development/backend.md)；业务人员操作见[用户手册](../user-guide/README.md)。覆盖 REQ-M3-01 至 M3-08、REQ-X-03/X-04，以及相关权限、通知和审计。

## 服务边界与代码

repair 是独立 Spring Boot 进程，使用 `repair` 保存业务、`session` 读取共享会话；运行账号没有 core schema 访问权。当前授权、房间引用、维修员候选、封禁/释放和通知都由 core 的真实 HTTP 接口提供。

| 实现 | 职责 |
| --- | --- |
| [RepairController](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairController.java) / [RepairDtos](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairDtos.java) | 公开入口与输入/响应；契约见 [repairs.yaml](../../contracts/openapi/repairs.yaml) |
| [RepairService](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairService.java) / [RepairPolicy](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairPolicy.java) | 状态转换、版本、幂等与业务事务 |
| [RepairAccess](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairAccess.java) / [CoreClient](../../services/repair-service/src/main/java/com/campus/studyroom/repair/CoreClient.java) | 动态授权和内部 HTTP |
| [RepairWorker](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairWorker.java) / [OutboxCommand](../../services/repair-service/src/main/java/com/campus/studyroom/repair/OutboxCommand.java) | 持久命令、租约、重试、确认与期限扫描 |
| [RepairOrder](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairOrder.java) / [迁移](../../db/migration/repair) | JPA `@Version`、工单与历史数据 |

## 状态与职责分离

| 动作与操作者 | 成功后的状态 | 必须保留的规则 |
| --- | --- | --- |
| 有准入权限的用户报修 | SUBMITTED | 本人仅能取消未派发工单 |
| 范围内调度员派给启用维修员 | ASSIGNED | 可以重新派单，第一次派单的 48 小时完工期限不延长 |
| 被指派维修员开始维修 | BLOCK_PENDING，HTTP 202 | 持久化 BLOCK 后异步请求 core；确认 ACTIVE 才进入 IN_REPAIR |
| 维修员提交完工说明 | AWAITING_ACCEPTANCE | 封禁继续保持 |
| 报修人或范围内物业管理员拒绝验收 | IN_REPAIR | 必须写原因，沿用原封禁和首次完工期限 |
| 独立验收人通过 | RELEASE_PENDING，HTTP 202 | 实际维修人不能验收自己的工作；确认 RELEASED 才 CLOSED |
| 报修人对已关闭工单评价 | CLOSED | 一次 1–5 整数评分 |

账号启用后，本人历史、取消、验收、评价保留相应收尾例外；撤销创建角色不应隐藏已承诺业务。`allowedActions` 帮助 UI 展示，服务端仍校验身份、范围、状态、版本、归属和职责分离。

派单把候选查询授权结果中的姓名保存为 `assignedToName` 快照，列表/详情不再逐个查询用户。之后改名不改变历史，重新派单更新快照。[扩展迁移](../../db/migration/repair/V20261022160000__assigned_worker_name_snapshot.sql)使用可空列，旧工单由界面回退显示编号，保持旧版兼容。

## 异步资源同步

工单与 outbox 同事务保存，之后由 worker 在数据库事务外调用 core。每个公开写请求要求 UUID 幂等键，状态命令要求整数 `expectedVersion`；同 key/内容重放原成功结果，不同内容 409，失败不留下成功占位。

BLOCK/RELEASE 固定 `sourceVersion=1/2`，与工单 JPA 版本不同。outbox 保存完整 payload、SHA-256、commandId 和原 requestId，重试必须全部保持。领取用短事务和 120 秒租约；HTTP 连接超时 3 秒、请求超时 10 秒；确认阶段按工单 → outbox 锁序。每 30 秒扫描待发或过期租约，前七次指数退避，第八次起持续每 300 秒重试。HTTP 200 但来源状态不符，记录 `SYNC_STATE_CONFLICT`，不推进工单。

详情 `sync` 给出待同步状态、尝试次数、最近/下次尝试和通用错误码。物业管理员的 `retry-sync` 只调整原命令，不创建新身份，不抢占仍有效的租约。释放只针对本工单来源；不能直接改 core 表、换来源补单或把 pending 强制改成已完成。

整个 core 不可用时，repair 无法确认授权，返回 503 并保持原工单。只有已经确认授权、后续资源命令暂不可达，才可能进入同步中。这个区别是安全与最终一致性的边界。

## 期限、审计和运行

持久期限分别为上报 +24 小时、首次派单 +48 小时、每轮完工 +48 小时。超时仅通知，不自动改变状态或释放资源。任务锁与 dedup key 避免重复；返修沿用首次完工期限，每轮验收超时独立去重。`repair_history`、`repair_acceptance`、`repair_rating`、`audit_event` 保留操作证据，运行角色对审计仅 INSERT/SELECT。

原生默认 8082，公开代理同源转发 `/api/v1/repairs`；会话 Cookie 为 `STUDYROOMSESSION`。本地初始化使用[统一启动](../development/local-setup.md)。配置来自 [application.yml](../../services/repair-service/src/main/resources/application.yml)：

| 配置 | 含义 |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 维修运行角色连接；可由 `REPAIR_DB_URL` / `REPAIR_DB_USER` / `REPAIR_DB_PASSWORD` 覆盖 |
| `CORE_INTERNAL_URL` | 私有 core HTTP 地址 |
| `INTERNAL_USERNAME` 与 `INTERNAL_PASSWORD` 或 `INTERNAL_PASSWORD_FILE` | 服务 Basic 身份，值只从 secret 注入 |
| `COOKIE_SECURE` | 生产 true；本机 HTTP 可 false |
| `CONTROL_DIR` / `JOBS_PAUSED` | 发布暂停控制；目录内 `jobs-paused` 停止领取新任务 |
| `RELEASE_ID` / `GIT_COMMIT` | `/api/v1/repairs/version` 的运行身份 |

私有 `/actuator/jobs` 暴露 paused、active、activeRequests，发布等待后台和请求排空；不能公开代理该接口。排空/恢复由[运维手册](../operations/runbook.md)控制。

## 验证与故障定位

按[测试矩阵](../development/testing.md)准备环境和选择入口：RepairPolicy 单元检查状态与职责分离；RepairPersistence 用真实 PG 检查期限、8 线程扫描和事务失败不泄漏 outbox；repair_http 串联双服务检验完整生命周期、多来源叠加、隐私与历史不复活；RepairDeadlineHttp 核对真实通知收件范围；故障测试检验提交后响应丢失仍保留同一命令。

同步长时间不结束，先取详情的 `sync`、响应 requestId 和运行版本，按[日志排障](observability.md)检查 core 可达性与相关 commandId。不得用“重新建一张工单”掩盖原命令状态。独立故障测试和会重启本机 repair 的脚本有不同副作用，执行前必须看测试矩阵。

历史覆盖见[维修验收](../evidence/repair-validation-2026-10-22.md)、[期限与 SMTP](../evidence/repair-acceptance.md)、[姓名升级](../evidence/repair-assignment-name.md)、[网络故障](../evidence/repair-fault-matrix.md)。当前交付身份统一看[交付台账](../evidence/delivery-ledger-2026-10-23.md)，本页不把旧测试数当作当前执行结果。
