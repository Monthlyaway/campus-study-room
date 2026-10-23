# 当前实例接手卡

[运维导航](README.md) · [巡检与排障](runbook.md) · [演示账号](../development/demo-accounts.md)

<a id="codex-lb-部署准备快照"></a>

## codex-lb 生产快照

以下为 **2026-10-23 v0.2.2 已部署后的核对快照**，不是自动刷新的健康页面。正式 Actions 的 verify/publish/deploy 均成功，公网 HTTPS 首页与三应用版本接口均为 200，11 个课程账号已实际验证登录。备份调度首次安装的 systemd unit 错误已在宿主修正，实际备份、隔离恢复与 42 表内容核对通过；独立桌面/375px 只读 UI 也已通过；资源观测只覆盖文档列明的 12 次样本，生成器修复已通过针对性验证，完整结论以对应 PR Checks 为准。完整运行身份、资产校验及实际边界见 [v0.2.2 发布记录](../evidence/github-production-v0.2.2.md)；旧 [v0.2.1](../evidence/github-production-v0.2.1.md) / [v0.2.0](../evidence/github-production-v0.2.0.md) 失败不改写为成功。

| 项目 | 已核对事实 / 配置位置 |
| --- | --- |
| 主机与公网入口 | codex-lb；`https://49.51.193.169` |
| 正式运行版本 | `v0.2.2` → `524134c7ad9b730538f535aa2245c7f8f8d611c0`；Actions 39955632806 / attempt 1 三 job 全部 success |
| 发布与运行绑定 | 稳定 Release 已发布；6 个资产 digest、3 个已测试 image ID 与发布组合核对一致，完整摘要见发布记录 |
| HTTP / HTTPS | TCP 80/443 可达；HTTP 308 到 HTTPS；证书验证通过，HTTPS 首页及 web/core/repair 版本接口均为 200 |
| HTTPS 服务 | 宿主 nginx 系统服务 active；回环 upstream `127.0.0.1:18443` |
| 证书 | Let’s Encrypt YE2；SAN 为 IP `49.51.193.169` |
| 首次证书有效期 | `2026-10-23T08:15:52Z` 至 `2026-10-30T00:15:51Z`；真实续期后需重新查询 |
| Certbot | 5.4.0，ACME 库 5.4.0；`/home/ubuntu/.local/tools/certbot/bin/certbot` |
| TLS 持久状态 | `/home/ubuntu/.local/state/campus-study-room/tls`；私钥不提交、不上传报告 |
| 续期任务 | `campus-study-room-certificate.timer` enabled/active，每六小时检查，最多十分钟随机延迟 |
| 续期演练 | `renew --dry-run --run-deploy-hooks` 实际通过，模拟续期及 nginx reload hook 成功；没有更换生产证书 |
| GitHub runner | `codex-lb-campus-study-room` online；2.338.1，专用系统服务 enabled/active |
| Runner 标签 | `self-hosted`、`Linux`、`X64`、`campus-study-room` |
| 运行工具 | Docker 29.1.3、Compose 2.40.3、GitHub CLI 2.103.0；Node 24.22.1、Java 21.0.12.1 |
| 开发 checkout | `/home/ubuntu/Codes/campus-study-room`；代码编辑、commit 与 push 均在该服务器 |
| 生产持久状态 | `/home/ubuntu/.local/state/campus-study-room/prod`；与 CI/浏览器测试状态隔离 |
| 不可变发布源码 | `/home/ubuntu/Deployments/campus-study-room/releases/v0.2.2/source`；后续文档 HEAD 不改变此运行版 |
| 课程资源 | 11 个账号已验证 HTTPS 登录，3 房间 / 30 座位；无预造预约、审批或工单，账号入口见[演示账号](../development/demo-accounts.md) |
| 独立生产 UI | 学生/管理员 × 1440px/375px 四上下文通过只读试用及会话/路由权限；手机管理表格右侧列可达，无业务写入 |
| 已完成资源观测 | 13:14:20.899763Z–13:19:50.924237Z，12次采样：四入口200/TLS0、版本一致、5容器healthy且重启/OOM为0；该窗口无PR CI负载 |
| 备份调度 | `com.campus-study-room.backup.8e4b5303d478.timer` enabled / active waiting，`Linger=yes`；首次 unit 配置错误已修正，生成器修复待 PR 验证 |
| 最近备份与恢复 | `backups/20261023T131356842634Z.dump`；隔离库 `studyroom_restore_20261023131409` 的 42 表内容一致，在线库未改，详细摘要见发布记录 |
| 备份日志 | `journalctl --user -u com.campus-study-room.backup.8e4b5303d478.service`；不以 Linux 的空 backup-scheduler.log 判断任务状态 |

该服务器约 2 核/2 GiB RAM、4 GiB swap；已通过的串行 CI 和成功部署不代表原 4 核/8 GiB 性能或 CI 与生产共同运行容量已验。已通过的备份/恢复只覆盖本次恢复点，独立 UI 只执行只读操作；资源样本没有覆盖后续 PR CI 负载。本次生成器修复和交付文档按[贡献指南](../contributing.md)的 PR 流程验证，完整结论以对应 PR 的 Checks 为准，不由当前生产状态推断新提交通过。生产状态不可作为普通测试默认目标；账号凭据只在[演示账号](../development/demo-accounts.md)维护，本页不复制密码。

日常证书查询与排障见[GitHub 运维手册](github.md#ip-https-与续期)。公网仅开放应用 HTTP/HTTPS 入口；数据库、内部 API、Mailpit 保持私网/回环，SSH 保持既有规则。

## 历史 Mac 实例（停止作为开发入口）

以下保留原本机交付记录，不能用于当前 codex-lb 的配置、密码或测试目标。后续代码开发和 push 均在服务器执行。

这是截至 **2026-10-23 归档交付**的本机实例记录，不是每次打开文档时自动刷新得到的健康状态。接手时按[巡检步骤](runbook.md#先确定操作对象)确认实际运行情况。下列绝对路径只属于这台机器；新服务器按[安装指南](installation.md)选择自己的路径。

| 项目 | 归档值 |
| --- | --- |
| Web | [http://localhost:19282](http://localhost:19282) |
| Mailpit 测试收件箱 | [http://localhost:19227](http://localhost:19227) |
| 环境名 | `test` |
| Compose project | `studyroom-portable-install` |
| 持久状态目录 | `/Users/agiuser/Codes/campus-study-room/.local/portable-install` |
| 已导入运行版的源码目录 | `/Users/agiuser/Codes/campus-study-room/.local/portable-source-final-v6`，无 `.git` |
| Release / tag | `v0.1.0` |
| 应用发布号 | `r-20261023T022529-1079900` |
| 运行源码 S | `10799003cd38a28629dc5ea39835c8313b1e9d79` |
| 本轮文档重构前的仓库 HEAD H | `d397d05124ae490d6ada762296909b910e82a7e0` |
| 平台 | linux/arm64 |

后续文档提交不会自动更换正在运行的镜像。不要要求运行版的 S 等于后续文档 HEAD，也不要为消除这个差异重新安装应用。默认 18080/18082 或原生 8000 不代表此版本；该实例也不作为开发测试的写入目标。

## 登录与邮件

账号用途见[演示账号](../development/demo-accounts.md)。初始随机密码位于上述状态目录的 `secrets/demo_password`，由获准的环境维护者在本机私下管理。不要把值复制进聊天、截图、日志或 Git；管理员重置后的账号以新密码为准。

Mailpit 接收本地测试邮件，不向真实外部邮箱投递。邮件及业务数据属于此实例状态，不能因排障而清空。生产 SMTP、域名与 TLS 未在此实例验收。

## 当前可搬运的安装包

通过可信渠道取得整包和相邻校验文件，按[离线安装](installation.md#从离线包安装新环境)验证后使用。

| 项目 | 值 |
| --- | --- |
| 运输包 | `/Users/agiuser/Codes/campus-study-room/.local/releases/v0.1.0/campus-study-room-v0.1.0-linux-arm64.tar.gz` |
| 校验文件 | 上述完整路径后追加 `.sha256` |
| 大小 | `414289744` 字节 |
| SHA-256 | `7b143161a31778be71ebabc20a1dffbc8d744cda454335df1fa82b3f571be7ee` |
| 解包顶层目录 | `campus-study-room-v0.1.0-linux-arm64/` |
| 原始完整包目录 | `/Users/agiuser/Codes/campus-study-room/.local/releases/r-20261023T022529-1079900/portable` |

`.local/releases/` 根目录还保留一个旧 V5 同名运输包；它与上表 `v0.1.0/` 子目录中的 V6 包不同。按完整路径和摘要识别，不能只看文件名。运输包不含这台机器的数据库或秘密。

## 已验收事实与后续检查

V6 导入、三版本、原状态保持、浏览器 6/6 和 V6→V5→V6 回退按各自时间归档于[发布证据](../evidence/release/README.md)。同一运行源码 S 的 feature/main/tag 五 job 通过见[正式发布记录](../evidence/gitlab-release-v0.1.0.md)；后续文档基线 H 的 main [pipeline 684208](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/684208)也已通过五门禁。这些是对应提交的历史结果，不替代新改动的验收。

日常接手依次核对目标 Docker context、状态配置、三个公开版本、入口和日志。升级复用此状态目录及 project，使用新源码与新包；回退使用正式 rollback 门禁。不得对这个已发布实例重新运行 `install.sh`，也不要以重新生成秘密解决密码或数据库连接问题。
