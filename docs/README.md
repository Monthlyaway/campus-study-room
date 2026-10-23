# 文档首页

[项目首页](../README.md)

从你的任务开始。日常使用不需要阅读架构和测试记录；开发者可以先建立运行环境，再按自己负责的部分深入。

## 五种阅读路线

| 读者 | 建议顺序 |
| --- | --- |
| 用户 | [用户手册](user-guide/README.md) → [常见工作流](user-guide/workflows.md) → [逐页操作](user-guide/pages.md) → [常见问题](user-guide/faq.md) |
| 架构工程师 | [当前架构](architecture/README.md) → [数据与一致性](architecture/data-consistency.md) → [身份与安全](architecture/security.md) → [ADR](adr/README.md) |
| 前端工程师 | [开发环境](development/local-setup.md) → [演示账号](development/demo-accounts.md) → [前端开发](development/frontend.md) → [测试选择](development/testing.md) |
| 后端工程师 | [开发环境](development/local-setup.md) → [后端开发](development/backend.md) → [API 指南](development/api.md) → [测试选择](development/testing.md) |
| 运维工程师 | [运维手册](operations/README.md) → [安装](operations/installation.md) → [运行与排障](operations/runbook.md) → [发布与回退参考](modules/release-operations.md) |

## 按问题查找

| 我遇到的问题 | 直接入口 |
| --- | --- |
| 如何完成预约签到、两级审批或报修验收？ | [用户工作流](user-guide/workflows.md) |
| 某个页面的按钮、字段和状态是什么意思？ | [全部页面说明](user-guide/pages.md) |
| 没收到邮件、结果未确认、权限不够怎么办？ | [用户常见问题](user-guide/faq.md)；维护者读[运维排障](operations/runbook.md) |
| 用哪个账号，密码从哪个环境找？ | [演示账号](development/demo-accounts.md) |
| API 如何认证，接口如何改，生成客户端放在哪？ | [API 指南](development/api.md)及[OpenAPI 参考](../contracts/openapi/README.md) |
| 三个应用、两个后端和数据库如何协作？ | [架构总览](architecture/README.md) |
| 为什么查询有空位，提交却冲突？维修为何还在同步？ | [数据与一致性](architecture/data-consistency.md) |
| 应该运行哪种测试，结果保存在哪？ | [测试指南](development/testing.md) |
| 如何把系统搬到另一台服务器？ | [安装选择](operations/installation.md)和[离线迁移参考](modules/portable-release.md) |
| 当前这台机器跑的是哪份包？ | [当前本机实例](operations/current-instance.md) |
| 如何评估一次修改、提交或文档变更？ | [贡献与文档维护](contributing.md) |

## 文档各自负责什么

| 类型 | 用途与依据 | 入口 |
| --- | --- | --- |
| 使用与开发指南 | 解释当前实现的操作步骤、成功信号和失败处理，事实应与源码一致 | 上方五种路线 |
| 模块参考 | 保存业务不变量、配置、协议、事务和模块级细节，避免在每份指南重复一遍 | [模块索引](modules/README.md) |
| 规格基线 | 定义原始业务要求、设计约束和任务门禁。计划中的路径/流程不自动代表当前实现 | [规格导读与现状对照](reference/README.md) |
| 架构决策 | 记录已经采纳的边界与用户调整，说明理由和影响 | [ADR 索引](adr/README.md) |
| 当前进度与需求追踪 | 回答当前交付范围和每条需求由什么证据支持 | [进度](progress.md)、[39 条 P0 需求](requirements-traceability.md) |
| 历史验收证据 | 固定日期、源码版本、命令与结果；不把旧通过/旧失败改写成今天的结果 | [验收索引](evidence/README.md) |

业务约束以 PRD 与已采纳 ADR 为依据；指南用于解释当前实现，不得借“更新文档”放宽权限、事务、时间边界或数据保护。发现实现与规格不一致时，应明确记录差异并评审，而不是静默选择容易实现的一方。命令、字段和路径以当前源码/契约为可核对依据；原始方案的具体映射见[规格导读](reference/README.md)。

## 维护与项目资料

- [贡献和文档维护规则](contributing.md)、[Agent 协作规则](../AGENTS.md)。
- [真实实施分工](team.md)、[伦理与风险记录](ethics-and-risks.md)、[固定依赖基线](dependency-baseline.json)。
- [五角色文档评审与整改记录](reviews/documentation-2026-10-23.md)。

本仓库直接维护 Markdown，链接可在 GitLab 和本地编辑器中使用。用户指南不依赖被忽略的 `output/` 目录；历史报告提及的原始日志可能只在本机或保留的 CI artifacts 中存在，应按报告标明的保存位置获取。
