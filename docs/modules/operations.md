# 运维模块：配置、状态与操作边界

对应 REQ-ENG-03/04/05/07/08。人类操作入口在[安装与运维](../operations/README.md)，首次安装见[安装指南](../operations/installation.md)，已有实例的巡检、升级与故障处理见[运维手册](../operations/runbook.md)。本页说明这些操作依赖的状态与约束。

## 配置身份

一套实例由环境名、绝对 `STUDYROOM_STATE_DIR`、Compose project 和 Docker daemon 共同确定。默认状态为 `<源码>/.local/<env>`，可由环境变量覆盖；默认端口见[环境表](../operations/README.md#环境如何区分)。`compose.env` 的 `ENV_NAME` 必须等于命令参数。

新建第二套同名环境需要不同状态目录、project 和端口，不能只换路径继续指向原卷。升级或更换源码路径则保留原状态/project/秘密。`scripts/init-env` 覆盖默认状态路径时必须显式传 `--directory`，不会自动使用 `STUDYROOM_STATE_DIR`；配置加载器在文件缺失时会调用初始化，因此对已有实例调用 `status` 前必须确认 `compose.env` 存在。

正常容器部署只公开回环 Web/Mailpit，数据库和两后端在私网。原生开发叠加 `infra/compose/development.yml` 才发布回环 PG/SMTP；原生 core/repair 使用固定 8081/8082，一次只运行一套。模式选择见[开发指南](../development/local-setup.md)。

## 状态目录地图

```text
<持久状态目录>/
├── compose.env                    # 人工维护的实例配置
├── secrets/                       # 随机秘密，仅私有保管
├── release.env                    # 发布器固定的运行镜像/版本覆盖
├── current-release.json            # 最后成功应用组合
├── previous-release.json           # 上一次成功应用组合（若有）
├── schema-release.json             # 数据库迁移身份，独立于应用版本
├── pending-release.json            # 进行中/失败尝试，成功后清除
├── control/                       # maintenance、jobs-paused 控制标记
├── release-history/               # 成功发布清单
├── release-failures/               # 自动恢复成功后保留的失败尝试
├── compatibility/                 # 与镜像及实际数据库绑定的演练证明
├── backups/                       # dump、配对元数据、隔离恢复报告
├── scheduled-backup.json           # 当日有效备份标记
├── backup-scheduler.log            # 主机调度输出
├── bootstrap-runtime.json         # Ubuntu 引导运行时结果（若使用）
├── bootstrap-state.json            # Ubuntu 引导配置结果（若使用）
└── run/、core.pid、repair.pid、*.log # 仅原生开发运行文件
```

不是所有文件在新装后都会出现。数据库及 Mailpit 邮件实际在对应 Compose project 的 Docker 命名卷中，**不在源码目录或运输包中**。只复制此目录不等于复制完整数据库；数据库备份、原秘密及上线切换需分别管理。目录和秘密权限、备份校验不构成异机灾备。

| 内容 | 谁写入 | 接手与升级规则 |
| --- | --- | --- |
| `compose.env` | init-env 首次生成，维护者配置 | 保持 ENV_NAME、路径、project、端口对应；不公开打印整个文件 |
| `secrets/*` | 首次初始化 | 目录 0700、文件 0600；已有秘密不覆盖，不提交、不进普通制品 |
| `release.env` | 发布器 | 加载优先于 compose.env 中的版本/镜像默认值；不要人工降级或删除 |
| current/previous/schema/pending、control | 发布器 | 排障依据；不能手改成功、删除 pending 或解除维护来绕过门禁 |
| history/failures/compatibility | 发布器/演练工具 | 保留真实版本范围，不能人工生成 passed |
| dump 与同名 json | 备份器 | 必须成对保存；partial 不是成功备份 |
| scheduler 文件/日志 | 调度工具 | 路径改变后重新 install；status 是查询，run 可能写备份并清理过期对 |
| PID/JAR/原生日志 | 原生开发入口 | 不与容器实例身份混用，不凭 PID 数字杀进程 |

`compose.env` 中端口、PUBLIC_BASE_URL、回环绑定及 project 是实例输入；`release.env` 只允许 RELEASE_ID、RELEASE_COMMIT、三应用镜像及 PostgreSQL/Mailpit 镜像键。`MAIL_MODE=smtp`、SMTP_HOST=mailpit、SMTP_PORT=1025 对应当前本机邮件收件模式，不能证明真实外部投递。prod 要求 HTTPS origin、Secure Cookie 和回环应用绑定，使用已验证 Release 的部署路径；不能只将测试配置的 `ENV_NAME` 改为 prod。具体配置见[生产准备](../operations/github.md#首次准备生产状态)，TLS 实测见[当前实例](../operations/current-instance.md)。

## 秘密与数据

公共运行秘密为 postgres/core/repair/migration 数据库密码、内部服务密码和签到加密密钥。local/dev/test 另有演示初始密码 `demo_password`；prod 生成首次管理员秘密 `admin_password`，不生成 demo 密码，部署器拒绝生产状态中的 demo 密码和 demo 账号。只说明文件名与位置，不在文档输出其值。[演示账号](../development/demo-accounts.md)统一维护账号与角色，不在运维手册复制另一份账号表。

业务角色不能执行 DDL，迁移由独立 `studyroom_migration` 执行；审计表对业务角色只允许查询和插入。容器入口读取私有挂载后，Java 应用以 UID 10001 运行。具体身份与权限设计见[架构安全](../architecture/security.md)。

搬迁已有数据时必须保留原秘密，尤其是 `checkin_key`，重新生成会使既有加密签到码无法读取。离线包不含秘密、数据库、会话、日志或邮件。现有 restore 只创建隔离库，不执行线上数据切换；操作见[备份与恢复验证](../operations/runbook.md#创建一次备份)。

## 锁与发布约束

`scripts/studyroom.py` 用环境文件锁串行化安装、依赖/原生启停、迁移/seed、发布、回退、备份和恢复；首次初始化有独立锁。锁按状态目录隔离，因此不同状态目录复用同一个 project 并不能获得安全隔离，必须先正确配置身份。

旧集成入口和原生构建按“环境锁→工作区锁”顺序保护迁移及共享构建产物，反向释放。纯 HTTP 阶段只持环境锁；内部可复用函数不重复取锁。原生 PID 必须匹配该环境运行 JAR 的完整命令、健康与启动时间，否则拒绝复用或发信号。

正式发布先维护/暂停、排空，再停止后端、备份、迁移、启动三应用、核对版本和私网 smoke，最后恢复公开入口并记录成功。回退不运行 down migration；完整状态机和证明条件见[发布机制](release-operations.md)。`release.env` 固定三应用及离线运行镜像，后续操作不能退回 development 默认值。至少保留前三个已验证成功发布的源码、镜像与清单，禁止自动删除命名卷。

## 验证与交付范围

当前实施状态由[交付进度](../progress.md)统一记录；本机安装/回退/恢复的各版本事实见[发布证据](../evidence/release/README.md)。现行 Actions、runner、PR 合入与 GHCR/Release 见[GitHub 手册](../operations/github.md)，GitLab 规则保留为历史。历史诊断不再作为本页的待办状态；后续成功不改写旧流水线失败。

源码对应 [studyroom.py](../../scripts/studyroom.py)、[init-env](../../scripts/init-env)、[Compose](../../infra/compose/compose.yml)、[备份调度](../../scripts/backup_schedule.py)。静态定义与单测不能代替真实新环境验收。
