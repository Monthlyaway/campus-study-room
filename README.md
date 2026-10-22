# 栖学 · 校园自习室

响应式校园资源系统：个人自习座位预约、教室申请与两级审批、报修维修与验收。面向学生的手机流程与面向管理员的桌面工作台共用一套权限和资源裁决。

**当前状态：正在实现与集成验收。** 不将尚未验证的功能或正式服务器部署标记为完成。进展与证据见 [docs/progress.md](docs/progress.md)。

## 本地安装入口

需要 Docker Engine/Desktop、Compose v2、Python 3.10+。推荐至少 4 vCPU / 8 GiB。

```sh
git clone git@code.agibot.com:yangchunyu/campus-study-room.git
cd campus-study-room
./install.sh local
```

该入口构建全部应用，初始化独立数据库账号，执行 Flyway 迁移、基线与本地演示种子，启动服务并等待健康检查。重复执行保留数据和已有密钥。已在本机独立 test 环境完成首次完整启动与预约、审批、维修 HTTP 回归。默认 local 端口如下；本次 test 实例使用应用 `18082`、邮件 `18027`。

- 应用：`http://localhost:18080`
- 本地邮件收件箱：`http://localhost:18025`
- 配置：`.local/local/compose.env`
- 随机生成的演示密码：`.local/local/secrets/demo_password`（只存在本机，权限 0600）
- 演示账号：`student`、`student2`、`applicant`、`dept-approver`、`space-approver`、`dispatcher`、`repairer`、`property-admin`、`room-admin`、`operator`、`admin`

演示账号仅允许 local/dev/test。生产不导入演示数据；正式服务器、域名和 SMTP 接入按用户选择留待后续。数据库和服务内部接口不发布到公网。

## 原生开发

需要 Java 21、Node 24.22.1 和 Docker；Maven Wrapper 与 pnpm 版本固定。

```sh
./scripts/dev-up local     # PG/邮件、构建、迁移、种子、两个 Java 进程
./scripts/web install --frozen-lockfile
./scripts/web dev          # Umi 开发服务器，代理两个 API 服务
./scripts/check            # 后端验证、类型检查、前端构建、Compose 校验
```

开发用数据库端口为本机 `15432`，SMTP 为 `11025`；正常容器安装不发布数据库端口。原生进程日志在 `.local/local/`；构建输出与正在运行的 JAR 分开存放。更新代码后使用 `python3 scripts/studyroom.py native-restart local`，该命令等待旧进程正常退出后再构建、迁移和启动。所有时间按 Asia/Shanghai 展示。

## 架构与仓库

| 路径 | 职责 |
| --- | --- |
| `services/core-service` | 账号权限、空间、通知审计、座位预约 M1、教室审批 M2、唯一资源裁决 |
| `services/repair-service` | 报修 M3、可靠 outbox、通过 HTTP 请求资源封禁与释放 |
| `apps/web` | React 19 / TypeScript / Umi 4 / Ant Design 6 |
| `modules` | DTO、稳定共享会话身份 |
| `tools/migration-runner`、`db` | 独立 Flyway 迁移、基线与演示种子 |
| `infra`、`scripts` | Compose、安装、部署、备份、应用回退 |
| `contracts/openapi` | API 契约与生成客户端来源 |
| `docs` | 模块说明、ADR、需求追踪、测试与发布证据 |

PostgreSQL 18 使用 core、repair、session 三个 schema；运行账号无 DDL 权限。后台任务持久化，维修服务不直接写 core 数据。写请求使用 CSRF、幂等键和版本号；预约、审批及维修共享房间锁协议。

## GitLab、提交与发布

[项目仓库](https://code.agibot.com/yangchunyu/campus-study-room)使用 GitLab CI/CD，不使用 GitHub Actions。用户已授权自主分阶段提交和发布；每个逻辑改动独立 commit，验收结果随提交更新。该 GitLab 实例未启用内置容器 registry，本地发行采用校验过的不可变镜像组合与离线镜像包，服务器可配置自备 registry。

```sh
./scripts/build-release r-YYYYMMDDTHHMMSS-commit
./scripts/deploy test .local/releases/RELEASE_ID/manifest.json
./scripts/backup test
./scripts/rollback test .local/releases/PREVIOUS_ID/manifest.json
./scripts/export-release .local/releases/RELEASE_ID/manifest.json
```

发布构建要求干净且已提交的 checkout。回退只更换整套应用，保留数据库结构与业务数据；禁止 `docker compose down -v`。发布脚本与兼容恢复流程正在集成验收，实际可用范围见进度记录。

## 文档

- [产品需求](01_PRD.md)、[技术方案](02_Technical_Design.md)、[里程碑](03_Development_Milestones.md)
- [Agent 协作规则](AGENTS.md)（用户所说的 Agents.md，仓库采用标准大写命名）
- [当前交付范围与 GitLab ADR](docs/adr/0002-delivery-and-gitlab.md)
- [模块与公共层归属](docs/adr/0001-module-and-shared-layer-ownership.md)
- [实施分工](docs/team.md)、[进度](docs/progress.md)、[风险记录](docs/ethics-and-risks.md)
- [精确依赖与镜像基线](docs/dependency-baseline.json)
- [安装与运维](docs/modules/operations.md)、[离线安装包](docs/modules/portable-release.md)
- [账号管理](docs/modules/administration.md)、[核心预约与审批](docs/modules/core.md)、[维修](docs/modules/repair.md)、[前端](docs/modules/web.md)
- [请求关联与运行日志](docs/modules/observability.md)
- [39 条 P0 需求追踪](docs/requirements-traceability.md)

前端采用 Sites skill 的本地设计指导并使用 Playwright 调试，不注册或发布 Site。独立 agent 必须实际试用前端并记录反馈。P1 自然语言业务 Agent 尚未启动，P0 优先。
