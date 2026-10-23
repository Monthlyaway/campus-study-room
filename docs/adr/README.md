# 架构与交付决策

[文档首页](../README.md) · [当前架构](../architecture/README.md) · [规格对照](../reference/README.md)

ADR 解释已经确定的取舍和适用边界。操作步骤放在开发/运维指南，验证结果放在证据目录；更新状态不改写决策当时的事实。

| 决策 | 日期 | 当前含义 |
| --- | --- | --- |
| [0001 模块与公共层所有权](0001-module-and-shared-layer-ownership.md) | 2026-10-22 | 已实施；双服务、共享身份、core资源所有权保持 |
| [0002 GitLab与交付边界](0002-delivery-and-gitlab.md) | 2026-10-22 | 用户明确决策；本机/可迁移、Mailpit、自主提交发版，正式目标另验收 |
| [0003 可理解命名](0003-readable-project-naming.md) | 2026-10-22 | 已实施；源码与项目使用 campus-study-room，历史保留 |

新增实质决策按顺序编号，写明问题、依据、选择、代价、影响和验证入口。用户指令、实现事实、agent建议应清楚区分；AI评审不是人工批准。
