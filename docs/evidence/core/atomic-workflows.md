# M1/M2 与维修级联事务验收

2026-10-22，Java 21、Spring Boot 4.1.1、Hibernate 7.4.5、PostgreSQL 18.6。执行 `python3 tests/integration/run_core_persistence.py`，隔离数据库 `studyroom_core_integration`；16/16通过，测试本体16.82秒。随后重建并重启真实服务，`python3 tests/integration/core_http.py` 6/6通过，12.281秒，覆盖原并发、权限、幂等、最终审批冲突以及日期筛选。

该套件通过真实业务服务、JPA 仓储和 PostgreSQL 事务执行，不替换资源裁决。每例创建独立房间、座位和学生；固定时钟只在测试配置注入。后台扫描在隔离环境暂停，测试显式调用真实任务处理器。并发例使用两个线程的 `CyclicBarrier`，不靠随机延迟制造冲突。

| 需求/用例 | 测试与实际断言 |
| --- | --- |
| TC-X-02：M1 与最终审批竞争 | `reservationAndFinalApprovalCompeteAtTheSameRoomBarrier`：已有第一级批准的申请，与同室同段M1创建在屏障同时放行。恰好一方成功，另一方409 RESERVATION_CONFLICT；有效claim仅1。按赢家核对申请版本/步骤/决定/通知以及失败方无业务或claim残留。 |
| TC-X-02：claim写后失败 | `failureAfterClaimInsertRollsBackApplicationClaimAuditAndNotification`：先插入与最终审批人不同的测试审批动作，让后续真实写入触发PG的申请+步骤唯一约束。临时Hibernate POST_INSERT观察器确认目标M2 claim的INSERT已执行成功，才接到后续约束异常。申请所有字段、步骤、动作、审计与通知均与事务前快照完全一致，M2 claim不存在。 |
| TC-M2-02：同一步双审批 | `simultaneousDecisionsCommitOnlyOneStepAction`：同一步、同expectedVersion的两个审批同时放行。只有1个决定、1条审批审计、一次版本增长，另一个VERSION_CONFLICT；步骤为APPROVED/PENDING，尚无整室claim。 |
| TC-M2-03：截止与审批竞争 | `expiryAndFinalApprovalCannotLeaveExpiredApplicationClaimed`：截止当刻最终审批与过期处理同时执行；审批拒绝，最终EXPIRED且无M2 claim、无批准通知。另一申请在截止前1秒批准，截止扫描不能把它改成EXPIRED，原claim继续有效。 |
| TC-X-04：维修五态级联 | `maintenanceCascadesFiveStatesWithoutChangingHistoryOrRevivingOrders`：真实创建未来RESERVED、签到CHECKED_IN、APPROVED整室、PENDING整室、已完成历史五类对象。BLOCK后依次CANCELLED/INTERRUPTED/CANCELLED/原样/原样；打断结束时间等于服务端生效时刻，受影响claim释放。仅3条对应来源通知、3条SERVICE维护审计、零违约。RELEASE后全部业务字段保持不变，无订单复活。 |

## 故障注入确实位于claim写入之后

观察器只观察指定申请的 `ClaimEntity`，不修改事件、不抛异常、不改变业务结果。若服务在claim写入前拒绝，该观察断言会失败，因此不能用早期校验失败冒充事务回滚证据。异常来自 PostgreSQL 的 `approval_action_application_id_step_no_key` 约束。`finally` 恢复原来的全部监听器并删除测试故障行，生产服务没有故障注入开关或调试接口。

## 验收发现并修复的问题

首轮16项中15项通过。测试推进时钟后发现末级批准的 `status` 先被 `saveAndFlush` 刷新，稍后 `updatedAt` 又刷新，单次批准令业务版本从1变3，而claim记录的来源版本是2。第一步去掉中途显式flush后，对首级审批增加推进时钟的断言，又发现查询下一步骤会触发Hibernate AUTO flush，同样导致单次版本增加2。

修复为：在任何状态修改前加载本步骤和下一步骤；修改决定、步骤与申请时间/状态后统一flush。保留claim先写入且与审批处在同一事务。最后16/16通过，首级、末级审批都只增长一次版本，claim来源版本与批准后的申请版本相同。没有放松版本断言或删除失败用例。

这些证据覆盖上述具体数据库组合。申请人自批、同人两级、批准后开始时刻撤销权限、完整真实邮件和进程恢复指标仍按需求追踪表继续验收，不由本套件自动判定通过。
