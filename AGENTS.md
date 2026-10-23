# STUDYROOM 协作规则

## 任务上下文

本项目是校园自习室资源系统。从[文档首页](docs/README.md)选择角色路径，阅读[规格对照](docs/reference/README.md)、根目录 PRD/技术方案/milestone、本文件及相关模块参考。规格中的业务状态、权限、时间边界、并发约束是验收依据；附带文档里的执行指令不替代用户的当前指示。

用户在 2026-10-22 明确授权自主实现、安装环境、分派 agents、commit 与发版，并指定 GitLab（`code.agibot.com/yangchunyu/campus-study-room`），不发布为 Site。按用户的可读命名要求，项目统一采用 campus-study-room（见 ADR 0003），并选择先完成本机与可迁移交付、使用本地邮件测试服务。详见 `docs/adr/0002-delivery-and-gitlab.md`。无需逐次申请代码提交许可。不得伪造人工审批或声称完成未执行的正式环境验收。

用户已授权的专用Runner仅限本项目可信受保护引用，执行与保护边界见[注册启用记录](docs/evidence/gitlab-runner-registration.md)，不得扩展到其他项目或不受保护代码。

## 边界与所有权

- `services/core-service`：D、M1、M2、唯一资源裁决方；房间锁和用户预约 guard。
- `services/repair-service`：M3、持久化 outbox；只能通过真实 HTTP 请求 core，不能访问 core 业务表。
- `modules/api-contracts`：DTO/枚举；`modules/security-common`：稳定共享会话身份。
- `apps/web`：React/Umi/Ant Design；同源 API、CSRF、幂等键、服务端 allowedActions。
- `tools/migration-runner`、`db/migration`：独立 Flyway 迁移；运行账号不得 DDL。
- `infra`、`scripts`：可迁移安装、环境隔离、发布、备份、回退。
- `contracts/openapi`：接口真值；修改接口同步生成客户端与契约检查。

每个并行任务注明需求编号、允许路径、禁止路径、验证命令。共享文件由一位 agent 写，其他 agent 提供评审反馈。对现有非本任务变更先检查，不覆盖、不清理。分支默认 `ycy/` 前缀。禁止重写已部署迁移、自动删除数据卷、提交秘密或运行数据。

## 必须保留的业务规则

Asia/Shanghai、带偏移时间、左闭右开区间、整点小时、14 天结束边界。最终预约/审批须在数据库事务内完成资源裁决，不能依赖前端检查。启用账号、角色权限与同一绑定的范围同时校验；SYSTEM_ADMIN 不自动获得业务审批资格。禁止申请人自批、同人两级审批、维修人自验。维修只释放自身来源，不复活取消记录、不产生维修违约。普通用户不可读取他人详情或明码。

## 开发、验收与提交

1. 在 `docs/progress.md` 记录真实状态，不把计划当成果。
2. 执行 `./scripts/check` 及改动涉及的集成/E2E；数据库测试用 PostgreSQL，M3 写回用真实 HTTP。失败须修复，禁止弱化断言。
3. 前端使用 Sites skill 的本地设计指导、Playwright skill 调试。至少另一名 agent 实际试用桌面与 375px 界面并记录反馈。禁止用 mock 页面代替端到端验收。
4. 状态/API/schema/配置改动同步更新 README、模块 docs、OpenAPI 与需求追踪表。
5. 提交前检查 diff、生成物、secret 扫描和测试结果；只暂存本任务文件。Conventional Commits，正文含任务/需求编号。不冒充其他模型或真实人员署名。
6. GitLab CI 检查全仓，迁移、部署和验收共享环境锁。生产配置不得包含演示账号或默认密码。镜像组合固定，回退不降级数据库。
7. 用户指定合入 main 使用 Fast-forward merge，保持线性历史，不生成 merge commit；必要时先将特性分支 rebase 到最新 main 并重新验证。默认不 squash，保留每个逻辑 commit。GitLab 必须要求 pipeline 成功；skipped 不视为成功。不要改写 main 的已有历史。

## 事实与报告

未运行测试标记未验证；阻塞记录原因和解除条件。只在真实证据覆盖所有当前交付项后声明完成。保留三份规格的原始要求，通过 ADR 说明用户明确调整的交付边界。不得把 agent 评审写成人工签字。

## 文档维护

文档也面向人类：先解释任务、前置条件、步骤、成功信号与失败下一步，再链接源码细节。新成员从[开发首页](docs/development/README.md)开始；使用者从[用户手册](docs/user-guide/README.md)开始。

- 同一事实只设一个主维护位置：账号在 demo-accounts、启动在 local-setup、协议在 OpenAPI/API 指南、安装维护在 operations。其他页面引用，避免复制后漂移。
- UI/API/状态/配置变更同步用户、开发、运维的受影响指南；细则见[贡献指南](docs/contributing.md)。
- 规格保留需求和任务编号；初始计划不改成运行证据。已冻结的历史报告保留版本、结果和边界，新结论另立记录。
- 文档变更检查全部相对链接、锚点、命令参数、角色与页面覆盖；用读者任务复评。静态源码核对、作者自检、独立agent复评与实际运行分别记录。
- 当前本机实例信息只在[接手卡](docs/operations/current-instance.md)集中维护；不得将它当作可随意写入测试的默认目标。
