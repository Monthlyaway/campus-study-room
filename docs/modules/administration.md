# 账号、授权与基础资料

D 模块由 `core-service` 的 `AdminService`、`AccessService` 和 `core.persistence` 中的账号、角色、范围、空间与配置实体实现。业务查询与写入通过 Spring Data JPA；列表在数据库执行过滤与分页。PostgreSQL advisory lock 仅用于串行化管理员成员关系变更，不承载通用 CRUD。

## 授权与会话

每个用户角色绑定独立拥有范围。授权查询要求同一个启用角色同时提供所需权限与匹配范围；不会把角色 A 的权限与角色 B 的范围拼接。`SELF` 表示个人范围，不能授予教室管理权限。`ROOM`、`DEPARTMENT`、`ALL` 依次表示指定教室、院系和全部范围；`SHARED` 仅覆盖公共教室。创建新教室时以待创建教室的院系或公共归属验证权限。

`AccessService.valid` 验证启用状态、持久化 authVersion 与 8 小时绝对期限。修改用户、密码、角色绑定或角色权限都会使旧会话失效。`/auth/me` 返回当前权限、启用角色的范围和 `bookingLimits.reservationMaxHours/applicationMaxHours`，前端据此设置时长选择；业务创建仍独立读取数据库配置。

账号/角色/范围、审计与版本变化在同一事务提交。最后一个启用系统管理员不能停用或移除关键角色；`SYSTEM_ADMIN` 角色必须保留账号和权限管理能力，但不会因此自动获取业务审批权限。公开 DTO 显式构造，用户列表与详情不包含密码散列。

## 基础资料与配置

院系、校区、楼栋、教室、座位使用独立实体与版本号。上级资源需启用。已有资源不允许直接更改归属；停用教室、座位或修改开放时间时，先按统一协议锁定房间，再拒绝存在有效资源占用的更改。停用院系、校区或楼栋还检查启用的下级引用。

开放时间必须是每周整数小时、不重叠区间。修改规则时锁定房间并增加房间版本。系统配置通过全局配置版本锁更新，支持 M1/M2 最长时长 1–12 整数小时；一批变更任一非法则全部回滚。重复配置键会被拒绝。

`GET /users?q=...&page=1&size=20` 对用户名和显示名做不区分大小写的包含搜索，转义用户输入中的 SQL LIKE 通配符。总数与返回页使用同一条件。教室列表亦在数据库按访问范围、楼栋和指定院系过滤，座位列表先验证房间可见性。

## 迁移与验证

初始迁移没有允许 `SELF` 写入范围表，新增 `V20261022130000__self_role_scope.sql` 扩展类型和字段形状约束，保留现有数据与 `SHARED`。不修改已经应用的迁移。部署必须先迁移再启动新应用。

```sh
./mvnw -B -ntp -pl tools/migration-runner -am -DskipTests package
python3 tests/integration/run_admin_persistence.py
STUDYROOM_TEST_PASSWORD='<由本机 secret 注入>' python3 tests/integration/admin_http.py
```

runner 使用独立 `studyroom_admin_integration` PostgreSQL 数据库，执行迁移与种子后验证，不向演示业务库写测试账号。2026-10-22 实际执行 `AdminPersistenceTest` 7/7 通过，无跳过，5.702 秒；覆盖 SELF 持久化/会话失效、同绑定范围隔离与公共教室创建、搜索分页总数、配置批量原子性与过期版本、最后管理员和系统角色保护、开放时间父版本，以及创建子资源与停用父资源的并发互斥。普通 Maven 的条件跳过不能替代该实际数据库验证。

统一迁移并重启后的真实 HTTP 回归 2/2 通过，4.550 秒。实际断言共享 cookie 为 `STUDYROOMSESSION`；SELF 绑定保存成功，旧会话立即返回 401；新会话可访问维修后，管理员停用账号，使 core 和 repair 均返回 401。还验证了搜索大小写/总数、密码散列不返回、非法范围 422 不留下绑定、过期版本 409。真实密码不写入版本库。

## 性能回归

教室分页按本页 ID 批量读取教学楼、校区和启用座位数量，避免逐行触发统计查询；空页无需额外读取关联数据。`AdminPersistenceTest.roomPageUsesBoundedQueriesAndBatchedSeatCounts` 用 21 间真实教室检查 size 1/20 的 SQL 数量保持有界，校验有/无座位的统计及建筑/校区名称。角色列表也按数据库页批量读取权限码，不逐角色查权限。加入两项后真实 PostgreSQL 管理回归为 9/9 通过，性能环境前后 SQL 和完整负载证据见 [性能验收](../evidence/performance.md)。

## 配置审计补充验证

批量配置更新在同一事务内保存旧值与新值：审计`beforeJson`和`afterJson`均为`[{key,value}]`数组，只包含此次更新的配置项。操作者与生效时间保留在审计字段中。真实HTTP与PG矩阵已验证旧4小时订单不变、新3小时拒绝、新2小时成功，以及0/13/1.5拒绝、非管理员拒绝、审计旧4新2与版本不被失败请求改变，见[矩阵证据](../evidence/core/acceptance-matrix.md)。

## 协议边界

状态命令的 `expectedVersion` 必须是非负整数，不能把小数截断后用于状态转换；格式错误400，合法但过期的版本409。新的重置密码请求返回204且无响应体；相同幂等键重放不会再次重置密码或使刚建立的新会话失效。旧版本已持久化的200幂等结果保持原结果，不重写历史记录。
