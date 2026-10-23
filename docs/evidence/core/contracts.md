# Core API 契约与协议边界验收

2026-10-22。`contracts/openapi/core.yaml` 对公开56个操作声明具名请求/响应、分页、状态枚举、可空字段、查询条件及共享错误响应；`internal-core.yaml` 对私有5个操作单独声明服务认证和命令模型。普通业务成功响应不再使用通用 `data: object`。仅事件内容、审计快照与错误明细允许动态JSON。

公开契约校准注销204、新增座位201、通知重试202及各接口真实query；重置密码按技术方案修为新请求204。历史幂等记录保留原响应，所以旧版本已成功的密码重置200仍允许原样重放。房间列表新增基线规定的departmentId数据库条件；版本校验拒绝小数、负数、溢出或非数字格式，不再截断后进行业务写入。

## 真实复现与修复

- 修复前的隔离HTTP：`expectedVersion:0.9` 取消预约实际200；重置密码实际200，与预期400/204分别产生严格断言失败。
- 院系过滤用例首轮fixture误写不存在的HUM代码而提前失败，已改为种子的ART；另在原生HTTP只读复现指定CS后返回20条，其中19条院系不符，确认过滤确实被忽略。
- 最小修复后首轮4/4通过；随后增加私有接口用例。内部授权用真实时间重建调用方校验时间，首跑与2031业务测试时钟不一致，产生INVALID_SESSION；该用例改用真实时间，其他业务时间边界仍采用独立可控Clock。未改变业务断言。

## 套件与范围

`python3 tests/integration/run_core_contract.py` 在独立 `studyroom_core_contract` PostgreSQL 启动真实随机端口HTTP服务。最终5/5通过，无跳过，测试本体46.05秒，Maven总计60秒。

| 用例 | 断言 |
| --- | --- |
| roomDepartmentFilterAppliesBeforePageAndTotal | 院系过滤同时影响total与分页，结合楼栋条件，排除其他院系与公共房间 |
| fractionalVersionCannotMutateReservation | 小数版本400 INVALID_VERSION，预约逐字段及有效claim不变 |
| passwordResetHasNoContentAndReplayDoesNotInvalidateFreshSession | 新请求204空响应，原cookie401；同键重放204且authVersion不再次变化，新会话仍有效 |
| publicReadAndBusinessResponseShapesMatchTheContract | 实际管理、空间、可用性、M1创建/取消、M2两级审批/撤销、通知读/重试、统计、审计等响应逐字段检查，含必填、类型、枚举、nullable、格式及未声明字段 |
| privateFiveOperationsMatchTheirOwnContractAndDeduplicateCommands | 真实机器Basic调用5类内部操作；授权允许/会话失效结果，维修人员分页；BLOCK/RELEASE响应与原命令重放；通知同命令只1记录、变payload409 |

Umi生成成功，全部本地引用解析成功，TypeScript检查与6项前端请求层测试通过。页面未改动。`ContractAssertions` 明确仅验证本项目使用的schema特性，不冒充通用OpenAPI验证器。上述测试覆盖代表性响应和业务语义，不声称56个操作的全部权限、错误与输入组合均已执行。

Java Controller 的请求Map仍需在后续独立checkpoint迁移为typed DTO；DTO改造须保留原请求JSON及省略/null差异的幂等哈希。当前已完成契约定义与三项协议行为修复，不以生成可编译代替后续整改。
