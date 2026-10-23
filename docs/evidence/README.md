# 验收与历史证据索引

[文档首页](../README.md) · [当前进度](../progress.md) · [39项需求](../requirements-traceability.md) · [运行接手卡](../operations/current-instance.md)

这里回答“何时、在哪个版本、用什么条件验证过什么”，不提供日常操作步骤。使用、开发和维护从文档首页的五种路线进入。

## 先看这些

| 要核对什么 | 证据与边界 |
| --- | --- |
| 最新 v0.2.1 验证为何停止 | [v0.2.1 失败记录](github-production-v0.2.1.md)：PR #2 完整 CI 通过并 rebase merge；tag 全仓检查通过，浏览器 1/6 失败，publish/deploy 跳过，无本版本的新 Release/package，生产仍待部署 |
| GitHub / codex-lb 的 v0.2.0 验证与发布中断 | [v0.2.0 记录](github-production-v0.2.0.md)：PR rebase merge、Linux AMD64 全仓检查、三镜像隔离部署与浏览器 6 项通过；tag verify 成功，publish 失败、deploy 跳过，生产/课程账号/备份恢复待执行 |
| 公网 IP HTTPS 与续期准备 | [当前接手卡](../operations/current-instance.md#codex-lb-部署准备快照)：有效 IP 证书、外部 TLS 校验及续期 dry-run 通过；应用最近响应为 502，不等同生产上线 |
| v0.1.0发布、线性合并、完整五门禁 | [GitLab发布记录](gitlab-release-v0.1.0.md)，运行源码为10799003；纯文档基线d397d05与运行版本分开 |
| V6桌面与手机六流程 | [浏览器最终报告](frontend-release-final-v6.md)，6/6、113.061秒；独立报告隐私探针1/1 |
| V6离线导入与运输包内容 | [导入记录](release/final-v6-portable-import.json)、[归档校验](release/final-v6-archive-verification.json)，450源码文件、五镜像，linux/arm64 |
| V6→V5→V6回退 | [回退记录](release/final-v6-v5-rollback.json)，保留会话、数据与schema；不代表降级数据库 |
| 性能与重启恢复 | [R4性能](performance.md)、[R4 core恢复](core/restart-recovery-v4.md)，保留原运行身份，不冒称V6重跑 |
| V6提交前限定隐私扫描 | [审计报告](runtime-audit-final-v6.md)，仅覆盖报告列明的输入、已知值和截止时刻 |
| 文档重构的五角色意见与核对 | [2026-10-23文档评审](../reviews/documentation-2026-10-23.md)，静态评审与实际运行分别记录 |
| 过去的推进、失败与修复 | [旧进度归档](delivery-ledger-2026-10-23.md)，来源为d397d05的进度页 |

## 读证据时注意

- S、R4、V5、V6等是报告中的运行身份简称，必须结合完整commit、releaseId、镜像ID和时间。README或文档HEAD变化不会改变已运行的镜像。
- 通过只覆盖报告中的断言与条件；取消、失败、跳过和未执行不能改成通过。旧失败的后续修复另有运行记录。
- 本机交付、CI 隔离部署、宿主 HTTPS 与生产业务上线是不同验证对象。旧 GitLab / ARM64 报告保留原范围；新增 codex-lb 记录已覆盖 Linux AMD64 CI、Ubuntu SSH/systemd 配置及公网 IP TLS，但生产业务仍待部署。不声称隔绝外网、外部 SMTP 实际收信、线上数据库切换或同机 CI/生产容量已验收。
- `output/`、`.local/`是忽略的本机原始产物/状态，普通clone不带它们。报告中的这些路径说明原保存位置，不是用户手册必须下载的附件；CI制品按对应job权限与保留策略访问。需要重现时走[测试指南](../development/testing.md)。
- 不公开密码、Cookie、CSRF、Runner令牌或未审核日志。一次限定扫描不能保证后续全部文件；证据有自己的截止时间。
- 本轮保留已有34份历史Markdown原字节，另建此索引和新报告；历史JSON、日志和截图也不因文档整理而改写结果。

## GitHub 与 codex-lb

- [v0.2.1 浏览器验证失败记录](github-production-v0.2.1.md)：桌面真实 409 弹窗关闭后缩至 375px 的动画越界与检查读取竞争；修复验证尚未完成。
- [v0.2.0 发布验证与中断记录](github-production-v0.2.0.md)：按实际 PR、tag、run/attempt 和镜像身份核对；修复后的新版本另立记录，失败版本不回填为上线成功。

## 全部历史文字报告

### 核心业务与接口

- [D / M1 / M2 剩余验收矩阵](core/acceptance-matrix.md)
- [Core 验收证据](core/acceptance.md)
- [M1/M2 与维修级联事务验收](core/atomic-workflows.md)
- [独立前端试用：M1 与 M2](core/browser-review.md)
- [Core API 契约与协议边界验收](core/contracts.md)
- [默认本地检查与 GitLab 共用验收计划](core/default-check.md)
- [R4 Core真实重启后的期限补偿验收](core/restart-recovery-v4.md)
- [Core 真实重启后的期限补偿验收](core/restart-recovery.md)
- [M1/M2 类型化命令、请求边界与管理权限](core/typed-inputs.md)

### 维修与跨服务

- [M3 负面矩阵与 D-06 真实 SMTP 验收](repair-acceptance.md)
- [维修人员姓名展示回归](repair-assignment-name.md)
- [维修资源故障、审计关联与日志扫描](repair-fault-matrix.md)
- [M3 时间精度：Linux CI 失败反证与修复](repair-timestamp-precision.md)
- [维修模块实际验收：2026-10-22](repair-validation-2026-10-22.md)

### 前端与真实试用

- [最终 V5 真实浏览器验收（2026-10-23）](frontend-release-final-v5.md)
- [最终V6真实浏览器验收（2026-10-23）](frontend-release-final-v6.md)
- [R4 真实浏览器验收（2026-10-23）](frontend-release-v4.md)
- [发布版前端验收（2026-10-23）](frontend-release.md)
- [真实资源同步故障下的前端验收](frontend-sync.md)
- [2026-10-22 离线发布包浏览器验收](../../tests/browser/REVIEW.md)

### 发布、交付与GitLab

- [GitLab 容器任务的 Alpine HTTPS 镜像修正](gitlab-alpine-mirror.md)
- [专用 Runner 配置的 GitLab 服务端模拟](gitlab-config-validation.md)
- [GitLab 容器发布门禁：DinD 诊断与隔离 Runner 进展](gitlab-dind-startup.md)
- [GitLab 失败门禁实测](gitlab-negative-gate.md)
- [v0.1.0 GitLab 发版与源码绑定](gitlab-release-v0.1.0.md)
- [专用 GitLab Runner 注册与启用记录](gitlab-runner-registration.md)
- [本机发布、回退与离线安装演练](release/README.md)

### 性能、日志与隐私

- [R3最终运行产物与待交付源码隐私审计](final-runtime-audit.md)
- [最终候选源码静态审计](final-source-audit.md)
- [结构化日志真实进程验证](logging.md)
- [性能验收证据（T50-03）](performance.md)
- [最终 V5 限定隐私审计](runtime-audit-final-v5.md)
- [最终 V6 限定隐私审计](runtime-audit-final-v6.md)
- [R4运行产物与源码增量隐私审计](runtime-audit-v4.md)

机器可读记录集中在 [core](core)、[release](release)、[performance](performance)；先读对应文字报告确定版本，再读取它链接的JSON。不要将目录中较新文件名自动视作正式交付选择。
