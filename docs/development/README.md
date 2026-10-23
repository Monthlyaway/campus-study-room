# 开发导航

[文档首页](../README.md) · [用户手册](../user-guide/README.md) · [架构导读](../architecture/README.md)

这组文档面向第一次接手项目的开发者。先跑通一种开发模式，再按前端或后端任务深入；不需要先读完发布验收日志。

## 第一次接手

1. 阅读[架构总览](../architecture/README.md)，认识三个应用、两个共享代码模块和数据库边界。
2. 按[开发环境](local-setup.md)连接 codex-lb，在服务器启动原生后端和前端，通过 SSH 转发核对地址与健康状态。
3. 从[演示账号](demo-accounts.md)选对角色，按[用户工作流](../user-guide/workflows.md)理解要开发的业务。
4. 前端读[前端开发](frontend.md)，后端读[后端开发](backend.md)；调用接口时使用[API 指南](api.md)。
5. 按[测试选择](testing.md)验证改动，再按[贡献流程](../contributing.md)提交。

## 按问题找答案

| 问题 | 文档 |
| --- | --- |
| 用容器安装还是用原生进程开发？端口和密码在哪？ | [开发模式与启动](local-setup.md) |
| 用哪个账号验证审批、维修、范围权限？ | [演示账号与样例数据](demo-accounts.md) |
| 路由在哪，如何增加页面或改请求？ | [前端开发](frontend.md) |
| 接口到 Service、Repository、迁移和测试怎么找？ | [后端开发](backend.md) |
| 如何登录 API，处理 CSRF、幂等、版本和错误？ | [API 指南](api.md) |
| Maven 通过是否代表数据库测试通过？ | [测试选择与结果](testing.md) |
| 发布失败或维修同步不结束怎么查？ | [运维排障](../operations/runbook.md)与[日志参考](../modules/observability.md) |
| 谁可以改共享模块，改完需同步哪些文档？ | [贡献与文档维护](../contributing.md)及[模块归属](../adr/0001-module-and-shared-layer-ownership.md) |

模块深层规则集中在[模块参考](../modules/README.md)。规格、当前说明和历史证据各自的用途见[文档首页](../README.md)，不要把历史测试次数或计划中的目录当作当前开发入口。
