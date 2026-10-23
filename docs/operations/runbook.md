# 日常运维与故障处理

[运维导航](README.md) · [当前实例](current-instance.md) · [安装新环境](installation.md) · [日志字段](../modules/observability.md)

本页面向已经安装的实例。巡检只读业务；升级、回退、备份和恢复各有独立步骤，不应把它们连续执行成一个“修复脚本”。原生开发进程的启停与日志见[本机开发](../development/local-setup.md)。

## 先确定操作对象

每次打开新终端，先从该实例的交接记录填写下列值。当前服务器值见[接手卡](current-instance.md)；先 SSH 到 codex-lb 再操作。`studyroom_source_dir` 应是与目标发布机制兼容、已验证的源码目录，不是任意旧 checkout。

```sh
set -eu
studyroom_source_dir=/absolute/path/to/verified-release-source
studyroom_env=test
export STUDYROOM_STATE_DIR=/absolute/path/to/existing-state
studyroom_project=studyroom-example
studyroom_url=http://localhost:18082
```

先确认路径存在并读取有限的身份字段，不打印秘密或整个环境：

```sh
test -f "$studyroom_source_dir/scripts/studyroom.py"
test -f "$STUDYROOM_STATE_DIR/compose.env"
python3 - "$STUDYROOM_STATE_DIR/compose.env" <<'PY'
import pathlib, sys
allowed = {'ENV_NAME', 'COMPOSE_PROJECT_NAME', 'STUDYROOM_STATE_DIR', 'PUBLIC_BASE_URL', 'WEB_PORT', 'MAIL_UI_PORT'}
for line in pathlib.Path(sys.argv[1]).read_text().splitlines():
    key, sep, value = line.partition('=')
    if sep and key in allowed:
        print(f'{key}={value}')
PY
docker context show
docker info --format '{{.OSType}}/{{.Architecture}}'
```

任一步失败，或输出与所填环境、project、URL、绝对状态目录不一致，先停止后续操作并找回正确配置。Docker context 必须是原实例所在 daemon；本页不自动切换 context。`status` 经由配置加载器执行，缺少 `compose.env` 时会初始化，因此先做存在性检查，不能用它搜索一个猜测路径。不要运行 `init-env` 补出旧实例遗失的秘密。

以下命令沿用本节变量；新终端需重新确认。`release.env` 固定已发布镜像及离线运行别名，不能删除或用旧值覆盖。

## 检查健康与版本

```sh
python3 "$studyroom_source_dir/scripts/studyroom.py" status "$studyroom_env"
curl -fsS "$studyroom_url/healthz"
curl -fsS "$studyroom_url/version"
curl -fsS "$studyroom_url/api/v1/version"
curl -fsS "$studyroom_url/api/v1/repairs/version"
curl -fsS -o /dev/null -w 'entry HTTP %{http_code}\n' "$studyroom_url/"
```

预期 postgres/core/repair/web 为 running/healthy，Mailpit 为 running；三个版本的 `releaseId` 和 `commit` 与 `current-release.json` 一致。Web `/version` 是扁平对象，两个后端版本在响应的 `data` 中。源码 development 安装尚无正式清单，版本通常为 `development` / `working-tree`；它不能作为 v0.1.0 的运行身份。

`/healthz` 只说明 Nginx 能响应，维护时仍会返回 200。入口或后端 API 返回 503 应检查发布状态；不能仅凭 Web healthy 宣告业务恢复。公开 `/internal` 与 `/actuator` 返回 404 是预期隔离，内部健康检查由容器执行。查看已发布身份可以只读取这些非秘密字段：

```sh
python3 - "$STUDYROOM_STATE_DIR/current-release.json" <<'PY'
import json, pathlib, sys
value = json.loads(pathlib.Path(sys.argv[1]).read_text())
print('releaseId:', value['releaseId'])
print('commit:', value['commit'])
PY
```

完成技术巡检后打开登录页；local/dev/test 使用该环境自己的[演示账号](../development/demo-accounts.md)，prod 使用正式管理员/用户账号。不要把可写测试脚本指向生产或其他既有交付实例。

## 查看容器日志与追踪请求

项目没有 `studyroom.py logs` 命令。可在完成身份核对后，定义下面的 Compose 查看助手；它显式选择 project，并在存在时加载 `release.env`。本节只用它执行 `ps` 和 `logs`，发布仍走正式脚本。

```sh
studyroom_compose() {
  if [ -f "$STUDYROOM_STATE_DIR/release.env" ]; then
    docker compose --project-name "$studyroom_project" \
      --env-file "$STUDYROOM_STATE_DIR/compose.env" \
      --env-file "$STUDYROOM_STATE_DIR/release.env" \
      -f "$studyroom_source_dir/infra/compose/compose.yml" "$@"
  else
    docker compose --project-name "$studyroom_project" \
      --env-file "$STUDYROOM_STATE_DIR/compose.env" \
      -f "$studyroom_source_dir/infra/compose/compose.yml" "$@"
  fi
}
studyroom_compose ps --all
studyroom_compose logs --no-color --tail 200 core repair web
```

不要从 CI 或其他实例继承镜像、端口、project 的环境覆盖后直接执行 `docker compose up`；shell 变量可能覆盖 env 文件。日常发布使用 `scripts/deploy` / `import-release`，由它们加载并核对目标镜像组合。未提供通用的 `start` 子命令；需重新部署同一版本时，也先核对 pending/schema，再使用正式部署入口。

接口报错时记录时间、路径、HTTP 状态和响应 `X-Request-ID`。`/api/v1/repairs...` 转给 repair，其他业务 `/api/...` 转给 core；例如排查 repair 的请求：

```sh
studyroom_request_id=replace-with-request-uuid
studyroom_compose logs --no-color --since 30m repair | rg -F "$studyroom_request_id"
```

未安装 `rg` 时可用 `grep -F`。以结构化日志的 `event`、`errorCode`、`durationMs` 和服务/版本继续定位，字段规则见[观察性参考](../modules/observability.md)。后台任务没有用户请求时，以该文档的任务/命令 ID 关联。日志、业务 ID、截图和邮件只在授权范围分享，先去掉个人数据；不要附带 Cookie、密码或整个状态目录。

## 升级既有实例

升级前完成身份与健康核对，记录当前版本，保留原状态/秘密/卷，以及至少前三个已验证成功版本的源码、三应用镜像和 manifest。确认磁盘空间与本轮维护窗口。发布命令会暂停任务、排空请求并备份；返回失败时按下一节处理，不连续反复切换版本。

**通过新离线包升级：** 按[安装指南](installation.md#从离线包安装新环境)完成外层与内层校验、解包到新源码目录，仅执行到第二步。不要执行新状态初始化；将变量指向**原实例**，用新源码导入：

```sh
studyroom_new_source=/absolute/path/to/new-release-source
studyroom_bundle_dir=/absolute/path/to/verified-new-bundle
test -f "$STUDYROOM_STATE_DIR/compose.env"
"$studyroom_new_source/scripts/import-release" "$studyroom_env" "$studyroom_bundle_dir"
```

**同 daemon 上已有新版本清单与镜像：** 从该版本的已验证源码运行：

```sh
studyroom_new_source=/absolute/path/to/new-release-source
studyroom_new_manifest=/absolute/path/to/new-release/manifest.json
"$studyroom_new_source/scripts/deploy" "$studyroom_env" "$studyroom_new_manifest"
```

两个方式择一。目标迁移必须包含已知结构且旧迁移摘要不变；重导旧包不能代替回退。不要对已有发行实例运行 `install.sh`。

命令成功后把 `studyroom_source_dir` 切为新源码路径，重复三版本、容器与公开入口检查。预期 current-release 指向新清单，`pending-release.json`、`control/maintenance`、`control/jobs-paused` 均无遗留。保留旧源码/包和镜像；若备份调度使用旧源码绝对路径，应从新验证源码重新安装调度并检查状态。此步骤完成前不要移走旧源码。

## 发布失败或入口持续 503

先记录命令退出码和时间，保留部署输出。部署命令非零，即使它自动恢复了上一版，也不表示新版本发布成功。检查 `pending-release.json`、`release-failures/`、三个服务日志和控制标记；这些文件的含义见[发布状态](../modules/release-operations.md#状态与门禁)。

```sh
python3 - "$STUDYROOM_STATE_DIR" <<'PY'
import json, pathlib, sys
state = pathlib.Path(sys.argv[1])
for name in ('pending-release.json', 'control/maintenance', 'control/jobs-paused'):
    print(name, 'present' if (state / name).exists() else 'absent')
pending = state / 'pending-release.json'
if pending.is_file():
    value = json.loads(pending.read_text())
    print('phase:', value.get('phase'))
PY
```

| 看到什么 | 下一步 |
| --- | --- |
| 校验/架构/源码不匹配，尚未开始部署 | 核对正确包、源码和 daemon，不编辑摘要或清单绕过校验 |
| 排空超时 | 查在途请求/后台任务日志，确认旧服务状态；保留已有维护状态，修复原因后重试同一发布 |
| 备份或迁移失败 | 查 postgres/迁移输出和 pending 阶段，保留 `.partial` 与迁移历史；修复同一发布，不能假设数据库仍是上一版本 |
| 新应用启动、版本或 smoke 失败 | 查对应服务；仅满足上一版身份与数据库兼容证明时，工具才可能自动恢复上一应用组合 |
| 自动恢复上一版成功 | 三版本应为上一版，schema 可仍为新版；保留 release-failures，记录本次发布失败 |
| pending/维护仍在或公开入口恢复失败 | 保持现场；修复同一发布后重新 deploy，或取得匹配实际数据库的证明后正式 rollback |

禁止以删除维护标记、pending、Flyway 历史、修改已应用 SQL、生成新秘密或删除卷来“解除故障”。部分迁移可能已经提交，应用版本不能推断 schema；仅靠启动旧镜像不构成安全回退。具体阶段与自动恢复条件见[发布机制](../modules/release-operations.md#失败处理)。

## 回退应用版本

适用于新应用有问题、已保留目标旧版本完整清单和镜像、且数据库兼容性已满足的场景。回退只更换 core/repair/web，不降级数据库、不执行 seed。跨 schema 需要与当前数据库和双方镜像绑定的实际演练证明；缺失时停止，不能临时人工填写 passed。

```sh
studyroom_previous_manifest=/absolute/path/to/previous-release/manifest.json
"$studyroom_source_dir/scripts/rollback" "$studyroom_env" "$studyroom_previous_manifest"
```

成功后重复三版本和入口检查：应用应为目标旧版，`schema-release.json` 保留已升级的结构；原秘密、卷和会话仍在。命令拒绝兼容证明或留下维护时，按上一节排查。使用当前已验证发布器执行，不能换旧源码绕过门禁。高级演练工具见[兼容演练](../modules/release-operations.md#v1--v2--v1-的可执行演练)。

## 创建一次备份

先完成目标身份核对，确认 postgres 运行且备份磁盘空间足够：

```sh
"$studyroom_source_dir/scripts/backup" "$studyroom_env"
```

成功输出该状态目录下 `backups/<UTC时间>.dump` 的实际路径，并生成同名 `.json`。脚本执行 `pg_dump -Fc`、归档目录清单检查和完整解码后才发布正式归档；元数据包含 SHA-256、版本、迁移清单和恢复点。保留二者，不仅复制 dump。`.partial` 不代表可用备份；失败时查 postgres、空间、权限及输出，不将失败文件改名为正式归档。

备份对应 pg_dump 的一致性快照，其后写入不在该恢复点内。数据库备份不包含原秘密和 Mailpit 邮件；这些需要部署方单独制定私有保护策略。将经验证的备份与必要秘密分别按受控流程存往独立存储，当前脚本没有自动异机灾备。

## 验证备份可恢复到隔离库

这一步会在当前 PostgreSQL 实例中新建数据库，保留在线库不变，不是线上数据切换。选择一次已完成备份的**实际绝对路径**，确认相邻 `.json` 存在且有足够空间：

```sh
studyroom_backup=/absolute/path/to/existing-state/backups/actual-timestamp.dump
test -f "$studyroom_backup"
test -f "${studyroom_backup%.dump}.json"
"$studyroom_source_dir/scripts/restore" "$studyroom_env" "$studyroom_backup"
```

成功输出 `studyroom_restore_<timestamp>` 数据库名、用户/预约/申请/报修数量和 `result: restored`，并明确在线库未修改。报告位于状态目录 `backups/`。这验证该归档可恢复；业务完整性还应与对应恢复点预期核对，不能只凭非零行数宣布迁移验收。

校验失败停止并取得正确成对文件；恢复中途失败保留隔离库与错误现场，不重定向到在线库。工具不会自动删除验证库。跨服务器数据搬迁、线上数据库切换及恢复后的邮件/密钥衔接尚无已验收流程，需要独立方案。

## 安装或检查每日备份调度

在所选实例已经安装、调用账号拥有目标 Docker 访问权限时执行。首次安装调度时先 install，再检查状态；已有调度只检查 status。

**v0.2.2 版本限制：**原包在 Linux 生成的 `WorkingDirectory` 引号会使 systemd 拒绝 unit。当前 codex-lb 的宿主 unit 已修正，日常 `run` / `status` 可用；不要用旧 v0.2.2 包重复 `install`。需要重新安装时，先采用包含本次修复且通过 PR 验证的源码或后续发行版本；保持原 v0.2.2 发布包与源码不可变。

```sh
"$studyroom_source_dir/scripts/schedule-backup" install "$studyroom_env" --state-dir "$STUDYROOM_STATE_DIR"
"$studyroom_source_dir/scripts/schedule-backup" status "$studyroom_env" --state-dir "$STUDYROOM_STATE_DIR"
```

install 会启用任务，可能立即触发当日备份。macOS 为当前用户 LaunchAgent；Linux root 为系统 timer，普通用户为 user timer。macOS 退出登录、宿主关机或 Docker 停止时不会备份；Linux user timer 依赖会话/linger，脚本不修改 linger 或授予 Docker 权限。恢复可用后，按需要手动补做当日检查，结果输出到当前终端：

```sh
"$studyroom_source_dir/scripts/schedule-backup" run "$studyroom_env" --state-dir "$STUDYROOM_STATE_DIR"
```

Linux 定时任务的日志在 systemd journal。普通用户安装时，将下面的占位值替换为 install 输出的 scheduler label（不含 `.timer` / `.service` 后缀）：

```sh
studyroom_backup_unit='com.campus-study-room.backup.<install输出的实例摘要>'
journalctl --user -u "${studyroom_backup_unit}.service" -n 100 --no-pager
```

root 安装的是系统 timer，查日志时省略 `--user`，并使用有权限读取系统 journal 的账号。Linux 安装器虽创建 `backup-scheduler.log`，服务标准输出并未重定向到它，不能以这个空文件判断任务未运行。macOS LaunchAgent 的标准输出/错误才写入该文件：

```sh
tail -n 100 "$STUDYROOM_STATE_DIR/backup-scheduler.log"
```

每小时检查，每个 **UTC 日期**最多自动生成一次有效备份；当日成功文件丢失/损坏会补做，失败不更新成功标记。`run` 可能备份并触发保留期清理，不是只读状态查询；确需当天追加时才加 `--force`。新备份成功后，仅清理超过七天且命名、元数据、摘要匹配的 dump/json 对，未知文件、损坏文件、partial 和恢复报告保留。

移走源码或改变 Python 路径前，从新验证源码重新执行 install，更新任务中的绝对路径并核对 status。取消调度时用相同参数执行 `remove`，它只卸载该实例任务，保留备份/数据/日志。调度不能代替独立存储；七天保留和 Linux 定义的既有验证范围见[调度参考](../modules/release-operations.md#每日备份与七天保留)。

## 常见问题定位

| 问题 | 先检查 | 处理方向 |
| --- | --- | --- |
| 浏览器连不上，Docker 正常 | context、project、WEB_PORT、绑定地址、web 日志 | 使用所选实例入口；远程回环服务通过 SSH 转发 |
| 页面打开但 API 502/503 | core/repair 健康、maintenance/pending、requestId | 区分后端不可用与维护流程；按日志和失败阶段修复 |
| 数据库密码错误 | 是否切错状态目录、Compose project 或秘密文件缺失 | 找回对应原秘密；不要初始化替代值、删卷或重建库 |
| 演示账号密码不对 | 访问地址和状态目录是否对应、账号是否已重置 | 看[账号说明](../development/demo-accounts.md)，不要混用其他环境密码 |
| Mailpit 没收到预期邮件 | 业务是否已提交、core 日志/邮件任务、Mailpit 状态 | SMTP 是本地收件，不是外部投递；不要修改已提交业务来“重试” |
| GitHub job queued 或 skipped | 最新提交、可信仓库/actor、runner 在线与串行队列 | 走[CI 排障](github.md#失败时检查什么)，不跳过完整检查和容器/浏览器验证 |
| 原生后端修改未生效 | JAR 副本、PID 身份、core/repair 日志 | 使用[原生重启](../development/local-setup.md#修改代码后如何生效)，不混用容器发布 |

处理后重复最初失败的检查，记录实际版本、现象及结果。历史演练和新故障分别记录，不用早先通过的证据替代本次验证。
