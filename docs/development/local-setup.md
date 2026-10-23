# 在本机开发

[开发导航](README.md) · [演示账号](demo-accounts.md) · [测试选择](testing.md)

本页帮助开发者从源码启动可调试的应用。只想使用已安装系统，请读[用户手册](../user-guide/README.md)；只想安装运行包，请读[安装指南](../operations/installation.md)。以下命令都从仓库根目录执行。

## 先选运行模式

| 目的 | 入口 | 默认状态与地址 | 会做什么 |
| --- | --- | --- | --- |
| 修改前后端源码 | 本页的 `dev-up local` + `web dev` | `.local/local`；前端 8000，core 8081，repair 8082 | 构建后端、迁移、种子；本机 Java 进程，Docker 提供 PG/邮件 |
| 从源码安装一套容器应用 | `./install.sh local` | `.local/local`；应用 18080，邮件 18025 | 构建并启动完整 Compose 环境 |
| 导入已有发行包 | [离线导入](../modules/portable-release.md) | 显式指定持久状态目录，端口取自该目录配置 | 导入固定镜像并部署，不下载编译依赖 |
| 完整本地检查 | `./scripts/check` | `.local/check/<运行编号>`；随机端口 | 创建隔离 PG/邮件/后端，写入本轮测试数据 |
| 使用这台机器现有交付实例 | [当前实例](../operations/current-instance.md) | `.local/portable-install`；应用 19282，邮件 19227 | 使用既有数据，不能作为随意运行测试的目标 |

原生开发与容器安装的默认 `local` 指向同一状态目录，不能把它们当成两套隔离环境同时运行。本文假设这是你自己的开发 checkout，端口未被其他实例占用。已有环境先确认归属、配置和运行方式；不要为启动开发进程随意停止别人的服务。

`STUDYROOM_STATE_DIR` 可以覆盖状态目录，但不会自动解决端口与 Compose 项目名冲突。原生 core/repair 仍固定使用 8081/8082；切换环境名不代表可以同时运行两套原生后端。多实例隔离见[运维配置](../modules/operations.md)。

## 前置条件

- Git 和仓库 SSH 读取权限。
- Java 21；仓库的 Maven Wrapper 负责固定 Maven 版本。
- Node 24，项目基线是 `.node-version` 中的 24.22.1；`scripts/web` 固定使用 pnpm 10.34.7。
- Python 3.10+、Docker Engine/Desktop、Compose v2。Docker 需已启动。
- 首次获取 Wrapper、依赖和基础镜像时需要网络。精确基线见[依赖清单](../dependency-baseline.json)，前端依赖以 `package.json` 和锁文件为准。

先确认工具可用：

```sh
java -version
node --version
python3 --version
docker version
docker compose version
```

Java 需指向 21；可通过你已有的版本管理器设置 `JAVA_HOME`。脚本在 macOS 会识别已有的 Homebrew OpenJDK 21 路径，但这不替代其他机器上的 Java 安装。不要把另一台机器的私有路径或临时下载代理写进项目配置。

## 启动后端和前端

还没有源码时先 clone；已有 checkout 直接进入该目录：

```sh
git clone git@code.agibot.com:yangchunyu/campus-study-room.git
cd campus-study-room
./scripts/dev-up local
./scripts/web install --frozen-lockfile
./scripts/web dev
```

`dev-up` 会生成缺失的本地配置和随机秘密，启动 PG/Mailpit，构建 Java 应用，执行迁移、reference/demo 种子，再启动两个 Java 进程。看到 `Backend ready: core localhost:8081, repair localhost:8082` 后，前端命令在当前终端持续运行；浏览器打开 `http://localhost:8000`。

默认 local 的地址如下。端口已由你修改时，以 `.local/local/compose.env` 为准；不要打印整个配置或秘密目录到公开日志。

| 服务 | 默认地址/端口 | 用途 |
| --- | --- | --- |
| 前端 | `http://localhost:8000` | 用户操作；开发代理把 core/repair API 分发到两个后端 |
| core | `http://localhost:8081` | 后端调试、公开 API；私有控制接口只用于本机诊断 |
| repair | `http://localhost:8082` | 维修 API 与后端调试 |
| PostgreSQL | `localhost:15432` | 原生开发连接；正常容器发行不发布数据库端口 |
| Mailpit 收件箱 | `http://localhost:18025` | 查看测试邮件 |
| SMTP | `localhost:11025` | core 发送邮件的本机端口 |

用同一份配置中的[演示账号](demo-accounts.md)登录。`student` 和 `applicant` 的能力不同；`admin` 不能替代审批人。前端开发服务没有发行版的完整 `/version` 身份，因此不能把 8000 直接作为正式发行浏览器 E2E 的目标，详见[测试指南](testing.md)。

## 确认启动成功

下面只读取健康信息，不创建业务记录：

```sh
curl -fsS http://localhost:8081/actuator/health/readiness
curl -fsS http://localhost:8082/actuator/health/readiness
```

两者应返回健康状态 `UP`。然后检查前端能打开登录页、演示账号能登录、Mailpit 收件箱可打开。若登录页能打开但 API 失败，先检查两个后端和代理地址，不要仅凭前端编译成功判定系统已就绪。

这些 `/actuator` 地址用于原生本机后端；容器公开入口会拒绝 `/actuator` 和 `/internal`，那是预期的隔离行为。

## 修改代码后如何生效

前端开发服务支持热更新。后端使用稳定副本 `.local/local/run/<service>.jar` 运行，重新执行 `dev-up` 会复用身份匹配的存活进程，不会自动替换其运行 JAR。后端改动后执行：

```sh
python3 scripts/studyroom.py native-restart local
```

该操作会优雅停止**本环境**后端，再构建、迁移和启动。日志追加到 `.local/local/core.log`、`.local/local/repair.log`。IDE 可从根 `pom.xml` 导入 Maven 模块并阅读/设置断点；项目没有封装远程调试端口开关，不要假设存在 `--debug-port` 参数。

停止前端时在运行它的终端按 Ctrl-C；停止本机 Java 进程：

```sh
python3 scripts/studyroom.py native-stop local
```

PG/Mailpit 会保留运行和数据。需要停止自己环境的 Compose 服务时再执行 `python3 scripts/studyroom.py stop local`；这个命令会停止该项目所有 Compose 服务，先确认没有其他人在使用。不要删除命名卷来处理普通启动问题。

## 首次改动和排障

- 后端功能：[组件与源码路径](backend.md) → [API 会话和契约](api.md) → [测试选择](testing.md)。
- 前端功能：[路由、组件和请求](frontend.md) → [用户流程](../user-guide/workflows.md) → [浏览器测试](../../tests/browser/README.md)。
- 配置/端口不匹配、密码目录混用、旧 JAR 未替换：先核对本页模式表与状态目录，再看[排障手册](../operations/runbook.md)。
- 迁移 checksum 失败：保留日志，检查是否修改了已应用迁移；应新增迁移，不要删除 Flyway 历史来绕过检查。

本页的源码依据是 [studyroom.py](../../scripts/studyroom.py)、[开发 Compose](../../infra/compose/development.yml)和[前端代理配置](../../apps/web/.umirc.ts)。
