# Core 验收证据

2026-10-22，本地 Java 21 + Spring Boot 4.1.1 + PostgreSQL 18.6。

## 已实际执行

- `./mvnw -pl services/core-service,tools/migration-runner -am package -DskipTests`：通过；两个共享模块、core、独立迁移程序可打包。
- `./mvnw -pl services/core-service -am test`：7个 DomainPolicyTest 用例通过，覆盖时间偏移量、整点与14天限制、签到左右边界、版本和终态、文本与幂等规范化。
- `python3 tests/integration/core_http.py`：5个真实HTTP验收用例通过，运行15.760秒。使用真实PG、Spring Security会话和数据库资源锁，没有mock资源裁决。

| 验收 | 观察到的结果 |
| --- | --- |
| TC-D-01 / TC-D-03 | 学生读取管理API403；跨院系详情404；账号禁用后旧会话401 |
| TC-M1-02 / TC-X-01 | 20个不同学生线程通过屏障同时预约；1个201，19个409；只存在1个有效claim |
| TC-M1-02 跨房间 | 同一个学生2个会话同时预约2间教室重叠时段；1个201、1个409 |
| 幂等与隐私 | 相同键同body重放相同data；同键改body409；他人订单详情404 |
| TC-M2-02 | 第二级抢先审批403；第一级通过后遇M1占用，最终审批409且状态仍PENDING、仅1条决定；释放冲突后批准成功 |

## 调试中发现并修复

1. PostgreSQL advisory lock函数返回void，直接按Long读取失败。改为`select 1 from pg_advisory_xact_lock(...)`，保持同事务锁。
2. 共享record principal默认toString超过Session principalName列长度。实现标准`Principal.getName()`返回UUID，登录与共享Session正常。
3. 在代理对象外访问注入EntityManager字段为空。改为Repository方法封装访问，真实预约与主数据操作验证通过。
4. HTTP验收之后增加requestId贯通、来源房间校验、数据库分页与统计聚合；这些变更会在最终整套回归再次执行，未将尚未运行的回归记为已通过。

原始控制台输出保存在任务执行记录；持续集成通过`tests/integration/core_http.py`重放。关键约束的故意破坏反证见下文，其他门禁仍按实际执行情况记录。

## JPA 聚合与查询整改后的回归

2026-10-22，命名迁移到 `campus-study-room` 后：

- `python3 tests/integration/core_http.py`：5/5通过，11.207秒；此次运行使用新 Reservation/Application/Claim Entity 与 Spring Data JPA 仓储。
- `python3 tests/integration/run_core_persistence.py`：隔离数据库 `studyroom_core_integration`，8/8通过，测试本体7.173秒；使用真实 PostgreSQL 18.6 与测试注入 Clock，未开放调试时间HTTP接口。
- 同日完成账号/配置仓储接入、配置一致快照、审计筛选修复后重跑：10/10通过，测试本体9.743秒。新增配置用例第一次因误用不具备 `config:manage` 的 operator 账号被正确拒绝，调整为具有该权限的 admin 后通过，未扩大权限或删除断言。

新增持久层用例：

| 用例 | 结果 |
| --- | --- |
| checkinBoundaryAndPersistedNoShowAreExclusive | +14:59可签到并完成；+15:00拒绝，重复到期处理仅1条违约 |
| maintenanceBeforeNoShowDoesNotPenalizeStudent | 维修先提交则取消，之后超时不记违约，释放不复活 |
| committedNoShowRemainsHistoricalDuringMaintenance | 已提交NO_SHOW及违约不会因维修被抹去 |
| sourceTombstoneAndMultipleMaintenanceClaimsAreIndependent | 命令重放相同JSON；双工单只释放指定来源；RELEASE先到阻止旧BLOCK复活 |
| concurrentDeadlineScannersOnlyCommitOneViolation | 8线程屏障并发处理同预约，1条违约、1条NO_SHOW通知 |
| availabilityAndStatisticsUseDatabaseProjection | 完整时段开放/过滤/分页正确；预约后余位0，聚合1座位小时 |
| sameSeatConflictCannotCommitSecondClaim | 两用户同座位第二次409，仅1条有效来源占用 |
| foreignDepartmentCannotCreateReservation | 直接调用服务创建跨院系预约404，数据库没有新增订单 |
| configurationChangeOnlyAffectsNewOrders | 已有订单保留原配置版本/时长；改为2小时后新3小时订单被拒，新1小时订单记新版本 |
| auditAndClaimFiltersApplyBeforeDatabasePagination | objectId UUID、动作/房间、来源与左闭右开时间过滤的total与列表一致；普通学生查询审计403 |

固定时钟只存在测试配置中。测试账号、房间使用新UUID隔离；生产服务没有调时间或绕过鉴权接口。

## 关键约束反证

2026-10-22，执行 `python3 tests/integration/core_mutation.py`。Runner 把后端源代码复制到系统临时目录，只修改副本；真实工作区、正在运行的服务和共享 Maven target 均不修改。使用独立数据库 `studyroom_core_mutation`，先执行未修改的3个用例，再分别破坏一条约束，最后恢复副本并再次执行3个用例。

| 故意引入的错误 | 检测结果 |
| --- | --- |
| 删除创建预约时的座位资源冲突判断 | `sameSeatConflictCannotCommitSecondClaim` 失败：应抛出冲突但第二个预约成功 |
| 删除创建预约时的院系准入判断 | `foreignDepartmentCannotCreateReservation` 失败：应返回404但越权预约成功 |
| 释放来源时错误地释放同教室所有未释放claim | `sourceTombstoneAndMultipleMaintenanceClaimsAreIndependent` 失败：另一个工单的封禁丢失 |

3项变异均被业务断言检测，没有把编译错误或启动错误当作检测成功；恢复后3/3通过。完整日志与结构化断言保存在受忽略的 `output/core-mutations/`，脚本可重新生成。该证据覆盖这3条具体保护规则，不代替全部状态与性能验收。

## 预约历史日期筛选（REQ-M1-06）

2026-10-22，修复后执行 `python3 tests/integration/run_core_persistence.py`：11/11通过，Maven总耗时14.668秒。新增 `reservationHistoryUsesHalfOpenDateBoundsBeforePagination`，真实创建边界前一小时、边界当刻、当天23点、次日00点四个预约，再加入其他用户订单，验证：

- `[startFrom,startTo)` 只包含中间两条，分页total始终2；第3页为空。
- +08:00与等价UTC边界结果一致；单侧范围、空值、日期与取消状态组合正确。
- 查询只返回本人；无偏移/无效时间以及反向/相等边界返回400，不修改订单。

前端结束日发送次日00:00+08:00，OpenAPI与生成客户端同步。生成、TypeScript检查以及6个前端API测试通过。真实服务重启后执行 `python3 tests/integration/core_http.py`：6/6通过，12.912秒。新用例通过公开API重复验证两种偏移表示、跨日边界、status组合、页码/total、400错误与读取无副作用；原20用户抢座与同人跨房间竞争同时回归通过。

另由实现者通过375×812真实浏览器复验：选择10月25日时只显示该日1条，改为10月26日时0条；请求上界确为所选结束日次日零点。过程及截图说明见[独立浏览器记录](browser-review.md)。

## 资源、审批与维修组合事务回归

新增5组真实PG事务组合后，隔离套件16/16通过（测试本体16.82秒）。覆盖M1与M2最终审批屏障竞争、claim已插入后的真实PG异常回滚、同一步并发审批、截止时刻与过期竞争，以及维修五态级联。验收发现并修复审批中途flush导致单次版本增长2的问题；固定时钟推进后首级/末级版本与claim来源版本均正确。故障位置证明、全部断言与验证边界见[组合事务验收](atomic-workflows.md)。

本次修复重建、重启后真实HTTP6/6再次通过（12.281秒）；没有以旧runtime的结果代替新修复回归。
