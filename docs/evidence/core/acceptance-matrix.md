# D / M1 / M2 剩余验收矩阵

2026-10-22。独立 `CoreAcceptanceMatrixTest` 在隔离 PostgreSQL 数据库 `studyroom_core_matrix` 启动随机端口的真实 Spring Boot HTTP 服务；使用 java.net.http、真实 Cookie、CSRF 和幂等头访问公开接口，服务端时钟仅通过测试配置注入。数据库结果由真实仓储或只读查询核对，不使用 MockMvc、mock 身份或调时间HTTP入口。

运行入口：`python3 tests/integration/run_core_acceptance.py`。跨服务动态角色另执行 `python3 tests/integration/core_roles_http.py`，连接真实 core 与 repair 进程。

## 执行记录

首轮14例中12例通过；两个测试适配错误分别为配置接口误用PUT（实际PATCH），以及BCrypt存储前缀未包含Spring的`{bcrypt}`标识。更正调用方法与格式断言后，针对两个用例重跑：每日码、隐私、重复签到通过；配置旧值审计的严格断言失败，`before_json`确实为null。已修复配置审计，以规范化key/value数组同时记录旧值、新值，时间与操作者仍由同事务审计提供。新增405/404真实HTTP断言后，15/15最终通过（测试本体85.63秒、Maven96秒）。中间一次复跑还发现占座fixture误用了seed学生，旧同段订单触发了正确的跨房用户冲突检查；改为每例新UUID账号，未放松任何业务断言。

真实双服务动态角色1/1通过，5.810秒：创建仅`statistics:read`角色、绑定单室范围后可查询统计；不能对他人SUBMITTED工单派单，工单版本/状态/负责人不变；有关联用户的角色不能删除；撤销后core及repair旧会话均401，重新登录统计403。

错误方法请求原先被通用异常处理器映射500；现在保留Spring框架的4xx状态与标准响应头，响应只含固定安全说明。405与不存在路由404均实际通过。对本轮三份验证日志扫描，测试密码、演示密码和请求体探针均未检出；这不代替完整部署环境的全秘密扫描。

## 断言与需求映射

| 用例 | 具体范围 |
| --- | --- |
| logoutAndDisableRejectReplayedCookiesWithoutCreatingOrders | TC-D-01：退出重放旧Cookie受拒、禁用旧会话401、禁用直接登录401、无新增订单 |
| cancellationChecksOwnerAndExactStartAndReplaysOneTransition | TC-M1-02/03：同人首尾相接成功；他人取消404；start−1秒取消成功、start时拒绝；同键重放仅1条取消审计、释放且零违约 |
| dailyCodesRespectTimeRoomNormalizationPrivacyAndSuccessfulReplay | TC-M1-04：昨日与他室码拒绝、start−1秒拒绝、start当刻忽略大小写空白成功、重放只1次签到；授权屏幕可见、普通接口不含明码/hash/ciphertext、数据库hash与密文存储 |
| sixthCheckinAttemptIs429AndWindowReopensAfterSixtySeconds | TC-M1-04：5次失败计数、第6次429且业务未变，60秒后正确码可签到；另一订单start+15分钟拒绝 |
| disabledHistoricalSeatAndWholeRoomOccupancyAreRecheckedOnRebooking | TC-D-04/M1-06：占用时停用409、取消后停用成功、历史座位可读；停用及M2占用导致再约失败，合法新时段新编号且旧历史逐字段不变 |
| mixedSeatAvailabilityAndWholeRoomBoundariesCoverNewSeats | TC-M1-01/M2-04：混合插座/占用查询精确，网格不暴露来源/用户；前一end可用；整室10–12令所有座位（含新增）10–11/11–12拒绝，12开始成功，取消后恢复 |
| fourHourExistingOrderSurvivesTwoHourLimitAndAuditKeepsBothValues | TC-D-05：旧4小时订单不变；改为2小时后3小时失败、2小时成功；0/13/1.5拒绝、非管理员拒绝；当前新增审计的旧/新值、操作者和时间严格核对 |
| complexStatisticsMatchHandCalculatedHoursRateAndRoomScope | TC-M1-07：真实JPA固定历史数据，含完成、取消、未签到、中断、尚未到点、已签到、跨日及范围外记录；总数7、取消2、未签到1、签到率3/4、座位小时7.5，空集合rate=null |
| applicantRoleOverlayEnablesSubmissionReplayAndFreePendingSpace | TC-M2-01：普通学生拒绝，动态叠加后旧会话失效、重新登录成功；同键同编号，PENDING不占用；两个expires_at分支 |
| selfApprovalAndSameActorBothStepsFailWhileHistoryStaysPrivate | TC-M2-02/05：本人即使具有审批权限也不能自批，外部审批人不能同时两级；拒绝理由保留，无关学生404且无内容；撤销创建权限后本人历史仍能读 |
| approvedApplicationCannotBeCancelledByOwnerAtStartButScopedManagerCan | TC-M2-03：开始当刻本人取消拒绝、claim保留；管理取消空理由拒绝，合格范围管理员带理由成功释放 |
| scopedManagerCannotMutateAnotherRoomAndAttachedRoleCannotBeDeleted | TC-D-02/03：只管理R1的账号修改R2被拒，R2逐字段不变；R1可改，有用户关联角色不能删除 |
| threeReminderStagesArePersistentDeduplicatedAndPrivate | TC-M1-05/D-06：持久任务依次触发三类EMAIL通知，各重复3次仅1条，最终NO_SHOW/1违约/释放；他人已读404，本人已读及重放/列表刷新保留 |
| checkinAndNoShowRaceRetainOneLegalOutcomeAtEachBoundary | TC-M1-05：start+14:59与start+15:00分别双线程屏障竞争，仅CHECKED_IN无违约或NO_SHOW有1违约，不能两者并存 |

| frameworkMethodAndRouteErrorsRemainSafeClientErrors | 标准HTTP语义：不支持方法405、不存在路由404，拒绝响应不回显请求路径，无业务写入 |

固定统计数据仅用作统计投影的输入，并不声称由完整历史流程产生；状态转换由其他真实服务/HTTP用例验证。通知EMAIL记录不等同SMTP送达，实际邮件与停启补偿指标仍由工程验收单独闭环。全页面角色/移动端矩阵、原目标规模负载与正式外部SMTP也不由本套件代替。
