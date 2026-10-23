# 栖学 · 校园自习室

预约自习座位、申请整间教室、处理设施报修的一套校园资源系统。学生可在手机上完成预约和签到，工作人员使用同一套系统审批、派单与管理空间。

**已经有账号和服务地址？从[用户手册](docs/user-guide/README.md)开始。** 手册先介绍常见工作流，再逐页说明按钮、状态和遇到问题时的处理办法。

## 按你的任务开始

| 我想做什么 | 从这里开始 | 能找到什么 |
| --- | --- | --- |
| 使用系统 | [用户手册](docs/user-guide/README.md) | 首次登录、预约签到、申请审批、报修验收、每个页面怎么用 |
| 理解系统设计 | [架构导读](docs/architecture/README.md) | 组件与部署、关键业务链路、数据和权限边界、设计取舍 |
| 开发后端 | [后端开发指南](docs/development/backend.md) | 组件和源码入口、调通 API、修改接口、数据库迁移与测试 |
| 开发前端 | [前端开发指南](docs/development/frontend.md) | 本地启动、路由与组件、请求和权限、样式、调试与测试 |
| 安装或维护系统 | [运维手册](docs/operations/README.md) | 源码/离线安装、环境配置、健康检查、升级、备份、回退和排障 |
| 查找规格或验收依据 | [文档总览](docs/README.md) | 产品需求、设计基线、ADR、模块参考、测试和发布证据 |

## 先试用当前本机实例

- 应用：[http://localhost:19282](http://localhost:19282)
- 本地测试邮件：[http://localhost:19227](http://localhost:19227)
- 学生可用 `student`，管理账号为 `admin`；完整角色和密码定位见[演示账号](docs/development/demo-accounts.md)。系统管理员不自动拥有业务审批权限。

以上地址指向这台开发机现有的 v0.1.0 验收实例，不是其他服务器的默认地址。其版本、状态目录与维护方式见[当前本机实例](docs/operations/current-instance.md)。使用已安装系统的用户无需安装开发工具。

## 新装一套本地环境

需要 Docker Engine/Desktop、Compose v2、Python 3.10+；源码获取还需要 Git 和仓库 SSH 读取权限。应用在固定容器中编译，推荐至少 4 vCPU / 8 GiB。

```sh
git clone git@code.agibot.com:yangchunyu/campus-study-room.git
cd campus-study-room
./install.sh local
```

启动成功后打开 `http://localhost:18080`，测试邮件在 `http://localhost:18025`。账号密码由安装器随机生成，定位见[演示账号](docs/development/demo-accounts.md)。完整前置条件、预期输出、端口调整和失败处理见[安装指南](docs/operations/installation.md)；无 Git 或离线迁移见[离线发布与导入](docs/modules/portable-release.md)。已有发行环境升级使用 `deploy` 或 `import-release`，不要用首次安装入口覆盖。

需要修改代码时，从[开发环境](docs/development/local-setup.md)开始。该模式使用前端 8000 和两个原生后端；默认 local 状态与容器 local 共用，不能当作两套隔离环境同时运行。

## 当前交付

[v0.1.0 Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已发布。当前 P0 覆盖账号与权限、预约、教室审批、维修闭环，以及本机安装、可迁移发布、备份和回退。离线镜像包为 Linux ARM64，邮件使用 Mailpit；正式服务器、域名/TLS、外部 SMTP 和可选 P1 自然语言业务 Agent 不属于已完成验收范围。

GitLab 负责构建、集成、容器和浏览器门禁。合入 main 使用 Fast-forward，必要时先 rebase，默认不 squash，保留每个逻辑提交。详细发布身份、已验收范围和历史结果分别见[发布记录](docs/evidence/gitlab-release-v0.1.0.md)、[进度与需求追踪](docs/progress.md)、[验收证据索引](docs/evidence/README.md)。

## 参与开发与维护文档

- 人类开发者：[开发导航](docs/development/README.md) → [测试选择](docs/development/testing.md) → [贡献与文档维护](docs/contributing.md)。
- 协作 Agent：[AGENTS.md](AGENTS.md)，遵守同一套业务约束、验证和提交要求。
- 业务规则来源：[PRD](01_PRD.md)；原始设计与任务基线的阅读方式见[规格导读](docs/reference/README.md)。
