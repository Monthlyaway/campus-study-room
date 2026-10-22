# M1/M2 类型化命令、请求边界与管理权限

2026-10-22。`api-contracts/command` 新增预约创建/动作、申请创建/审批/撤销五类请求DTO与共同JSON快照支持。公开Controller接收DTO，业务本体通过UUID、OffsetDateTime、String、long访问器读取参数；已有Map服务入口仅转入相同DTO，供内部调用和既有测试兼容。业务时间、权限、长度和状态规则继续在事务服务中裁决。

DTO保存完整、递归不可变的原始JSON，不从类型化字段重建幂等body。类型解析在幂等动作内部发生：已完成请求先重放原结果；缺省字段、显式null、未知字段、原时区字符串均保持原哈希语义。签到仍先持久化限流，再进行幂等及字段解析，小数/缺失等版本错误不会绕过次数。版本整数校验由api-contracts共享，非整数、负数、溢出或非数字返回400 INVALID_VERSION；合法过期版本仍409。

## 运行

`python3 tests/integration/run_core_inputs.py` 使用独立PostgreSQL数据库 `studyroom_core_inputs`、真实随机端口Spring HTTP、Cookie/CSRF/幂等键和可控业务Clock。`CoreInputBoundaryTest` 必须设置 `STUDYROOM_INPUT_DB_TESTS=true`；CI使用已有reference/demo环境，不新增mock或外部依赖。

本轮联合回归27/27通过：`CoreContractTest` 5/5（13.42秒），`CorePersistenceTest` 16/16（8.054秒），`CoreInputBoundaryTest` 6/6（6.339秒）。其中新输入套件第一次4/4通过后补入两项管理范围/停用资源组合，再运行联合回归。后续对学生管理用例追加五项操作拒绝，定向1/1通过（6.851秒）；该用例共9类读取与19类管理写入拒绝。

| 新用例 | 真实断言 |
| --- | --- |
| oldMapIdempotencyRecordsReplayWithUnknownNullAndOffsetFieldsUntouched | 已持久化Map格式预约重放201且业务行不变；未知嵌套对象/含null列表完整保留；同键删字段、改显式null或等价时区文本均409；已完成审批省略comment能重放，改显式null同键409且仅1审批动作 |
| m1AndM2RequireOffsetsWholeHoursAndInclusiveFourteenDayEnd | 两模块分别验证开始/结束无时区400、半小时与纳秒非整点422、当前起点/零长度/14天外422；失败无订单/申请/claim；结束恰好第14天两模块均201 |
| malformedVersionsStillConsumeCheckinLimitAndNeverMutateState | 小数/数字字符串/负数/溢出/缺失version均400，正确码的第6次尝试429；预约仍RESERVED/version0 |
| studentCannotUseAdministrativeReadsOrWritesAndRowsStayUnchanged | 9类管理读取403；账号创建/更新/密码/绑定、角色创建/权限/删除、主数据创建/修改、开放时间与配置等管理写入403；账号/角色/空间行逐字段不变 |
| roomScopedManagerCannotWriteOrInspectPrivateObjectsInAnotherRoom | 仅R1范围的管理者写R2房间/座位/开放时间/通知重试403；R2占用与每日码403，R2审计/送达/统计范围排除；被拒数据逐字段不变；R1新增座位201 |
| disabledRoomBlocksPendingFinalApprovalAndRepairReleaseNeverEnablesIt | 申请第一级批准后停用房间，最终审批422且申请/步骤/占用保持合法；维修BLOCK/RELEASE后仍停用、房间版本不变、不可预约，PENDING申请不被级联取消 |

以上补充对应REQ-D-03/04、REQ-M1-02/04、REQ-M2-01/02与REQ-X-01的具体子场景。Auth、管理和内部控制器仍有Map请求，响应Java投影仍有Map；这一检查点只声称关键M1/M2写入DTO已完成。其余精确DTO迁移、所有异常输入、页面与部署验收不能由这27个测试代替。
