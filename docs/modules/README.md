# 模块参考索引

[文档首页](../README.md) · [架构导读](../architecture/README.md) · [开发导航](../development/README.md)

这里保留准确的实现和运行细节。第一次接手请先读对应指南；普通用户需要的是[页面操作说明](../user-guide/pages.md)。

| 参考 | 适合查什么 | 先读 |
| --- | --- | --- |
| [核心预约与审批](core.md) | 资源裁决、锁序、时间、签到、审批事务 | [后端开发](../development/backend.md) |
| [账号与主数据](administration.md) | 账号/角色范围、配置、空间的服务端约束 | [安全架构](../architecture/security.md) |
| [共享能力](shared-services.md) | 会话、幂等、通知、期限任务、发布排空 | [数据一致性](../architecture/data-consistency.md) |
| [维修模块](repair.md) | 状态机、outbox、core HTTP、重试和租约 | [后端开发](../development/backend.md) |
| [前端模块](web.md) | 页面权限、请求规则、当前界面实现 | [前端开发](../development/frontend.md) |
| [请求关联与日志](observability.md) | requestId、JSON 日志、敏感字段边界 | [运行排障](../operations/runbook.md) |
| [环境与运维](operations.md) | 环境、状态目录、配置、CI/Runner | [运维导航](../operations/README.md) |
| [Ubuntu 环境引导](bootstrap.md) | 运行依赖安装、输入清单、验证和边界 | [安装指南](../operations/installation.md) |
| [离线发布与导入](portable-release.md) | 导出格式、校验、首次导入与迁移 | [安装指南](../operations/installation.md) |
| [发布、备份与回退](release-operations.md) | 整套发布、兼容门禁、备份调度、失败恢复 | [运维手册](../operations/README.md) |

API 的字段定义集中在 [OpenAPI 契约](../../contracts/openapi/README.md)，不在上述每页复制一份。历史通过/失败和性能结果统一从[验收证据](../evidence/README.md)进入。
