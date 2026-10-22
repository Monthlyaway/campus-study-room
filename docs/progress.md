# 开发进度与验收索引

更新时间：2026-10-22。目标仍在执行，尚未声明 P0 全量完成。

| 阶段 | 状态 | 当前证据 / 后续门禁 |
| --- | --- | --- |
| MS0 工程底座 | IN_PROGRESS | 已读取基线、划分任务、确认 Java21/Node24/Docker、GitLab SSH 与页面权限；安装脚本/CI/迁移正在实现 |
| MS1 公共层 | IN_PROGRESS | core agent 实现身份、资源、通知、审计；待真实 PG 验收 |
| MS2 M1 预约 | IN_PROGRESS | 核心后端与前端并行；待集成 |
| MS3 M2 审批 | IN_PROGRESS | 核心后端与前端并行；待集成 |
| MS4 M3 维修 | IN_PROGRESS | repair agent 实现真实 HTTP 与持久化 outbox；待集成 |
| MS5 验收恢复 | NOT_STARTED | 全量行为、故障、并发、兼容、备份恢复验证 |
| MS6 交接发布 | NOT_STARTED | 独立 agent 使用安装文档与浏览器试用后冻结 |
| MS7 P1 Agent | NOT_STARTED | P0 优先 |

当前交付范围按 [ADR 0002](adr/0002-delivery-and-gitlab.md)。详细验收以 `docs/evidence/` 下实际日志摘要和需求追踪表为准。
