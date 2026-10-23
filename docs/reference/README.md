# 规格与实现对照

[文档首页](../README.md) · [当前架构](../architecture/README.md) · [需求验收](../requirements-traceability.md) · [决策记录](../adr/README.md)

根目录三份规格是 2026-10-22 的产品和实施基线，保留需求编号、任务编号、业务约束与初始计划。它们不是当前操作手册，也不是当前完成状态。使用系统从[用户手册](../user-guide/README.md)开始，开发从[开发指南](../development/README.md)开始，安装从[运维入口](../operations/README.md)开始。

## 怎么读三份规格

| 文件 | 先读哪些内容 | 解决什么问题 |
| --- | --- | --- |
| [PRD](../../01_PRD.md) | §3角色范围、§4全局规则，随后§5～9相关业务；§10界面、§11～12验收 | 必须实现什么结果；39项P0仍是验收依据 |
| [技术方案](../../02_Technical_Design.md) | §2边界、§3身份、§5事务、§6～7接口一致性，再读相关章节 | 初始设计为何如此；实际类、命令、机制见下表与当前架构 |
| [里程碑](../../03_Development_Milestones.md) | §3状态与证据、§4依赖、目标阶段任务、§14～15门禁 | 任务编号与初始推进顺序；不能把初始 NOT_STARTED 当成今天进度 |

业务与验收标准继续有效。历史 GitLab/本机交付调整由 [ADR 0002](../adr/0002-delivery-and-gitlab.md)记录；当前 GitHub、codex-lb 远程开发与同机 CI/生产、用户自主部署授权由 [ADR 0004](../adr/0004-github-codex-lb-production.md)记录；当前进度在 [progress.md](../progress.md)，逐项证据在[追踪表](../requirements-traceability.md)。发现新的实质性冲突须记录需求影响并处理，不能通过更新这张表自行降低标准。

## 初始设计与当前落点

下面区分用户决策、实现细化与尚未交付范围。该对照帮助读者定位真实代码，不把规划路径当成已存在功能。

| 基线内容 | 当前实现 / 适用说明 | 可核对入口 |
| --- | --- | --- |
| GitHub Actions、`.github/workflows/`、PR | 当前恢复使用 GitHub；codex-lb 专用 runner，PR rebase merge、默认不 squash；GitLab 保留历史 | [ADR 0004](../adr/0004-github-codex-lb-production.md)、[工作流](../operations/github.md) |
| 技术方案“尚未实施”、里程碑初始 NOT_STARTED | 描述基线创建时刻；P0交付状态独立维护，不改写初始计划 | [当前进度](../progress.md) |
| 六人课程角色和人工签字流程 | 实际为 owner 与分工 AI agents；用户授权自主开发、提交、发版，不虚构六名真人或人工签字 | [实际分工](../team.md)、[协作规则](../../AGENTS.md) |
| 初始会话 Cookie 名 `PJSESSION` | 当前 `STUDYROOMSESSION`，共享稳定 Principal，重命名不重写历史 | [命名 ADR](../adr/0003-readable-project-naming.md)、[SecurityConfiguration](../../services/core-service/src/main/java/com/campus/studyroom/core/SecurityConfiguration.java) |
| 规划的 `modules/test-support`、`db/seed` | 没有独立这些目录；测试夹具位于各服务测试及 tests，种子由 MigrationRunner 管理 | [测试指南](../development/testing.md)、[MigrationRunner](../../tools/migration-runner/src/main/java/com/campus/studyroom/migration/MigrationRunner.java) |
| 规划的 `tests/e2e`、`tests/load` | 可重复浏览器套件在 `tests/browser`，负载工具在 `tests/performance` | [浏览器说明](../../tests/browser/README.md)、[性能说明](../../tests/performance/README.md) |
| 初始前端页面目录示意 | `.umirc.ts` 单一通配入口，App 手动分发，管理视图集中 Admin.tsx | [前端路由表](../development/frontend.md) |
| 初始范围列举 | 当前后端为 SELF、DEPARTMENT、ROOM、SHARED、ALL；SHARED在教室维度表示公共教室，不匹配按申请人院系裁决的一级审批；必须与权限处于同一绑定 | [安全指南](../architecture/security.md)、[AccessService](../../services/core-service/src/main/java/com/campus/studyroom/core/AccessService.java) |
| 初始幂等并发算法描述 | core 在事务内使用 PostgreSQL advisory lock 后读取/写入幂等记录；repair 有独立本地实现，不能一概而论 | [一致性指南](../architecture/data-consistency.md)、[Idempotency](../../services/core-service/src/main/java/com/campus/studyroom/core/Idempotency.java) |
| 所有请求 DTO/客户端统一迁移的设计目标 | M1/M2 已有不可变请求与原JSON body语义；其余接口仍有现有 DTO/Map，前端仍有直接 api 调用 | [后端指南](../development/backend.md)、[契约说明](../../contracts/openapi/README.md) |
| registry digest 推广与正式服务器上线 | 当前目标为 GHCR 和 codex-lb 生产；同一 test 候选按 digest 推广，TLS、备份与实际运行分别验收；外部 SMTP 尚未提供 | [GitHub 发布](../operations/github.md)、[当前实例](../operations/current-instance.md) |
| 4 核/8 GiB 与独立 CI 主机 | 用户改为 codex-lb 2 核/约 2 GiB 与同机 CI；采用串行与资源预算，不将旧性能结果写成新主机容量验证 | [ADR 0004](../adr/0004-github-codex-lb-production.md) |
| P1 自然语言 Agent | 可选后续阶段，未启动，不属于已完成P0功能 | [进度](../progress.md) |

架构不变量仍是两个独立后端、core唯一资源裁决、repair真实HTTP写回、持久化outbox、数据库事务、独立迁移和最小权限。目录变化、实现细化不能被用来绕过这些要求。

## 修改规格时

保留 REQ/任务 ID 与原业务约束；先描述问题和受影响行为，再在 ADR 记录已作出的决策，并同步指南、代码、契约、测试和追踪表。历史验收只支持原版本和原条件；新实现不能靠改旧报告获得“已验证”状态。文档维护规则见[贡献指南](../contributing.md)。
