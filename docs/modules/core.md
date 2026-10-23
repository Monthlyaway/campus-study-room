# Core：预约、审批与资源裁决

这页用于修改 D/M1/M2 实现和检查并发边界；首次开发先读[后端指南](../development/backend.md)，联调见[API 指南](../development/api.md)。core 同时提供身份和管理能力，相关规则分见[账号与授权](administration.md)、[公共机制](shared-services.md)。需求关联：REQ-D-01 至 D-07、REQ-M1、REQ-M2、REQ-X-01 至 X-04。

## 职责与代码入口

core 是唯一资源裁决方，只使用 `core` 和 `session` schema。repair 通过受服务身份保护的 `/internal/v1` 请求授权、房间引用、资源和通知，不能直接改 core 业务表。服务关闭自动迁移，仅以 Hibernate `validate` 校验结构；[迁移程序](../../tools/migration-runner)先应用 [core 迁移](../../db/migration/core)。

| 实现 | 阅读重点 |
| --- | --- |
| [CoreController](../../services/core-service/src/main/java/com/campus/studyroom/core/CoreController.java) / [命令 DTO](../../modules/api-contracts/src/main/java/com/campus/studyroom/api/command) | 公开入口、幂等包装、输入解析与响应 |
| [AuthController](../../services/core-service/src/main/java/com/campus/studyroom/core/AuthController.java) / [SecurityConfiguration](../../services/core-service/src/main/java/com/campus/studyroom/core/SecurityConfiguration.java) | 会话、CSRF、登录/退出、私有入口 |
| [BookingService](../../services/core-service/src/main/java/com/campus/studyroom/core/BookingService.java) | 预约、签到、取消、违约与期限 |
| [ApplicationService](../../services/core-service/src/main/java/com/campus/studyroom/core/ApplicationService.java) | 两级审批、职责分离和最终批准事务 |
| [ResourceService](../../services/core-service/src/main/java/com/campus/studyroom/core/ResourceService.java) | 用户 guard、教室锁、占用唯一写入、维修来源墓碑 |
| [QueryService](../../services/core-service/src/main/java/com/campus/studyroom/core/QueryService.java) / [AvailabilityReadRepository](../../services/core-service/src/main/java/com/campus/studyroom/core/AvailabilityReadRepository.java) | DTO 投影、完整时段可用性、过滤/计数/分页 |
| [persistence](../../services/core-service/src/main/java/com/campus/studyroom/core/persistence) / [RoomRepository](../../services/core-service/src/main/java/com/campus/studyroom/core/RoomRepository.java) | 聚合实体、`@Version`、实际 PostgreSQL 行锁 |

复杂读取使用参数化查询和投影，不把 Entity 当响应。可用性在数据库中处理准入、开放时段、占用排除和分页；不是按座位逐次查询。房间编号先通过标量投影取得，获得教室锁后再加载业务实体，避免等待锁前的一级缓存旧值。

## 必须保持的事务规则

- M1 创建先锁用户 `booking_guard`，再锁教室，核查同用户跨房间重叠和资源占用，在同一事务创建预约、占用、审计和期限任务。
- 其他资源操作先锁教室。M2 提交不预占；第二级批准的步骤记录、申请状态、资源占用同事务提交。冲突必须全部回滚，不能遗留“已批准”步骤。
- M2 先加载需要的审批步骤再修改并统一 flush，避免查询触发中途 AUTO flush，使一次动作增加两次版本。
- 创建 M1/M2 先对配置总版本取共享读锁，再读最长时长。订单保存同一快照中的配置版本与数值；管理员变更取排他锁，历史订单不追溯改变。
- 业务写入与成功幂等结果同事务；M1/M2 的 DTO 字段解析在幂等 action 内，先判断是否已有结果可重放，并使用原始完整 JSON 计算哈希。签到限流先于字段解析。

锁序、回滚与跨服务关系见[数据一致性](../architecture/data-consistency.md)。不要把前端可用性结果当最终锁，不要在 core 数据事务内发 HTTP 或 SMTP。

## 时间、身份与资源效果

业务时区 Asia/Shanghai，API 时间带偏移；区间左闭右开、整点小时、开始严格晚于当前、结束不超过 14 天并覆盖完整开放时段。预约历史按开始时间作 `[startFrom,startTo)` 筛选，单边可省略，双边必须递增；列表和 total 用同一条件并限制本人。界面的结束日期转换为次日 00:00，以包含所选自然日。

每次受保护请求检查账号启用和 authVersion。本人已有业务的读取与收尾可保留所有权例外，但账号仍须启用。SYSTEM_ADMIN 没有隐式审批权；申请人不能自批，两级审批不能由同一人完成。会话、CSRF、幂等与版本协议见 [API 指南](../development/api.md)。

M3 BLOCK 建立本来源整室封禁，取消未来 RESERVED、打断 CHECKED_IN、取消 APPROVED 整室申请；不创建维修违约，不改变 PENDING 申请。RELEASE 只解除该来源，不恢复停用资源、取消历史或其他工单的封禁。内部 commandId 与内容哈希永久去重；来源版本 1 是 BLOCK、2 是 RELEASE，释放墓碑拒绝迟到 BLOCK。来源不能换房间；返回的 sourceState 是 repair 推进同步状态的依据。

## 签到、任务与通知

签到窗口为 `[start,start+15min)`，持锁后以服务端时间判断。每日教室码为排除易混字符的 8 位码；[CheckinCodes](../../services/core-service/src/main/java/com/campus/studyroom/core/CheckinCodes.java)用 BCrypt 保存校验 hash、AES-256-GCM 保存展示密文，每条随机 nonce。展示要求相应权限，普通学生不能读取明码。`checkin_key` 必须在迁移部署时安全保留。

持久任务每 30 秒扫描，执行时重查当前状态；恢复优先处理 NO_SHOW，过时提醒不补发。任务与业务状态同事务，重复扫描不能重复违约。通知与业务同事务落库，SMTP 在事务外；失败保留重试，不回滚预约或审批。机制及暂停排空见[公共机制](shared-services.md)。

## 如何验证与排错

用[测试指南](../development/testing.md)选择有正确前置和数据边界的入口：

| 改动 | 关键验证 |
| --- | --- |
| 预约/申请/资源事务 | CorePersistence、core_http；同用户跨教室竞争、20 人抢一座、最终审批冲突回滚 |
| 权限、时间、配置、统计 | CoreAcceptanceMatrix、CoreInputBoundary；同时检查失败后版本和审计不变 |
| 公开响应/输入 | CoreContract 加真实 HTTP 行为；契约结构通过不等于所有权限矩阵通过 |
| 任务、通知、恢复 | SharedPersistence、CoreSmtpAcceptance、恢复演练；不使用 mock PG/SMTP 替代实际链路 |

遇到 403、409、503 或新代码未生效，从[requestId 排障](observability.md)开始。本地启动/重启和日志路径统一见[本机环境](../development/local-setup.md)，不要把数据库密码复制进示例。

当前 M1/M2 写命令已类型化；认证、管理、内部输入仍有独立解析实现，这是代码组织边界，不是发布“待完成”的结论。当前交付结论见[交付台账](../evidence/delivery-ledger-2026-10-23.md)，具体历史覆盖见[原子流程](../evidence/core/atomic-workflows.md)、[矩阵](../evidence/core/acceptance-matrix.md)、[输入与权限](../evidence/core/typed-inputs.md)和[恢复证据](../evidence/core/restart-recovery.md)。历史证据不自动覆盖之后的改动。
