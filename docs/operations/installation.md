# 安装新环境

[运维导航](README.md) · [当前实例](current-instance.md) · [升级既有实例](runbook.md#升级既有实例)

本页用于建立新的 local/dev/test 环境；codex-lb 的生产安装与当前配置入口见[GitHub 发布](github.md)及[接手卡](current-instance.md)，不得通过 demo 安装路径初始化生产。已有发布实例直接走升级流程；已有业务数据迁往另一台机器还需要独立的数据迁移与切换方案。离线包只交付程序和运行镜像，`restore` 只恢复到隔离库。

## 先检查机器与工具

| 机器用途 | 前置条件 | 需要网络的阶段 |
| --- | --- | --- |
| 从源码安装容器应用 | Python 3.10+、Docker Engine/Desktop、Compose v2；获取源码时需 Git | 基础镜像与容器构建依赖首次下载 |
| 导入完整离线包 | Python 3.10+、Docker/Compose、tar、SHA-256 工具；Docker daemon 与包同为 linux/arm64 或 linux/amd64 | 已有完整运行时和包后，导入不 pull、不构建 |
| 构建并检查正式源码发布 | 上述工具、Git、Java 21、Node 24；pnpm 由脚本固定 | 完整本地检查及构建依赖，见[开发准备](../development/local-setup.md) |
| Ubuntu 引导控制端 | Python 3.12+、venv、OpenSSH、可信 inventory | PyPI，以及目标主机的 Ubuntu/Docker 官方软件源 |
| Ubuntu 受管主机 | Ubuntu 24.04、Python 3.12、systemd、已授权免交互 sudo、可信 SSH 身份 | 安装固定 Docker 组件；之后再导入应用 |

原性能基线为 4 vCPU / 8 GiB；用户选择的 codex-lb 是 2 核/约 2 GiB，CI 串行并设置内存预算，具体容量不能沿用原性能结论。为镜像、数据库、备份及历史版本准备磁盘空间。原 v0.1.0 运输包为 linux/arm64；codex-lb 的新发布须选择 linux/amd64 并有独立验收。先运行以下只读工具检查，核对 Docker **daemon** 的平台和所选 context，而不是只看客户端主机：

```sh
python3 --version
docker context show
docker info --format '{{.OSType}}/{{.Architecture}}'
docker compose version
```

Python 版本不足、Docker 不可连接或 context 指向错误主机时先修复前置条件。Ubuntu 缺少运行时可走[引导手册](../modules/bootstrap.md)；引导只准备运行时、账号和配置，完成后仍需本页的包导入。不得把 `--validate`、`--syntax-check` 或容器内包安装等同真实服务器完成部署。

## 选择状态目录与端口

源码目录可以随版本更换；状态目录保存该实例长期配置和秘密，必须固定。每套实例记录四项：环境名、绝对状态目录、Compose project、Docker context。默认端口见[环境表](README.md#环境如何区分)。

下面示例在新终端中执行，路径须替换为自己已准备好的绝对路径；不要套用当前本机实例的状态目录来练习新装。

```sh
set -eu
studyroom_source_dir=/absolute/path/to/campus-study-room
studyroom_env=local
export STUDYROOM_STATE_DIR=/absolute/path/to/new-local-state
```

对于新的独立目录，可先生成配置再调整端口：

```sh
"$studyroom_source_dir/scripts/init-env" "$studyroom_env" --directory "$STUDYROOM_STATE_DIR"
```

`init-env` 不自动读取 `STUDYROOM_STATE_DIR`，因此 `--directory` 必须显式提供。它生成随机秘密和 `compose.env`，不覆盖已有文件。**若路径已经属于既有实例，先停止新装流程，按接手/升级流程核对，不能用生成缺失秘密修复旧数据库。**

在本机编辑新环境的 `compose.env`。需要调整的字段如下，其余先保留默认值：

| 字段 | 新建独立实例的要求 |
| --- | --- |
| `ENV_NAME`、`STUDYROOM_STATE_DIR` | 与选择的环境和绝对目录一致 |
| `COMPOSE_PROJECT_NAME` | 同一 daemon 中唯一；第二套 local 可用 `studyroom-local-review` |
| `WEB_PORT`、`MAIL_UI_PORT` | 不与其他实例冲突 |
| `PUBLIC_BASE_URL` | 与 Web 地址对应，如 `http://localhost:18180` |
| `BIND_ADDRESS` | 本地交付保持 `127.0.0.1` |
| `DB_PORT`、`SMTP_PORT_HOST` | 仅原生开发模式发布；若使用该模式也需不冲突 |

升级时保持原 project/状态和秘密，不执行此新建配置步骤。配置含义及保留规则见[状态参考](../modules/operations.md)。

## 从源码安装新环境

适合想从当前 checkout 构建完整容器应用的维护者。首次获取源码：

```sh
git clone https://github.com/Monthlyaway/campus-study-room.git
cd campus-study-room
```

已有源码则进入其根目录。按上一节选择环境和新状态目录，完成可选端口配置，再执行：

```sh
"$studyroom_source_dir/install.sh" "$studyroom_env"
python3 "$studyroom_source_dir/scripts/studyroom.py" status "$studyroom_env"
```

安装会启动 PostgreSQL/Mailpit、构建 core/repair/web、执行独立迁移器和 reference/demo seed，再等待三个应用健康。成功输出 `Installed: <PUBLIC_BASE_URL>`；`status` 应显示五个服务运行，带健康检查的服务为 healthy。首次源码安装的版本是 `development` / `working-tree`，不是不可变发行版；正式发布见[构建发布包](../modules/portable-release.md#构建与导出)。

打开所配置 Web 地址，用[演示账号](../development/demo-accounts.md)登录，并确认 Mailpit 可打开。执行[巡检](runbook.md#检查健康与版本)检查两个后端。失败时先看错误阶段与日志；依赖下载失败修复网络后可重试同一新环境，保留秘密和卷。若已有 `current-release.json`，安装器拒绝覆盖，改走升级；不要删除该文件绕过。

## 从离线包安装新环境

适合已取得完整五镜像包的同架构目标机器。当前包的位置与可信摘要见[当前实例](current-instance.md#当前可搬运的安装包)。校验和能检测损坏与不一致，不能证明发布者身份，因此整包与摘要应来自可信渠道。

**第一步：校验运输归档并解包。** 下例包名对应 v0.1.0 ARM64；使用其他版本时替换包名并核对该版本可信摘要。下载目录必须已有归档和相邻 `.sha256`；新解包目录的父目录已存在、目录自身不存在。

```sh
set -eu
studyroom_download_dir=/absolute/path/to/downloads
studyroom_unpack_dir=/absolute/path/to/new-unpack-directory
studyroom_package=campus-study-room-v0.1.0-linux-arm64
cd "$studyroom_download_dir"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c "$studyroom_package.tar.gz.sha256"
else
  shasum -a 256 -c "$studyroom_package.tar.gz.sha256"
fi
mkdir "$studyroom_unpack_dir"
tar -xzf "$studyroom_package.tar.gz" -C "$studyroom_unpack_dir"
studyroom_bundle_dir="$studyroom_unpack_dir/$studyroom_package"
```

校验应显示 OK；失败则停止，不解包或导入。若已取得完整 `portable/` 目录，跳过外层解包，仅设置 `studyroom_bundle_dir=/absolute/path/to/portable`。

**第二步：校验包内文件，解出新的源码目录。** 新源码目录的父目录已存在，目录自身不存在；不要覆盖旧源码。

```sh
studyroom_source_dir=/absolute/path/to/new-release-source
cd "$studyroom_bundle_dir"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c SHA256SUMS
else
  shasum -a 256 -c SHA256SUMS
fi
cat INSTALL.md
mkdir "$studyroom_source_dir"
tar -xzf source.tar.gz -C "$studyroom_source_dir"
```

五项内层校验都应 OK。阅读 `INSTALL.md` 中的版本/平台，与目标 daemon 比对。包内源码无 `.git`，不需要在目标安装 Java、Node 或 Git。

**第三步：选择新状态并导入。** 下例创建 test，默认 Web 18082、Mailpit 18027。已有同名 project 时先按[配置说明](#选择状态目录与端口)建立不同 project 和端口；仅换目录不足以隔离。

```sh
studyroom_env=test
export STUDYROOM_STATE_DIR=/absolute/path/to/new-test-state
# 需要独立 project 或自定义端口时，先 init-env --directory 并编辑 compose.env。
"$studyroom_source_dir/scripts/import-release" "$studyroom_env" "$studyroom_bundle_dir"
python3 "$studyroom_source_dir/scripts/studyroom.py" status "$studyroom_env"
```

导入器再次校验文件、源码、平台和五镜像，执行 `docker load` 后调用正式部署门禁，不 pull 或 build。成功末尾输出 `Verified offline release deployed from ...`；状态目录应有 `current-release.json` 和 `release.env`。按[巡检](runbook.md#检查健康与版本)核对三应用的 release/commit 与包内 manifest 一致，再用[演示账号](../development/demo-accounts.md)登录。

校验失败先检查包完整性、平台和源码目录，不能改 manifest 让校验通过。进入部署后失败保留状态和日志，按[失败处理](runbook.md#发布失败或入口持续-503)排查。`--source-dir` 的另一种自动解包模式见[导入接口](../modules/portable-release.md#导入接口)，不要混用“新空目录”和“已解出的完整源码目录”。

## 准备浏览器 E2E 的隔离发行版环境

浏览器测试会实际创建预约、申请和维修记录。它要求三个应用的 commit 为同一完整 40 位 SHA；8000 开发页或 `development` / `working-tree` 安装不满足条件。使用已验证、与 daemon 同架构的完整包，按前文完成校验和源码解包，然后在**另一个新状态目录和 project**导入。下面采用专用于测试的 19382/19327，不能指向当前 19282 实例。

```sh
set -eu
studyroom_source_dir=/absolute/path/to/verified-package-source
studyroom_bundle_dir=/absolute/path/to/verified-portable-bundle
export STUDYROOM_STATE_DIR=/absolute/path/to/new-browser-test-state
test ! -e "$STUDYROOM_STATE_DIR"
"$studyroom_source_dir/scripts/init-env" test --directory "$STUDYROOM_STATE_DIR"
python3 - "$STUDYROOM_STATE_DIR/compose.env" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
updates = {
    'COMPOSE_PROJECT_NAME': 'studyroom-browser-e2e',
    'WEB_PORT': '19382', 'MAIL_UI_PORT': '19327',
    'DB_PORT': '15534', 'SMTP_PORT_HOST': '11127',
    'PUBLIC_BASE_URL': 'http://127.0.0.1:19382',
}
lines = path.read_text().splitlines()
assert all(any(line.startswith(key + '=') for line in lines) for key in updates)
path.write_text('\n'.join(
    line.split('=', 1)[0] + '=' + updates[line.split('=', 1)[0]]
    if line.split('=', 1)[0] in updates else line for line in lines
) + '\n')
PY
"$studyroom_source_dir/scripts/import-release" test "$studyroom_bundle_dir"
```

运行前确认这些端口和 `studyroom-browser-e2e` project 尚未使用；已有测试项目时选择另一组独立值，不能仅换状态路径。这个配置脚本只更新新环境的非秘密字段。导入应输出成功，并生成自己的 demo 数据和随机秘密；检查 `/version`、`/api/v1/version`、`/api/v1/repairs/version` 的 release/commit 与包清单一致。

若是在同一 daemon 上测试自己新构建的候选，可先按[构建步骤](../modules/portable-release.md#构建与导出)取得 manifest，再使用同一提交对应的源码，在上述新环境配置完成后以 `"$studyroom_source_dir/scripts/deploy" test /absolute/path/to/candidate/manifest.json` **代替 import-release**。该方式需要本地已有 manifest 指定的三个镜像和运行依赖；不能把未提交工作区当作已打包候选。

随后从需要验收的 checkout 按[浏览器测试说明](../../tests/browser/README.md)运行，目标 URL 为此处 `http://127.0.0.1:19382`，密码文件为此**测试状态**下的 `secrets/demo_password`。可用 `STUDYROOM_E2E_EXPECT_COMMIT` 进一步锁定清单完整提交。测试写入只属于此独立实例；测试完成也不要删除卷或秘密来伪造清理成功。这是一条准备流程，本次文档更新未运行安装或 E2E。

## 安装完成后

把实际环境名、状态/source 路径、project、Docker context、端口、版本和维护者记录在部署方的私有交接记录中。秘密只在状态目录管理；需要长期运行时按[备份调度](runbook.md#安装或检查每日备份调度)启用任务，并另行安排独立存储保护。远程 Ubuntu 默认回环访问，通过[SSH 转发](../modules/bootstrap.md#完成后导入应用)打开 Web/Mailpit。

后续使用[运维手册](runbook.md)，不重新执行首次安装来升级不可变发行版。本页步骤不能替代实际验收。GitHub/AMD64 与生产结果见[当前进度](../progress.md)；真实断网安装与线上数据库切换需要单独方案和证据，不能由同机导入结果推断。
