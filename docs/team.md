# 实施与评审分工

[文档首页](README.md) · [贡献指南](contributing.md)

## 产品实现阶段

| 参与方 | 类型 | 本次责任 | 独立反馈 |
| --- | --- | --- | --- |
| yangchunyu | 用户 / 仓库 owner | 产品目标、交付范围授权 | 最终使用反馈 |
| root | AI agent | 集成、部署、GitLab、文档、发布 | 检查服务与前端交付 |
| core_backend | AI agent | D/M1/M2、共享模块、迁移、核心契约 | 评审维修契约 |
| repair_backend | AI agent | M3、outbox、跨服务 HTTP | 实际试用前端 |
| frontend | AI agent | React/Umi/Ant Design、交互与客户端 | 浏览器验证与截图 |

以上为真实本次分工，不代替课程要求的真实成员登记或正式人工验收签字。

## 2026-10-23 文档重构

用户要求五个角色独立评审：用户、架构工程师、前端工程师、后端工程师、运维工程师。对应AI代理分别先给建议，主代理统一设计导航和分配写入路径，再安排交叉核对；不是五名真人签字。初评范围、建议与整改结果见[五角色文档评审](reviews/documentation-2026-10-23.md)。

角色是读者视角，作者和复评者须分开记录。用户流程由架构代理依据用户角色初评实施，前端指南由主代理依据前端角色初评实施，不能将作者自检写成独立用户试用。

## 2026-10-23 GitHub 与 codex-lb 交付

| 参与方 | 类型 | 负责范围 | 验证边界 |
| --- | --- | --- | --- |
| Monthlyaway | 用户 / GitHub owner | 明确远程开发、同机 CI、生产上线、Release 与 PR 合并授权 | 真实用户授权，不代替未执行的验收 |
| root | AI agent | Actions、专用 runner、服务器配置、集成与发布、最终 PR | 实际执行结果单独记录 |
| production_runtime | AI agent | prod 配置、首次管理员、资源限制与回归 | 单测与实际启动区分 |
| github_release | AI agent | 容器验证、GHCR digest、GitHub Release 与生产推广 | 发布/部署是否真实成功看相应运行 |
| operations_docs | AI agent | 当前 ADR、操作与开发指南、历史边界及链接核验 | 文档静态核对不冒充部署验收 |

所有源码修改均在 codex-lb。AI agents 的互审不写作第二位人工审批；GitHub 套餐实际支持的保护能力以[工作流手册](operations/github.md)与新证据为准。
