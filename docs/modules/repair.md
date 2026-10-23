# 维修与验收模块

`repair-service` 是独立 Spring Boot 进程，使用 `repair` schema 保存工单，使用 `session` schema 读取共享登录会话。账号权限、房间引用、维修人员候选、资源封禁与邮件通知均通过 core 的真实 HTTP 接口取得；维修服务的数据库角色没有 core schema 访问权。

本模块覆盖 REQ-M3-01 至 REQ-M3-08、REQ-X-03，以及 M3 的权限、通知、审计和可靠任务部分。完整契约为 `contracts/openapi/repairs.yaml`；控制器及业务实现位于 `services/repair-service/src/main/java/com/campus/studyroom/repair/`。

## 操作流程

1. 学生在有准入权限的教室上报标题和故障说明，工单进入 `SUBMITTED`。本人只能取消尚未派发的工单。
2. 调度员在自己的教室管理范围选择启用的维修员。`ASSIGNED` 可以重新派单，第一次派单确定的 48 小时完工期限不延长。
3. 被指派维修员开始维修后返回 `202 BLOCK_PENDING`。资源命令先与工单一起落库；后台通过 HTTP 请求 core 封禁房间。只有收到 `ACTIVE` 确认才进入 `IN_REPAIR`。
4. 维修员提交完工说明，进入 `AWAITING_ACCEPTANCE`，封禁保持。报修人或有范围的物业管理员验收；实际维修人不能验收自己的工作。拒绝需说明原因，回到 `IN_REPAIR`，沿用原封禁和首次完工期限。
5. 验收通过返回 `202 RELEASE_PENDING`。只有收到 core 的 `RELEASED` 确认才 `CLOSED`；其他工单的封禁保持。报修人可提交一次 1–5 整数评分。

派单同时保存 `assignedToName` 姓名快照：取自当前已授权的维修候选查询，不再发起用户资料查询。列表和详情直接读取快照；人员后来改名不修改历史快照，重新派单会更新。扩展迁移 `V20261022160000` 增加可空列，旧工单保持空值并由前端回退显示编号。

本人历史、取消、验收和评价遵守账号启用后的收尾权限例外；撤销创建角色不会隐藏已承诺业务的收尾操作。接口返回 `allowedActions`，前端据此显示按钮，服务端仍独立验证权限、状态、版本和归属。

## 一致性与恢复

`repair_order` 使用 JPA `@Version` 和行锁。每个写请求要求 UUID `Idempotency-Key`；状态动作要求 `expectedVersion`。成功响应与业务在同一事务保存，同键同内容重试返回原响应；同键不同内容为 `409 IDEMPOTENCY_KEY_REUSED`，失败事务不留下成功占位。

开始和解除分别固定 `sourceVersion=1/2`，资源版本与 JPA 版本无关。`outbox_command` 持久化完整 payload、SHA-256、commandId 和原 requestId，重试不改内容或编号。领取任务用短事务与 120 秒租约；连接超时 3 秒、响应超时 10 秒；HTTP 在事务外执行。确认阶段按工单 → outbox 锁序，避免与手动重试互锁。

每 30 秒扫描待发送或租约过期的命令；前七次按指数退避，第八次起每 300 秒持续重试并输出结构化错误事件。`GET /repairs/{id}` 的 `sync` 给出是否待同步、尝试次数、最近尝试时间、下次尝试和通用错误码。物业管理员的 `retry-sync` 只调整原命令，不创建新 commandId，不抢占仍有效的租约。HTTP 200 但来源状态不匹配时记 `SYNC_STATE_CONFLICT`，不推进工单。

期限任务分别持久化上报 +24h、首次派单 +48h、每轮完工 +48h。超时只产生通知，不改变状态或释放资源。任务行锁与唯一 dedup key 保证并发扫描不重复通知。验收后返修仍沿用首次完工期限；每轮验收超时独立去重。

`repair_history`、`repair_acceptance`、`repair_rating`、`audit_event` 保留操作证据。数据库运行角色仅能 INSERT/SELECT 审计表。日志只记录 UUID、事件类型与错误码，不打印密码、会话、服务凭证或完整邮件内容。

## 运行配置

本地使用 `./scripts/dev-up` 统一初始化。服务默认监听 8082；对外通过 Nginx 的 `/api/v1/repairs` 同源代理访问。

| 变量 | 用途 |
| --- | --- |
| `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` | 独立维修角色与 PostgreSQL 连接；兼容 `REPAIR_DB_*` 显式覆盖 |
| `CORE_INTERNAL_URL` | core 私有 HTTP 地址 |
| `INTERNAL_USERNAME`、`INTERNAL_PASSWORD` 或 `INTERNAL_PASSWORD_FILE` | 维修服务 Basic 身份；secret 注入 |
| `COOKIE_SECURE` | 生产必须为 true，本机 HTTP 可为 false |
| `CONTROL_DIR` | 发布控制目录；存在 `jobs-paused` 文件时停止新任务领取 |
| `JOBS_PAUSED` | 启动时暂停任务的开关 |
| `RELEASE_ID`、`GIT_COMMIT` | 版本响应 |

私有 `GET /actuator/jobs` 返回 `{paused,active,activeRequests}`。发布器先创建暂停文件，再等待后台扫描和业务请求都归零；扫描计数覆盖暂停检查与整个扫描，避免领取前的计数空档。Nginx 必须拒绝公网 `/actuator/*`。共享会话 cookie 为 `STUDYROOMSESSION`；`/api/v1/repairs/version` 可查询维修服务实际版本。

## 验证入口

```sh
./mvnw -B -ntp -pl services/repair-service -am test
python3 tests/integration/run_repair_persistence.py
STUDYROOM_TEST_PASSWORD='<本地演示密码>' python3 tests/integration/repair_http.py
python3 tests/integration/run_repair_faults.py
```

密码应从忽略目录或 secret 注入，上例为参数说明，不能把真实值写进 Git。API 测试可通过 `STUDYROOM_CORE_URL`、`STUDYROOM_REPAIR_URL` 或统一 `STUDYROOM_BASE_URL` 指定地址。持久化测试使用独立 `studyroom_repair_integration` 数据库，不向本地演示业务库写测试工单；需要已启动的本地 PostgreSQL 和已构建的迁移程序。

单元测试检查收尾权限、取消状态、验收职责分离、评分边界、版本与重试策略。PostgreSQL 测试检查期限去重、8 线程并发扫描、返修期限和事务失败不泄漏 outbox。真实 HTTP 套件串联两个服务与数据库，检查 CSRF、对象不可枚举、幂等、完整维修/驳回/验收/评价、两个来源封禁叠加、维修取消预约和历史不复活。

故障注入脚本仅用于本机原生开发进程：它暂时重启本 checkout 的 repair，让透明代理在 core 提交首个 BLOCK 后丢弃响应；验证原 commandId、requestId 和完整 payload 不变的重试，再恢复正常进程。HTTP 测试为每次运行建立独立学生和房间，避免与并行浏览器验收共享预约。实际结果见 [维修验收证据](../evidence/repair-validation-2026-10-22.md)，不以仅编译成功代表跨模块验收。

## 负面矩阵与真实邮件补充

2026-10-22新增5组真实公共API负面矩阵，覆盖范围、停用人员、重新派单原期限、自报自修禁止自验、文本/评分类型、封禁期取消及通知归属，全部通过。三期限另在独立repair数据库与可控Clock下通过真实core HTTP验证：截止边界、重复投递、9个角色收件对象、同步中禁止取消和正常业务清理。D-06使用真实SMTP421拒收与Mailpit验证8次失败后的持续重试、原记录人工重试、已读独立及M1三类实际邮件，未使用capture模式。

```sh
python3 tests/integration/run_repair_acceptance.py
python3 tests/integration/run_repair_deadlines.py
python3 tests/integration/run_smtp_acceptance.py
```

运行环境、精确断言与剩余部署边界见[补充证据](../evidence/repair-acceptance.md)。CI显式启用两个隔离数据库suite，并将公共HTTP套件连接到真实Mailpit；任何套件零测试或跳过都会失败。CI执行结果以真实流水线为准，本机通过不等同GitLab已通过。

姓名快照的真实HTTP与既有数据升级证据见[姓名展示回归](../evidence/repair-assignment-name.md)，新测试 `repair_assignment_name_http.py` 由CI公共API套件自动收集。

## 隔离网络故障验收

`tests/integration/run_fault_matrix.py` 只使用独立18180–18183端口、独立数据库与稳定运行包副本，提供资源写入断网、恢复、全core授权中断、双侧审计关联与日志敏感值扫描。复现步骤、实际证据与运行包边界见[故障矩阵](../evidence/repair-fault-matrix.md)。资源命令单独不可达时允许已获授权的动作进入同步中；整个core不可达时权限不能确认，返回503并保持原工单，不能跳过授权。
