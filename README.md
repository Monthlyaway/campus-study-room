# 栖学 · 校园自习室

响应式校园资源系统：个人自习座位预约、教室申请与两级审批、报修维修与验收。面向学生的手机流程与面向管理员的桌面工作台共用一套权限和资源裁决。

**当前状态：最终 V5 本机验收已通过，整体交付尚未完成。** V5 的浏览器6流程、五镜像离线导入、正式回退与恢复均通过，[限定范围隐私审计](docs/evidence/runtime-audit-final-v5.md)为0命中。当前提交的 [GitLab pipeline 683462](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683462) 已结束：后端、前端、集成通过，容器任务在从官方Alpine HTTPS CDN安装Python/Git时遭遇TLS EOF/超时而失败，尚未开始业务构建，打包任务跳过。完整流水线、main 合入、最终 tag 和正式 release 仍待完成。用户授权的[专用 ARM64 Runner #5666](docs/evidence/gitlab-runner-registration.md)仅绑定本项目及受保护引用。进展见 [docs/progress.md](docs/progress.md)，版本与运维证据见[发布演练](docs/evidence/release/README.md)。

V5 的完整源码包含后续 CI、测试和文档修正；[242份生产及构建输入](docs/evidence/release/final-v5-production-equivalence.json)与R4的路径、字节和Git权限模式一致。因此保留R4完整性能、SQL及真实core/repair恢复的原版本证据，不把它们改标成V5重跑结果，也不声称两版镜像ID相同。R3、R4及更早失败记录分别保留。

## 本机当前 V5 入口

- 应用：[http://localhost:19282](http://localhost:19282)
- Mailpit：[http://localhost:19227](http://localhost:19227)
- 发布号：`r-20261023T001633-e95b025`；完整源码：`e95b025de6f03c393d8ad32063ed51b2cbf7babe`
- 状态目录：`.local/portable-install/`；随机演示密码：`.local/portable-install/secrets/demo_password`（只存在本机，权限0600）

这是当前V5验收实例；下述源码安装默认使用18080，既有18082测试实例与8000开发实例不代表当前V5。[V5前端证据](docs/evidence/frontend-release-final-v5.md)记录6/6通过及真实隐私反证；[R4前端证据](docs/evidence/frontend-release-v4.md)和[R3历史证据](docs/evidence/frontend-release.md)保留原结果及另行标明版本的跨升级午夜签到。

## 本地安装入口

需要 Docker Engine/Desktop、Compose v2、Python 3.10+。推荐至少 4 vCPU / 8 GiB。

```sh
git clone git@code.agibot.com:yangchunyu/campus-study-room.git
cd campus-study-room
./install.sh local
```

该入口构建全部应用，初始化独立数据库账号，执行 Flyway 迁移、基线与本地演示种子，启动服务并等待健康检查。重复执行保留数据和已有密钥。已在本机独立 test 环境完成首次完整启动与预约、审批、维修 HTTP 回归。以下是新建源码安装的默认 local 端口；请与上方现有V5验收实例区分。

- 应用：`http://localhost:18080`
- 本地邮件收件箱：`http://localhost:18025`
- 配置：`.local/local/compose.env`
- 随机生成的演示密码：`.local/local/secrets/demo_password`（只存在本机，权限 0600）
- 演示账号：`student`、`student2`、`applicant`、`dept-approver`、`space-approver`、`dispatcher`、`repairer`、`property-admin`、`room-admin`、`operator`、`admin`

需要自动备份时使用 `./install.sh local --schedule-backups`，或在已有环境运行 `./scripts/schedule-backup install local`。调度器每天生成校验过的备份，成功后保留最近七天；macOS 使用 launchd，Linux 使用 systemd，详情见[发布运维](docs/modules/release-operations.md)。

演示账号仅允许 local/dev/test。生产不导入演示数据；正式服务器、域名和 SMTP 接入按用户选择留待后续。数据库和服务内部接口不发布到公网。

## 原生开发

需要 Java 21、Node 24.22.1 和 Docker；Maven Wrapper 与 pnpm 版本固定。

```sh
./scripts/dev-up local     # PG/邮件、构建、迁移、种子、两个 Java 进程
./scripts/web install --frozen-lockfile
./scripts/web dev          # Umi 开发服务器，代理两个 API 服务
./scripts/check            # 编译/类型/单元 + 隔离真实 PG、HTTP、SMTP、后端契约检查
./scripts/check --unit-only # 日常快检；明确跳过真实集成与后端契约，不能作为完整验收
```

默认检查需要可用的 Docker、Java、Node 和已安装的前端依赖；依赖不可用或测试被跳过时失败退出，不自动降级。它创建独占 PostgreSQL/Mailpit 容器与数据卷、随机凭据及本机空闲端口，运行两个临时后端和九组真实数据库套件（含公开/内部契约）、跨服务 HTTP、邮件与响应丢失补偿测试。检查不会重启现有 local/dev/test/prod 服务。整个检查持有工作区构建锁，集成环境锁覆盖依赖创建、迁移、测试和清理；结束只移除本轮容器/网络并停止本轮进程，保留命名数据卷。项目/卷名、0600 凭据与运行 JAR 留在私有 `.local/check/<运行编号>/` 供复查，不自动删除数据卷。日志和逐套件计数保留在 `output/check/<运行编号>/`，CI 调用同一集成函数与测试清单。性能、浏览器和发布演练仍有各自验收入口，不由该命令代替。

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

[项目仓库](https://code.agibot.com/yangchunyu/campus-study-room)使用 GitLab CI/CD，不使用 GitHub Actions。用户已授权自主分阶段提交和发布；每个逻辑改动独立 commit，验收结果随提交更新。合入 main 使用 Fast-forward merge，必要时先 rebase 并重新验证；默认不 squash，保留提交记录且不生成 merge commit。GitLab 要求 pipeline 成功，skipped 不视为成功。该 GitLab 实例未启用内置容器 registry，本地发行采用校验过的不可变镜像组合与离线镜像包，服务器可配置自备 registry。

```sh
./scripts/build-release r-YYYYMMDDTHHMMSS-commit
./scripts/deploy test .local/releases/RELEASE_ID/manifest.json
./scripts/backup test
./scripts/rollback test .local/releases/PREVIOUS_ID/manifest.json
./scripts/export-release .local/releases/RELEASE_ID/manifest.json
```

发布构建要求干净且已提交的 checkout。回退只更换整套应用，保留数据库结构与业务数据；禁止 `docker compose down -v`。[V5→R4→V5正式回退与恢复](docs/evidence/release/final-v5-r4-rollback.json)已在独立环境通过，原2个会话、3类业务及完整行摘要保持，未降级数据库。R4→B的[兼容演练](docs/evidence/release/v4-compatibility.json)及[正式回退](docs/evidence/release/v4-actual-rollback.json)、R3→B历史结果仍保留原身份，不与本次同schema演练混用。

本机 `.local/releases/` 保留A、B、R3、R4、V5五份发布清单及历史包。当前五镜像包位于 `.local/releases/r-20261023T001633-e95b025/portable/`，包含三应用、PostgreSQL、Mailpit、指定提交源码与校验和；[正式导入核对](docs/evidence/release/final-v5-portable-import.json)确认442份源码内容及完整权限、五镜像和三版本一致，导入前原7份秘密及19预约/16申请/11报修的完整行摘要保持。这是导入时快照，后续浏览器验收仍会创建记录。R4的[414份源码导入结果](docs/evidence/release/v4-portable-import.json)另行保留。

单一运输归档为 `.local/releases/campus-study-room-v0.1.0-linux-arm64.tar.gz`，同目录附 `.sha256`，共414259208字节，SHA256为 `cc13877ab94d3394640a82508c42fc924acaa95a9b5226ebad3f71f2f84584f0`。当前包仅为linux/arm64；AMD64服务器需要对应平台构建，未实测远程或跨架构安装。`v0.1.0`保护规则已配置，但归档文件名不代表已创建tag或正式release；最终发布门禁仍待闭合。

## 文档

- [产品需求](01_PRD.md)、[技术方案](02_Technical_Design.md)、[里程碑](03_Development_Milestones.md)
- [Agent 协作规则](AGENTS.md)（用户所说的 Agents.md，仓库采用标准大写命名）
- [当前交付范围与 GitLab ADR](docs/adr/0002-delivery-and-gitlab.md)
- [模块与公共层归属](docs/adr/0001-module-and-shared-layer-ownership.md)
- [实施分工](docs/team.md)、[进度](docs/progress.md)、[风险记录](docs/ethics-and-risks.md)
- [精确依赖与镜像基线](docs/dependency-baseline.json)
- [安装与运维](docs/modules/operations.md)、[Ubuntu 一键运行环境](docs/modules/bootstrap.md)、[离线安装包](docs/modules/portable-release.md)
- [账号管理](docs/modules/administration.md)、[核心预约与审批](docs/modules/core.md)、[维修](docs/modules/repair.md)、[前端](docs/modules/web.md)
- [请求关联与运行日志](docs/modules/observability.md)
- [维修负面矩阵、三期限和真实 SMTP 验收](docs/evidence/repair-acceptance.md)
- [39 条 P0 需求追踪](docs/requirements-traceability.md)

前端采用 Sites skill 的本地设计指导并使用 Playwright 调试，不注册或发布 Site。独立 agent 必须实际试用前端并记录反馈。P1 自然语言业务 Agent 尚未启动，P0 优先。
