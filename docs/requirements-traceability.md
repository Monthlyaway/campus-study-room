# P0 需求、实现与验收追踪

更新时间：2026-10-22。本表逐条保留 [PRD](../01_PRD.md) 的 **39 个 P0 REQ**，并关联 [技术方案](../02_Technical_Design.md)、[milestone](../03_Development_Milestones.md)。测试通过只证明其实际断言；一个有关联的测试通过，不等于整条 TC 或阶段门禁通过。当前仍有明确缺口与未执行验收，**尚不能声明全部 P0 完成**。

用户调整的交付边界见 [ADR 0002](adr/0002-delivery-and-gitlab.md)：GitLab 取代 GitHub Actions；用户授权自主提交和本地发版；正式公网/TLS、外部SMTP实际收信、正式服务器生产发布待后续目标信息。业务、权限、并发、数据保护要求不缩减。本表中的“部分验证”表示已有运行证据但尚欠该 TC 的具体子场景；“缺口”表示已经识别实现或交付不足；“待执行”不能视为通过。

## 实现与证据索引

- [Core 模块](modules/core.md)、[账号与管理](modules/administration.md)、[维修模块](modules/repair.md)、[前端模块](modules/web.md)。
- [Core 实测](evidence/core/acceptance.md)：7个规则单测、5个真实HTTP、10个隔离PG、3个故意破坏反证及恢复通过。
- [独立 M1/M2 浏览器试用](evidence/core/browser-review.md)：实际桌面与375px、多账号操作；不模拟资源裁决。
- [维修实测](evidence/repair-validation-2026-10-22.md)：10个规则单测、4个隔离PG、2个真实HTTP、提交成功后响应丢失重试、独立手机试用。
- [D 管理实测](modules/administration.md#迁移与验证)：7个隔离PG、2个真实HTTP、SELF范围和跨服务旧会话失效。
- 共享层真实PG 7/7由根代理执行；断言见 [SharedPersistenceTest][shared-test]。尚需补充具体SMTP及进程恢复证据，不能将数据库租约测试视为外部邮件送达。
- 主逻辑首轮提交：`34aef3c`（构建/迁移基础）、`49300fb`（D/M1/M2行为）、`1ec7ccb`（M3）、`8c50781`（前端）、`e68cedb`（前端细化）。后续JPA与验收整改当前为待统一提交的工作区检查点，发布tag/manifest由发版任务补齐；不把工作区冒充已发布版本。

## D 公共层

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-D-01 / T10-01、T10-06 | [认证][auth]、[安全][security]、[账号][admin] | core HTTP禁用后401；admin HTTP同时验证core/repair401；浏览器登录退出 | 部分验证：补禁用账号直接登录、退出后重放原Cookie且无新增订单的完整组合 |
| REQ-D-02 / T10-02、T50-01 | [RBAC][access]、[角色管理][admin] | Admin PG验证同一绑定权限+范围、SELF不扩大管理权限、权限变更版本；admin HTTP角色保存使旧会话失效 | 部分验证：补“新动态统计角色→统计成功→派单失败→撤销后旧会话拒绝”完整TC，并验证有用户角色不得删除 |
| REQ-D-03 / T10-02、T50-01 | [范围授权][access]、[可用性][availability] | core HTTP/PG跨院系拒绝、共享教室成功；Admin PG同绑定隔离；范围mutation被捕获 | 部分验证：补仅R1管理员直接修改R2及数据库不变的HTTP矩阵；所有管理列表范围仍需逐端点核对 |
| REQ-D-04 / T10-03 | [主数据][admin]、[房间锁与来源][resource] | Admin PG规则版本/并发父停用与子创建；真实浏览器创建与查询；真实PG资源模型 | 部分验证：补占用座位停用冲突→取消后停用→历史仍读，以及整室占用中新建座位仍不可约的明确TC |
| REQ-D-05 / T10-03 | [配置管理][admin]、[订单配置快照][booking] | Admin PG13拒绝及批量原子性；Core PG新订单限制与旧订单快照、版本保持 | 部分验证：现有旧订单示例为1小时，补0/小数拒绝、规格指定旧4小时/新2小时成功，并核对审计旧值/新值字段 |
| REQ-D-06 / T10-05、T20-04 | [通知][notifications]、[任务与事件][events] | Shared PG事务回滚、去重、失效租约、8次失败/手动原记录重试、已读及分页 | 部分验证：补真实SMTP失败→恢复→对应M1三类提醒邮件捕获，重启后发送、他人标记已读拒绝；正式邮箱收信按ADR延期 |
| REQ-D-07 / T10-05、T50-01 | [审计事件][events]、[审计查询][queries] | Shared PG审计UPDATE/DELETE权限拒绝及JOB类型；Core PG objectId/动作/房间筛选、学生403 | 部分验证：补维修BLOCK/RELEASE按同工单关联两侧requestId查询、拒绝审批/越权审计与全日志秘密扫描报告 |

## M1 自习座位预约

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-M1-01 / T20-01 | [可用性SQL分页][availability]、[查询][queries] | Core PG插座/room过滤、占用排除与total；真实浏览器选择日期/座位 | 部分验证：补混合座位、跨M2整室边界、恰好上一end的数据库查询矩阵；全部座位不可用原因隐私验证 |
| REQ-M1-02 / T20-01 | [预约事务][booking]、[资源裁决][resource] | core HTTP20用户屏障：1成功19冲突且1claim；同用户跨2室1成功；幂等重放；冲突mutation失败 | 部分验证：补同用户首尾相接2次数据库成功；全部输入边界通过规则单测但仍需关键API组合 |
| REQ-M1-03 / T20-02 | [取消][booking] | 独立浏览器桌面创建→手机取消、终态更新；HTTP取消释放用于审批冲突解除 | 部分验证：补start前1秒/start当刻持久化边界、非本人取消及失败后状态不变、重复幂等取消 |
| REQ-M1-04 / T20-03 | [每日码][codes]、[签到][booking]、[限流][limits] | DomainPolicy左右边界；Core PG+14:59成功/+15:00拒绝；Shared PG持久计数与过期 | 部分验证：补start前1秒/start当刻真实签到、旧日期/他室码、成功重复重放、第6次HTTP429、普通接口不泄码 |
| REQ-M1-05 / T20-04 | [持久任务][tasks]、[预约处理][booking] | Core PG8线程仅1违约/通知，维修先后提交公平处理；签到完成及NO_SHOW释放 | 部分验证：补三个提醒时点与真实邮件、签到与NO_SHOW同时竞争、进程停启跨越多个时点不补过期提醒、≤60秒实测 |
| REQ-M1-06 / T20-05 | [历史与再次创建][booking]、[页面][web] | 本人详情隔离HTTP；Core PG11/11与HTTP6/6含日期半开边界、状态组合、分页total和400校验；375px真实浏览器10月25日1条/10月26日0条及次日零点上界核对；存在再次预约入口 | 日期筛选已修复并回归；补历史停用/M2占用时再约失败及成功后新编号、原历史不变 |
| REQ-M1-07 / T20-05 | [统计聚合][queries] | Core PG1笔1座位小时；SQL范围/聚合实现 | 部分验证：补含取消/NO_SHOW/INTERRUPTED/跨日/零分母的完整手算固定数据集与范围外排除；页面指标名称复核 |

## M2 整室申请

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-M2-01 / T30-01、T30-04 | [提交及步骤][application] | 真实HTTP纯STUDENT403、applicant创建且PENDING时M1同段仍可约；独立浏览器提交 | 部分验证：补动态叠加角色后创建成功、申请同键重放同编号与期限边界 |
| REQ-M2-02 / T30-02 | [两级审批事务][application] | core HTTP第二级抢先403；最终资源冲突409状态/决定回滚；浏览器不同合格两人顺序批准 | 部分验证：补申请人自批、同人两级、同一步并发决定屏障；写入claim后强制异常的原子回滚 |
| REQ-M2-03 / T30-03 | [撤销与过期任务][application] | 浏览器批准后申请人开始前撤销；HTTP取消释放 | 部分验证：补截止当刻拒绝且最终EXPIRED、审批与过期并发无残留claim、开始后本人拒绝/范围管理员带理由成功 |
| REQ-M2-04 / T30-02、T30-03、T30-05 | [整室claim][resource]、[最终审批][application] | HTTP已有M1阻止最终批准且释放后批准，批准后同段座位409；M3叠加测试验证整室资源 | 部分验证：补批准10–12后全室任意座位10–11/11–12拒绝，12开始成功，取消恢复且保留其他来源 |
| REQ-M2-05 / T30-01、T30-04 | [历史与读取范围][application] | 独立三账号浏览器看到两级顺序、时间、人员和已批准状态 | 部分验证：补拒绝理由显示、无关学生编号请求404且不泄露字段、权限撤销后历史读取规则 |

## M3 报修与验收

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-M3-01 / T40-01、T40-06 | [维修服务][repair] | repair HTTP合法上报/空标题拒绝/对象隔离/幂等；手机真实创建取消；同室双来源完整链路 | 部分验证：补超长说明、无准入教室的API断言，不能仅以policy文本边界单测代替全部请求 |
| REQ-M3-02 / T40-02、T40-06 | [派单与权限][repair] | repair HTTP完整派单；policy首次期限固定、维修人员权限；候选走core HTTP | 部分验证：补非维修人员、范围外调度员、重派后旧维修人拒绝/新维修人成功且deadline不延后的真实API组合 |
| REQ-M3-03 / T40-03 | [outbox与worker][repair]、[core封禁][resource] | 真实HTTP BLOCK_PENDING→IN_REPAIR；透明代理丢失已提交响应后原命令重试成功 | 部分验证：单独补core完全断网→页面显示同步中→恢复自动收敛；生产入口拒绝内部封禁请求验证 |
| REQ-M3-04 / T40-04 | [完工][repair] | repair HTTP完工后等待验收且仍封禁；policy仅assigned操作 | 部分验证：补另一维修人直接完工/空说明API拒绝及数据库不变；核对报修人收到对应通知 |
| REQ-M3-05 / T40-04、T40-05 | [验收/来源释放][repair]、[资源][resource] | HTTP返修/通过/双来源叠加；policy维修人即使管理员也不能自验；来源mutation检测 | 部分验证：补“报修人同时是维修人→合格物业管理员独立验收”整条真实API；拒绝与释放失败后的状态矩阵 |
| REQ-M3-06 / T40-06 | [评价][repair] | repair HTTP CLOSED首次评价及第二不同请求409；policy仅本人已关未评价 | 部分验证：逐项执行0、6、小数、IN_REPAIR评分、相同评价幂等键重试与原评分不变 |
| REQ-M3-07 / T40-02、T40-04 | [期限任务][repair] | Repair PG可控Clock三期限轮次、8线程去重、返修原截止、超时不自动关单 | 部分验证：补真实通知接收对象及验收超时后M1仍不可约、进程重启补偿≤60秒实测 |
| REQ-M3-08 / T40-01 | [取消][repair] | repair HTTP SUBMITTED本人取消/重放、ASSIGNED本人取消409；policy状态/角色矩阵 | 部分验证：补ASSIGNED调度成功、IN_REPAIR取消失败且有效封禁不变的完整真实API断言 |

## 跨模块一致性

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-X-01 / T10-04、T40-05、T40-07 | [来源实体/资源][resource] | Core PG双来源与墓碑；repair真实HTTP双工单先后解除；来源限定mutation被检测 | 主路径已验证，待按管理查询核对全部来源历史、释放时间/requestId，并补释放后仍有M1/M2来源的组合 |
| REQ-X-02 / T10-04、T30-02、T30-05 | [统一房间锁][resource]、[审批事务][application] | 同座20线程；M1已占用时M2最终审批完整回滚 | **待专项：M1创建与M2最终批准真正屏障并发，以及claim插入后/审批完成前强制异常回滚。** 顺序冲突测试不能代替这两项 |
| REQ-X-03 / T10-05、T40-03、T40-05 | [outbox][repair]、[永久去重/墓碑][resource] | repair故障代理保留相同commandId/requestId/payload两次HTTP；Core PG先RELEASE后旧BLOCK不复活 | 已验证提交后丢响应与墓碑；补双侧审计关联、重启与租约过期/网络不可达组合；正式发布下恢复另验 |
| REQ-X-04 / T10-04、T40-03、T40-07 | [维修级联][resource] | repair HTTP未来预约被维修取消且不复活；Core PG维修/NO_SHOW两种提交顺序均无错误追责 | 部分验证：补同一测试同时准备RESERVED、CHECKED_IN、APPROVED、PENDING、已结束记录，核对五种结果/通知/零新增违约和释放后不复活 |

## 工程交付

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-ENG-01 / T00-01、T60-01 | [协作规则](../AGENTS.md)、[分工](team.md)、本表 | 分模块连续commit与agent实测记录；原规格与ADR已入库 | 部分验证：逐REQ补最终commit/MR/tag/manifest闭环；独立agent审核不冒充人工签字；原人工提交流程按用户授权调整 |
| REQ-ENG-02 / T00-02、T50-02 | [HTTP测试][core-http]、[PG测试][core-test]、[反证][mutation]、浏览器证据 | 真实PG与两个进程HTTP、3项变异均业务断言失败且恢复通过，独立桌面/手机 | 反证子门禁通过；完整M1签到/完成、M2拒绝/过期和多角色全部UI E2E仍未覆盖，不能据此关闭G5 |
| REQ-ENG-03 / T00-04、T50-04 | [迁移程序][migration]、[版本SQL](../db/migration) | 空库迁移/reference/demo、重复seed；新增SELF纠正迁移在原库后应用，未改原迁移 | 部分验证：补旧版业务数据→新schema升级→旧版镜像仍读写的完整演练及数量报告 |
| REQ-ENG-04 / T00-03、T00-04、T50-05 | [环境与发布脚本][ops]、[GitLab CI][ci] | local与隔离PG套件使用不同数据库；独立test Compose安装由根代理执行 | 待执行test/prod（或等效隔离验收环境）不串数据证据；跨进程部署/集成测试争用同一环境锁的实际排队证明 |
| REQ-ENG-05 / T00-05、T50-05 | [GitLab CI][ci]、[全仓检查](../scripts/check) | 本机全仓check通过；有构建/生成漂移/PG/HTTP任务配置 | **未闭环：GitLab真实提交触发、故意失败阻断部署与合并、修复后整套test自动升级及E2E。** 配置文件存在不等于pipeline已运行 |
| REQ-ENG-06 / T00-06、T50-04～06 | [发布/回退][ops]、[版本API][controller] | 不可变组合校验与错误manifest的ops单测；本地版本接口实现 | 待执行带新增可空列V2→旧组合V1回退、关键业务仍可用且schema/数据保留；三版可拉取留存与最终tag/manifest。正式生产审批按ADR保留待验收 |
| REQ-ENG-07 / T00-03、T60-02 | [安装入口](../install.sh)、[部署脚本][ops] | 根代理已完成本机独立test Compose首次安装；前端生产入口独立复核 | 待在该新环境跑完整预约/审批/报修并归档安装/健康/业务证据；另一服务器迁移复现与正式目标条件分开记录 |
| REQ-ENG-08 / T00-01、T50-01 | [安全][security]、[伦理记录](ethics-and-risks.md) | HTTP CSRF/越权拒绝、Cookie HttpOnly/SameSite配置，维修公平处理PG；非真实学生测试数据 | 部分验证：全运行日志/提交秘密扫描、签到码泄露接口矩阵、统计无个人违约排名与四类伦理记录的具体commit关联；正式HTTPS按ADR延期 |

## 无REQ编号但仍属于验收基线的项目

| 基线 | 已知事实 | 仍需执行 |
| --- | --- | --- |
| PRD§10 必备页面和错误反馈 | React/Umi页面已实现，独立M1/M2/M3与admin部分流程已操作 | 逐页面角色可见性、409刷新、未知结果同幂等键重试、401/403/422/429/503、加载/空态、键盘与375px完整矩阵 |
| PRD§12 数据规模 | 数据库分页/聚合已整改 | 1000账号、20室、1000座、10万历史记录数据准备与分页正确性/查询数量；不能拿小样本通过替代 |
| PRD§12 性能 | 已有20请求资源正确性测试 | 4vCPU/8GiB明确环境；20并发、60秒预热+5分钟80:20读写；查询p95≤1s/写p95≤2s/5xx<1%的分类报告 |
| PRD§12 定时与恢复 | Clock测试+数据库任务/租约持久化；M3响应丢失重试30秒左右收敛 | 真实停启跨期限、恢复≤60秒开始补偿、健康延迟≤60秒；各类型误提醒/重复业务核对 |
| PRD§12 容器持久化 | 命名volume与共享JDBC Session实现 | 重建应用容器前后核对订单、审批、维修、会话与审计，保留数据卷且验证数量/关键对象 |
| 技术方案§1/§2.3/§6 契约 | 日常写入已JPA实体化；OpenAPI生成可编译 | 部分管理/业务请求仍Map和通用Envelope，补精确DTO/schema及错误契约对照；不能将类型生成成功等同契约完整 |
| milestone G0～G6 | 多项实际实现/测试已完成，原milestone初始状态保留为规格 | 只有本表所有相应证据闭环后更新工作进度门禁，不能回填不存在的人工验收或历史pipeline |

## 下一轮优先验收清单

1. 继续REQ-M1-06再次预约的停用、M2占用和新编号/旧历史不变验收；日期筛选已完成DB/API边界与分页回归。
2. 加入真实PG竞争与失败注入：M1对M2最终审批、同一步双审批、M2过期竞争；补维修五态级联，保留严格断言。
3. 补签到每日码/权限/限流，以及M1/M3真实进程停启、三类提醒邮件、迟到任务补偿与60秒指标。
4. 用固定复杂数据集验证统计；按表逐个填齐管理与维修的负面HTTP子场景。
5. 完成新test容器三条业务链、保留卷重建、备份恢复与V2→V1兼容回退，生成可核验release manifest。
6. 实际运行GitLab pipeline正反门禁与整套自动部署，归档提交/MR/流水线/版本链接。
7. 运行指定容量与5分钟负载；完成秘密扫描、接口/页面全矩阵、文档复现，最后决定各阶段门禁状态。

P1 `REQ-AGENT-01` 未启动，不计入以上39项，也不用于替代P0验收。

[auth]: ../services/core-service/src/main/java/com/campus/studyroom/core/AuthController.java
[security]: ../services/core-service/src/main/java/com/campus/studyroom/core/SecurityConfiguration.java
[admin]: ../services/core-service/src/main/java/com/campus/studyroom/core/AdminService.java
[access]: ../services/core-service/src/main/java/com/campus/studyroom/core/AccessService.java
[booking]: ../services/core-service/src/main/java/com/campus/studyroom/core/BookingService.java
[application]: ../services/core-service/src/main/java/com/campus/studyroom/core/ApplicationService.java
[resource]: ../services/core-service/src/main/java/com/campus/studyroom/core/ResourceService.java
[availability]: ../services/core-service/src/main/java/com/campus/studyroom/core/AvailabilityReadRepository.java
[queries]: ../services/core-service/src/main/java/com/campus/studyroom/core/QueryService.java
[codes]: ../services/core-service/src/main/java/com/campus/studyroom/core/CheckinCodes.java
[limits]: ../services/core-service/src/main/java/com/campus/studyroom/core/RateLimits.java
[notifications]: ../services/core-service/src/main/java/com/campus/studyroom/core/Notifications.java
[events]: ../services/core-service/src/main/java/com/campus/studyroom/core/Events.java
[tasks]: ../services/core-service/src/main/java/com/campus/studyroom/core/TaskExecutor.java
[controller]: ../services/core-service/src/main/java/com/campus/studyroom/core/CoreController.java
[repair]: ../services/repair-service/src/main/java/com/campus/studyroom/repair
[web]: ../apps/web/src
[core-http]: ../tests/integration/core_http.py
[core-test]: ../services/core-service/src/test/java/com/campus/studyroom/core/CorePersistenceTest.java
[shared-test]: ../services/core-service/src/test/java/com/campus/studyroom/core/SharedPersistenceTest.java
[mutation]: ../tests/integration/core_mutation.py
[migration]: ../tools/migration-runner/src/main/java/com/campus/studyroom/migration/MigrationRunner.java
[ops]: ../scripts/studyroom.py
[ci]: ../.gitlab-ci.yml
