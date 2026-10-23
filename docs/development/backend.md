# 后端开发

这条路线帮助你从本机启动走到一次可验证的功能修改。先按[本机开发环境](local-setup.md)准备 Java 21、Node、Docker 和仓库依赖；账号与密码位置只在[演示账号](demo-accounts.md)维护。下面所有命令从仓库根执行，目标是你自己的原生 local 环境。

## 先认识运行中的组件

| 组件 | 代码入口 | 负责什么 | 数据边界 |
| --- | --- | --- | --- |
| core-service，默认 8081 | [core 包](../../services/core-service/src/main/java/com/campus/studyroom/core) | 身份、管理、座位预约、整室申请、唯一资源裁决、通知 | `core` 与共享 `session` |
| repair-service，默认 8082 | [repair 包](../../services/repair-service/src/main/java/com/campus/studyroom/repair) | 报修、派单、维修、验收、持久化 outbox | `repair` 与共享 `session`；不能查询 core 表 |
| api-contracts | [共享协议模型](../../modules/api-contracts/src/main/java/com/campus/studyroom/api) | 响应/错误/分页、M1/M2 类型化命令；不放业务服务 | 无数据库连接 |
| security-common | [共享安全与日志](../../modules/security-common/src/main/java/com/campus/studyroom/security) | 稳定的会话身份、请求关联、结构化日志与请求计数 | 不持有跨模块业务仓储 |
| migration-runner | [迁移程序](../../tools/migration-runner/src/main/java/com/campus/studyroom/migration/MigrationRunner.java) | 一次性迁移、参考数据、开发演示数据、生产初始管理员 | 独立迁移账号；运行服务不能 DDL |
| web | [前端](../../apps/web) | 浏览器界面；开发代理或发行版 Nginx 转发同源 API | 不直连数据库 |

组件关系与请求流程见[架构](../architecture/README.md)。维修服务通过真实 HTTP 向 core 校验当前授权、查询房间引用、发送资源命令和通知；共享会话不等于共享业务数据库。

## 第一次开发与修改后的重启

按[本机启动步骤](local-setup.md)运行 `./scripts/dev-up local`，在另一终端运行前端。成功信号是两个后端 readiness 返回成功，浏览器能登录，`/api/v1/version` 与 `/api/v1/repairs/version` 可读。不要把当前交付实例当成默认开发或测试靶标。

`dev-up` 会复用已经确认属于本工作区的存活进程；修改 Java 后，使用以下命令重新构建并重启原生服务：

```sh
python3 scripts/studyroom.py native-restart local
```

在 IDE 中导入根 [pom.xml](../../pom.xml)，以 Maven 模块组织源码和测试。优先用下面的单元/数据库测试调试具体方法；项目 CLI 没有提供 JDWP 参数。需要进程级断点时，应为自己的独立 IDE 启动配置注入与 local 一致的配置和秘密，并避开已使用端口，不要把密码保存为可提交的 IDE 配置。

原生日志默认在 `.local/local/core.log` 和 `.local/local/repair.log`；自定义状态目录时随之变化。定位过程见[运行日志与排障](../modules/observability.md)。

## 从需求找到要改的代码

| 任务 | 纵向阅读路径 | 应关注的测试 |
| --- | --- | --- |
| 创建/取消座位预约 | [CoreController](../../services/core-service/src/main/java/com/campus/studyroom/core/CoreController.java) → [command DTO](../../modules/api-contracts/src/main/java/com/campus/studyroom/api/command) → [BookingService](../../services/core-service/src/main/java/com/campus/studyroom/core/BookingService.java) → [ResourceService](../../services/core-service/src/main/java/com/campus/studyroom/core/ResourceService.java) → [persistence](../../services/core-service/src/main/java/com/campus/studyroom/core/persistence) | CorePersistence、CoreAcceptanceMatrix、CoreInputBoundary、core_http |
| 第二级批准整室申请 | CoreController → [ApplicationService](../../services/core-service/src/main/java/com/campus/studyroom/core/ApplicationService.java) → ResourceService → 同事务审批步骤/资源占用/审计 | CorePersistence 的资源冲突与回滚、CoreAcceptanceMatrix、core_http |
| 开修/验收后的资源同步 | [RepairController](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairController.java) → [RepairService](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairService.java) → [RepairWorker](../../services/repair-service/src/main/java/com/campus/studyroom/repair/RepairWorker.java) → [CoreClient](../../services/repair-service/src/main/java/com/campus/studyroom/repair/CoreClient.java) → core ResourceService | RepairPersistence、repair_http、网络故障矩阵 |
| 用户、角色或范围 | CoreController → [AdminService](../../services/core-service/src/main/java/com/campus/studyroom/core/AdminService.java) / [AccessService](../../services/core-service/src/main/java/com/campus/studyroom/core/AccessService.java) | AdminPersistence、admin_http、跨服务会话失效 |
| 查询过滤/分页 | [QueryService](../../services/core-service/src/main/java/com/campus/studyroom/core/QueryService.java)、[ReadQueries](../../services/core-service/src/main/java/com/campus/studyroom/core/ReadQueries.java)、[AvailabilityReadRepository](../../services/core-service/src/main/java/com/campus/studyroom/core/AvailabilityReadRepository.java) | 过滤后 total、越权、SQL 数量与真实 PG 结果 |

先看对应[模块参考](../modules/README.md)的不变量，再修改实现。写操作在事务内复核权限、状态、版本和资源，不能依赖前端按钮或先前的可用性查询。列表必须在数据库完成权限过滤、计数和分页，不将 Entity 直接暴露成 API。

## 改一个接口的工作顺序

1. 在 [PRD 与规格对照](../reference/README.md)找到需求与业务边界；确认是新业务意图、既有操作还是内部命令。
2. 修改 [OpenAPI 真值](../../contracts/openapi/README.md)，写清参数、响应、状态码、权限与兼容影响。同步 Controller、输入解析/DTO、服务和响应映射；M1/M2 的原始 JSON 快照必须保留给幂等哈希。
3. 有 schema 变化时按下一节新增迁移；状态、审计、通知和资源效果必须保持事务关系，跨服务 HTTP 与 SMTP 放在事务外。
4. 补充能证明行为的测试：成功路径、权限/范围、非法输入、版本/状态冲突；涉及资源时还要验证并发和回滚。按[测试指南](testing.md)选择真实 PG/HTTP 覆盖。
5. 生成客户端并核对调用方，命令见[API 指南](api.md)。生成成功只证明工具能处理契约，不能替代后端行为测试。
6. 更新受影响模块和用户操作说明，执行相应门禁，按[贡献指南](../contributing.md)准备提交。

## 修改数据库结构与种子

迁移源在 [db/migration](../../db/migration)，按 `session → core → repair` 执行，各 schema 有自己的 Flyway 历史。服务启动使用 Hibernate `validate`，Flyway 在服务中关闭。迁移角色拥有 DDL，core/repair 运行角色仅获各自业务 schema 与 session 所需权限；审计表禁止运行账号更新和删除。

新增文件采用 `V<时间戳>__<含义>.sql`，放在拥有该数据的 schema 目录。已部署迁移不能重写、改校验和或通过删除历史表绕过检查。优先做兼容扩展；例如新增可空字段并为旧数据提供读取回退。源码中的 [MigrationRunner](../../tools/migration-runner/src/main/java/com/campus/studyroom/migration/MigrationRunner.java)维护种子，没有单独的 `db/seed` 目录。

| 动作 | 作用 | 使用边界 |
| --- | --- | --- |
| `migrate` | 应用各 schema 的增量迁移 | 改结构后先于新应用启动 |
| `reference` | 幂等建立权限、角色等参考数据 | 不用于重置管理员自定义配置 |
| `demo` | 非生产演示用户、空间、开放时间 | 不创建成功业务订单，也不重置已修改的演示密码 |
| `bootstrap` | 生产首次管理员初始化 | 只在固定镜像首次 prod deploy 的环境锁内执行；从秘密文件读取随机密码，升级不重设账号。生产流程见[GitHub 手册](../operations/github.md#首次准备生产状态) |

本地新增迁移后可直接执行 `native-restart local`，它会构建、迁移并启动。仅需检查迁移/种子时，以下命令会写入你自己的 local 数据库，前置是 local PostgreSQL 已运行：

```sh
./mvnw -B -ntp -pl tools/migration-runner -am -DskipTests package
./scripts/migrate local
./scripts/seed local reference
```

提交前至少验证全新库迁移和受影响的真实 PG suite；升级交付还需验证已有数据及旧应用读取新 schema。具体兼容、备份和回退流程见[运维手册](../operations/runbook.md)，回退应用不会自动降级数据库。

## 完成一次后端修改的检查

- 能用 [API 会话示例](api.md)解释 Cookie、登录后 CSRF 更新、幂等键与 `expectedVersion`。
- 选择的测试真正执行了预期 suite；单纯 Maven 绿色不能证明条件启用的 PG 测试已运行。
- 资源、审计、通知和任务的失败回滚边界有验证；跨服务重试不更换命令身份。
- 失败可通过响应 `requestId` 定位，无须输出密码、Cookie、请求正文或完整数据库记录。

下一步：[API 调用与契约](api.md) · [测试选择](testing.md) · [数据一致性](../architecture/data-consistency.md)。
