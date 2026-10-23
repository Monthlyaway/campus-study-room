# 账号、授权与基础资料

这页面向修改 D 模块的开发者，覆盖 REQ-D-01 至 D-05 及相关审计。管理人员操作见[用户手册](../user-guide/README.md)；环境、角色样例与密码来源见[演示账号](../development/demo-accounts.md)。

## 实现入口

[CoreController](../../services/core-service/src/main/java/com/campus/studyroom/core/CoreController.java)提供入口，[AdminService](../../services/core-service/src/main/java/com/campus/studyroom/core/AdminService.java)处理管理事务，[AccessService](../../services/core-service/src/main/java/com/campus/studyroom/core/AccessService.java)复核身份与权限，[AdminViews](../../services/core-service/src/main/java/com/campus/studyroom/core/AdminViews.java)构造公开投影，[persistence](../../services/core-service/src/main/java/com/campus/studyroom/core/persistence)保存用户、绑定、空间、配置与版本。公开字段以 [core.yaml](../../contracts/openapi/core.yaml)为准。

实体写入使用 Spring Data JPA，列表在 PostgreSQL 完成过滤/分页。advisory lock 用于串行化管理员成员关系变更，不是通用 CRUD 锁。公开 DTO 显式构造，不返回密码散列。

## 授权需要同一角色绑定

每个角色绑定独立拥有范围。所需权限与匹配范围必须来自**同一个启用角色绑定**，不能拼接角色 A 的权限与角色 B 的范围。

| 范围 | 语义 |
| --- | --- |
| SELF | 个人范围，不能授予教室管理权 |
| ROOM | 指定教室 |
| DEPARTMENT | 指定院系 |
| SHARED | 公共教室 |
| ALL | 全部范围 |

创建教室时，以待建教室的院系或公共归属判断范围。每次受保护请求检查账号启用、持久 authVersion 和 8 小时绝对会话期限；用户、密码、角色绑定、角色权限变化使相关旧会话失效。`/auth/me` 返回当前权限、启用绑定范围和 `bookingLimits`，UI 据此展示，创建时服务端仍读取数据库配置。

账号/绑定/版本和审计同事务。最后一个启用系统管理员不能被停用或移除关键角色，SYSTEM_ADMIN 角色必须保留账号/权限管理能力。系统管理员不会自动拥有两级审批、开修或验收资格；这些资格需按业务角色单独验证。

## 空间与系统配置

院系、校区、楼栋、教室、座位各有实体和版本。前三类写入要求 `user:manage`；教室/座位写入要求对应 `room:manage` 范围。当前前端空间页入口另要求 `room:manage`，因此维护上级主数据需同时满足页面入口与API写权限。上级须启用；已有资源不能直接更换归属。停用房间/座位或修改开放时间，先锁房间，再拒绝与有效资源占用冲突的变更。停用院系、校区、楼栋还检查启用下级引用。创建子资源与停用父资源必须正确互斥。

开放规则为每周整数小时且区间不重叠，更新时锁房间并增加房间版本。系统配置以总版本锁批量更新，M1/M2 最长时长均为 1–12 整数小时；重复 key 或任一非法项导致全批回滚。审计 beforeJson/afterJson 保存此次修改项的 `{key,value}` 数组、操作者和时间。已有订单保留原配置快照，不能追溯改变。

`GET /users?q=...&page=1&size=20` 对用户名/显示名作不区分大小写的包含查询，转义 SQL LIKE 通配符；total 与页面使用同一条件。教室查询按授权范围、楼栋、院系过滤，座位查询先确认房间可见性。房间本页关联名称/座位数量和角色本页权限采用批量读取，避免 N+1；空页不额外查询关联。

状态命令 `expectedVersion` 必须是非负整数，类型错误 400，过期版本 409。新密码重置成功为 204 空响应；同 key 重放不会再次改密码或使新会话失效。旧版本已保存的 200 幂等响应仍按原结果重放，不重写历史。

<a id="迁移与验证"></a>

## 如何修改和验证

迁移只能新增；例如 [SELF 范围扩展](../../db/migration/core/V20261022130000__self_role_scope.sql)扩展约束且保留既有 SHARED 数据。先迁移后启动新应用，开发步骤见[迁移指南](../development/backend.md)。接口变更同步 OpenAPI、输入/输出、生成客户端和行为测试。

按[测试矩阵](../development/testing.md)运行 AdminPersistence，覆盖同绑定范围、SELF 持久化/会话失效、最后管理员保护、父子资源并发、开放规则父版本、配置全批原子性和分页 SQL 数量。`admin_http.py` 进一步验证公开响应不泄漏密码散列、旧版本 409 和两个服务共享会话失效；这些入口的前置和数据副作用不同，不能用普通 Maven 的条件跳过代替真实 PG。

权限修改后旧浏览器返回 401 通常是预期失效，重新登录；403 看同绑定范围，404 看对象可见性，409 刷新版本和当前资源占用。更详细步骤见[排障](observability.md)。历史行为与性能证据分别见[矩阵](../evidence/core/acceptance-matrix.md)、[输入边界](../evidence/core/typed-inputs.md)、[性能](../evidence/performance.md)，本页不维护随运行变化的历史测试计数。
