# ADR 0001：模块与公共层所有权

日期：2026-10-22。状态：已实施（2026-10-23 核对）。

采用技术方案指定的单仓库、双 Java 服务、静态 Web 与 PostgreSQL。core 拥有 D/M1/M2 和所有资源 claim；repair 拥有 M3 工单与 outbox。共同安全模块只共享稳定会话身份，禁止跨 schema 业务查询。

本次自动化实现分工见 [team](../team.md)。核心后端 agent 负责身份、资源与审批；维修后端 agent 负责工单、HTTP 恢复；前端 agent 负责统一界面与客户端；主 agent 负责安装、GitLab DevOps、集成与文档。共享契约由服务作者和前端调用方交叉审查。数据库与部署由主 agent 独立检查。

课程六人角色是原始组织建议，本仓库不虚构六名真实参与者。当前 owner 为 GitLab 用户 `yangchunyu`；AI 代理的贡献与测试证据按真实执行记录保存。

当前实现见[架构指南](../architecture/README.md)，验证范围见[需求追踪](../requirements-traceability.md)和[发布记录](../evidence/gitlab-release-v0.1.0.md)。历史结果不因更新本决策状态而改写。
