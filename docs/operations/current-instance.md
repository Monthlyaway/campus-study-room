# 当前本机实例接手卡

[运维导航](README.md) · [巡检与排障](runbook.md) · [演示账号](../development/demo-accounts.md)

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
