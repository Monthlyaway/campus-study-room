# 栖学 · 校园自习室

预约自习座位、申请整间教室、处理设施报修的一套校园资源系统。学生可在手机上完成预约和签到，工作人员使用同一套系统审批、派单与管理空间。

**课程验收先查看[登录账号与初始密码](docs/development/demo-accounts.md#公网课程验收账号)。已有账号从[用户手册](docs/user-guide/README.md)开始。** 手册先介绍常见工作流，再逐页说明按钮、状态和遇到问题时的处理办法。

## 按你的任务开始

| 我想做什么 | 从这里开始 | 能找到什么 |
| --- | --- | --- |
| 课程验收登录 | [账号与初始密码](docs/development/demo-accounts.md#公网课程验收账号) | 公网地址、各角色登录凭据及演示顺序 |
| 使用系统 | [用户手册](docs/user-guide/README.md) | 首次登录、预约签到、申请审批、报修验收、每个页面怎么用 |
| 理解系统设计 | [架构导读](docs/architecture/README.md) | 组件与部署、关键业务链路、数据和权限边界、设计取舍 |
| 开发后端 | [后端开发指南](docs/development/backend.md) | 组件和源码入口、调通 API、修改接口、数据库迁移与测试 |
| 开发前端 | [前端开发指南](docs/development/frontend.md) | 本地启动、路由与组件、请求和权限、样式、调试与测试 |
| 安装或维护系统 | [运维手册](docs/operations/README.md) | 源码/离线安装、环境配置、健康检查、升级、备份、回退和排障 |
| 查找规格或验收依据 | [文档总览](docs/README.md) | 产品需求、设计基线、ADR、模块参考、测试和发布证据 |

## 当前访问与开发入口

服务部署到 codex-lb，公网目标为 `https://49.51.193.169`。实际运行版本、HTTPS 状态、维护入口及已验证范围统一记录在[当前实例接手卡](docs/operations/current-instance.md)。生产使用独立管理员和正式账号，不能套用测试环境的 `student` / `admin` 演示凭据。使用已安装系统的用户无需安装开发工具。

代码、CI 和发布全部在 codex-lb；控制电脑通过 SSH 连接。开发 checkout 为 `/home/ubuntu/Codes/campus-study-room`，从[远程开发指南](docs/development/local-setup.md)开始。GitHub 私有仓库为 [Monthlyaway/campus-study-room](https://github.com/Monthlyaway/campus-study-room)，PR、Actions 与 Packages/Release 操作见[GitHub 手册](docs/operations/github.md)。

## 新装一套开发或测试环境

以下在 codex-lb 或独立开发服务器执行，不能指向生产状态。需要 Docker Engine、Compose v2、Python 3.10+、Git 和私有仓库读取权限。应用在固定容器中编译；当前 codex-lb 为 2 核/2 GiB，使用串行 CI 和资源限制，不能同时再启动另一套重型构建。

```sh
git clone https://github.com/Monthlyaway/campus-study-room.git
cd campus-study-room
./install.sh local
```

启动成功后通过 SSH 转发访问服务器的 `http://localhost:18080`，测试邮件在 `http://localhost:18025`。账号密码由安装器随机生成，定位见[演示账号](docs/development/demo-accounts.md)。完整前置条件、预期输出、端口调整和失败处理见[安装指南](docs/operations/installation.md)；无 Git 或离线迁移见[离线发布与导入](docs/modules/portable-release.md)。已有发行环境升级使用 `deploy` 或 `import-release`，不要用首次安装入口覆盖。

需要修改代码时，在 codex-lb 从[开发环境](docs/development/local-setup.md)开始。该模式使用前端 8000 和两个原生后端；默认 local 状态与容器 local 共用，不能当作两套隔离环境同时运行。

## 当前交付

P0 覆盖账号与权限、预约、教室审批、维修闭环，以及安装、不可变发布、备份和回退。当前 GitHub Actions 使用 codex-lb 的专用 runner，三应用通过 GHCR Packages 保存，发行信息见 [GitHub Releases](https://github.com/Monthlyaway/campus-study-room/releases)。工作分支通过完整验证后提交 PR，使用 rebase merge，默认不 squash，保留每个逻辑提交。

原 GitLab v0.1.0 / Linux ARM64 交付按[历史发布记录](docs/evidence/gitlab-release-v0.1.0.md)保留。新服务器、Linux AMD64、HTTPS 与生产运行以本次[进度](docs/progress.md)和[证据索引](docs/evidence/README.md)为准；不能用旧通过结果代替。邮件仍使用 Mailpit，外部 SMTP 实际收信未提供验收条件；P1 自然语言业务 Agent 未启动。用户调整的开发、资源与交付边界见 [ADR 0004](docs/adr/0004-github-codex-lb-production.md)。

## 参与开发与维护文档

- 人类开发者：[开发导航](docs/development/README.md) → [测试选择](docs/development/testing.md) → [贡献与文档维护](docs/contributing.md)。
- 协作 Agent：[AGENTS.md](AGENTS.md)，遵守同一套业务约束、验证和提交要求。
- 业务规则来源：[PRD](01_PRD.md)；原始设计与任务基线的阅读方式见[规格导读](docs/reference/README.md)。
