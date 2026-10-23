# 发布、数据库版本与应用回退

人类操作步骤见[升级、回退与恢复手册](../operations/runbook.md)。本页解释发布状态和失败门禁；兼容演练属于发布准备，不是在故障现场直接套用的恢复脚本。对应 REQ-ENG-05/07。

`scripts/studyroom.py` 负责整套 core/repair/web 镜像发布，独立迁移器负责 schema。数据库和秘密保存在固定 Compose 项目及 `STUDYROOM_STATE_DIR`，升级/回退不会删除卷、重建会话表或覆盖已有秘密。只更换源码目录时必须继续使用原状态目录与 Compose 项目名，否则新秘密无法访问旧数据库卷。

| 目标 | 正常入口 | 改动范围 |
| --- | --- | --- |
| 升级固定应用组合 | deploy / import-release | 维护窗口、备份、向前迁移、三应用及版本验证 |
| 应用回退 | rollback | 只切换三应用，不迁移/seed；数据库结构保持 |
| 保存恢复点 | backup / schedule-backup | 生成经过校验的 dump 和配对元数据 |
| 验证恢复能力 | restore | 在当前 PostgreSQL 新建隔离库；在线库不切换 |

逐项操作及预期结果见[日常手册](../operations/runbook.md)。跨 schema 兼容演练是高级发布准备；不应替代以上日常入口。现有恢复验证不能宣称完成异机数据搬迁或线上切换。

## 状态与门禁

外部清单在访问 Docker 或创建维护状态前先检查字段：releaseId 为 1–96 个安全字符，提交为完整 40 位小写十六进制，镜像内容 ID 为 `sha256:` 加 64 位十六进制，镜像引用不能包含换行/空白/环境赋值，兼容 family 必须是非空安全 token。离线导入复用同一字段规则，避免路径穿越和 `release.env` 注入。

| 状态文件 | 真实含义 |
| --- | --- |
| `current-release.json` | 最后完成私网校验、公开入口恢复的应用组合 |
| `schema-release.json` | 迁移版本的持久记录：来源清单及摘要、实际 PostgreSQL 版本和三个 schema 的 Flyway history 快照及摘要 |
| `pending-release.json` | 正在执行或失败的尝试，包括目标清单、可能改变 schema 的清单、执行阶段和可读到的失败后数据库快照 |
| `release.env` | 当前应用和可选离线运行镜像引用 |
| `compatibility/B--A.json` | B 数据库结构与 A 三镜像实际兼容的演练证明 |

应用回退到 A 后，`current-release` 变成 A，`schema-release` 仍保留 B。不能用当前应用版本推断数据库版本。迁移逐 schema 执行，失败时前面的 schema/脚本可能已经提交，因此 pending 从 `migration_started` 起保守记录整个尝试的迁移集合；失败后不自动删除这些证据。

正常 deploy（包括离线 import）要求目标迁移集合覆盖已知数据库结构，已有脚本的摘要必须相同。旧源码目录重新导入旧包也不能绕过这条门禁。Flyway 默认忽略 future migrations，不能把它当作阻止旧应用越过回退检查的保证。

应用回退禁止执行迁移和 seed。跨 schema 回退必须先有证明，且证明绑定：两个完整发布清单的规范化 SHA-256、两个提交、三应用的实际内容 ID、实际 PostgreSQL 版本和 session/core/repair 全部 Flyway history。只写 `result: passed` 或复用相同 commit 的其他镜像证明无效。执行回退前还会再次读取目标数据库；与演练快照不同则拒绝。

若没有 pending，而实际 PostgreSQL/Flyway 版本指纹与持久记录不同，部署拒绝并要求排查迁移历史漂移。这里的指纹不是全部 DDL 的摘要，无法发现未改变 Flyway history 的手工改表；运行账号无 DDL 权限，任何维护账号改表仍须通过版本化迁移。发现失败 pending 后，恢复依据实际迁移历史和可能已执行的迁移判断；不会仅看上一个成功应用版本。

## 失败处理

发布先进入维护并暂停任务，确认请求与任务排空后停止两后端、备份、迁移、启动三应用、核对版本和私网 smoke，再恢复公开入口。公开入口也成功后才更新成功记录并清除 pending。备份从创建时就使用 0600 权限。

停止应用后的失败先保留维护与任务暂停，写出失败阶段及 PostgreSQL/Flyway 快照（数据库不可用时明确标记）。仅在新三应用启动、版本校验或私网 smoke 阶段失败时，才尝试有条件自动恢复上一成功版本：重新核验上一版完整清单和三个镜像 ID、兼容 family；迁移集合不同时还要通过与实际 PostgreSQL/Flyway 版本指纹绑定的证明。没有证明不会自动启动旧应用。

自动恢复在原部署锁内执行，不递归调用 deploy。只切换三应用，保持新数据库；再次核对实际容器 ID、三个版本、私网 smoke 和公开入口。全部通过后恢复服务，把失败尝试及恢复结果存入 `release-failures/`，保留新 `schema-release`，清除 pending。新版本部署命令仍返回失败，避免将“恢复了上一版”报告成“新版本已发布”。迁移失败、证明缺失、上一版身份不符或恢复再次失败时，维护及 pending 均保留。

恢复尝试若在排空阶段失败，不会删除上次失败留下的维护标志。公开入口恢复失败不触发自动恢复，也不会被记成成功发布。

恢复流程：检查 pending 阶段、服务日志及实际数据库；修复同一发布后重新 deploy，或取得匹配当前数据库的兼容证明后执行 `scripts/rollback`。不得修改/删除已应用迁移、手工清空 pending 来绕过检查，或回退数据库结构。

## V1 → V2 → V1 的可执行演练

本节会创建并修改业务数据、切换应用版本，仅用于明确隔离的兼容演练项目。日常升级、回退、备份和隔离恢复分别使用[运维手册](../operations/runbook.md)，不能将这段演练脚本用于当前交付实例。

工具：`tests/integration/release_compatibility.py`。它只允许显式隔离的 dev/test 项目，要求环境 `compose.env` 中同时具有：

```dotenv
COMPATIBILITY_SANDBOX=true
COMPOSE_PROJECT_NAME=studyroom-compat-rehearsal
```

使用独立状态目录、端口与数据库卷；不得将上述标记加入日常环境。准备两个已提交、固定镜像组合的版本：A 为基线，B 仅新增一个业务允许的可空 TEXT 列（当前探针显式允许 `core.reservation.operator_note` 或 `repair.repair_order.assigned_to_name`），同时保持 Compose、运行依赖与既有迁移不变。可使用独立演练分支承载该扩展，不修改已经部署过的迁移。先构建 A/B 镜像，再捕获会话，以免构建耗时超过 30 分钟的会话闲置期。

在隔离项目部署 A 后，用 B 源码中的工具捕获真实业务与 V1 登录会话：

```sh
export STUDYROOM_STATE_DIR=/absolute/path/to/isolated-compat-state
python3 tests/integration/release_compatibility.py capture test \
  /absolute/path/to/A/manifest.json /absolute/path/to/private-fixture.json \
  --expanded-column core.reservation.operator_note
```

capture 会核对 A 三镜像及版本，通过真实 HTTP 创建独立教室、座位、预约、报修，并保存原用户 Cookie、业务 ID 和版本。fixture 以 0600 创建，含会话秘密，必须留在忽略目录，不能作为发布工件或提交内容。

随后用 B 的发布器正常 deploy B。其新增迁移必须真实执行。再运行：

```sh
python3 tests/integration/release_compatibility.py verify test \
  /absolute/path/to/B/manifest.json /absolute/path/to/A/manifest.json \
  /absolute/path/to/private-fixture.json \
  "$STUDYROOM_STATE_DIR/compatibility/B--A.json"
```

证明文件名必须使用真实 `releaseId`（例如 `release-b--release-a.json`），上述 B/A 仅是占位符。verify 的实际步骤：

1. 核对 B 三个运行容器的 image ID 与 `/version`，读取实际 Flyway history，并确认原 V1 Cookie 在 B 仍能读取本人数据。
2. 确认扩展列存在且允许 NULL，写入独立非空哨兵值。
3. 维护、排空后仅切换三应用到 A，不运行迁移或 seed。
4. 用原 Cookie 访问 core 和 repair，核对原业务 ID、状态、版本；通过 A 分别取消预约、已批准整室申请与报修，再读取确认写入已持久化。
5. 确认扩展列哨兵值未丢失、Flyway history 未变化，三镜像和三个版本精确匹配 A。
6. 将隔离项目恢复 B 并再次核对版本和入口，所有步骤通过后才生成证明。不在证明中保存 Cookie、密码或业务完整响应。

演练失败不会生成通过证明，隔离项目保留维护状态供排查。成功后运行真实门禁入口 `scripts/rollback test <A manifest>`；再次确认三应用版本为 A、`schema-release` 仍是 B、原 Cookie 可读、扩展列仍在、预约、整室申请和报修的取消状态保留。演练不能使用重登录代替原会话验证。

## 已完成的自动检查与实际证据

`python3 -m unittest discover -s tests/ops -v` 包含旧包重导降级拒绝、跨 schema 成功回退仍保留新 schema 记录、证明与镜像/实际数据库不一致、部分迁移失败、恢复时的维护标志、公开入口失败不记录成功、有证明的自动恢复、缺证明不恢复、恢复再次失败保持维护等回归。单测保留原有发布和离线包校验，不替代真实 Docker 演练。

兼容工具的实际运行及 A/B 发布清单、数据库 fingerprint、三镜像 ID、原会话与数据断言结果由发布验收记录补充。没有实际生成证明之前，不能将应用回退兼容性标记完成。

V2 增量迁移 `V20261022150000__reservation_operator_note.sql` 为预约增加可空运维备注列，不改变状态或裁决，旧版写入无需提供新字段。兼容工具在A创建预约、两人两级通过的整室申请与报修，分别保留学生和申请人的原会话，验证B读到原记录、A在新schema仍能读写三类业务。该列的非空哨兵只在明确隔离演练库注入；[A/B完整演练](../evidence/release/README.md)已归档。维修姓名列的兼容探针使用报修ID，后续版本必须生成对应新证明，不能复用A/B证明。

## 每日备份与七天保留

实际安装/检查/补做/取消命令集中在[备份调度手册](../operations/runbook.md#安装或检查每日备份调度)。源码新装可选 `install.sh <env> --schedule-backups`；既有或离线实例使用 schedule-backup 并显式传入状态目录。install 启用主机任务，可能立即触发；status 查询；run 可能生成备份并执行保留期清理。

`scripts/backup_schedule.py` 每小时检查，以 UTC 日期判定当日成功；标记、dump、配对元数据、SHA 和完整解码记录一致且不是符号链接时才跳过。丢失/损坏则补做，失败不更新标记；force 可追加。备份持有发布共用的环境锁，发布前仍独立备份，不被当日成功标记跳过。

备份采用 pg_dump 自定义格式，TOC 和完整解码通过后才把 0600 的 partial 发布为 dump；配对元数据记录 SHA、时间、实际 PG/Flyway、应用清单和快照恢复点，不含秘密。只有新备份成功后，才清理超过七天且命名/元数据/摘要匹配的 dump/json 对；未知、损坏、链接、partial 和恢复报告保留。

macOS 使用当前用户 LaunchAgent，Linux root 使用系统 timer、普通用户使用 user timer。任务保存解释器、源码和状态绝对路径；源码迁移后需重新 install。会话、主机或 Docker 不可用会影响调度，工具不修改 linger 或 Docker 权限。备份日志在 `backup-scheduler.log`，remove 仅卸载该实例任务，不删除数据/日志。

这是本机保留策略，不是异机灾备。2026-10-22 的[调度证据](../evidence/release/scheduled-backup.json)覆盖真实 macOS 备份与 launchd、截断归档反证；七天保留通过临时文件测试，未经历真实七天；Linux systemd 定义已校验，未在独立 Linux 宿主实际加载。此范围不随文档更新扩大。

## GitLab 合并历史

当前五门禁、受保护 Runner 引用、Fast-forward/default no-squash 和发行制品流程统一见[GitLab 手册](../operations/gitlab.md)。历史发布记录保留原提交与 pipeline，后续文档 HEAD 不改变运行版本或旧验收结果。
