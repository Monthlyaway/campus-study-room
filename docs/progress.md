# 当前进度与交付状态

[文档首页](README.md) · [39项P0追踪](requirements-traceability.md) · [验收证据](evidence/README.md)

更新时间：2026-10-23。在用户确定的本机与可迁移交付范围内，P0 的39项需求均已验证，MS0～MS6完成；v0.1.0已发布。正式服务器、域名/TLS、外部SMTP及AMD64安装未验收，P1尚未启动。边界见[ADR 0002](adr/0002-delivery-and-gitlab.md)。

## 当前交付

| 内容 | 状态与入口 |
| --- | --- |
| 运行软件 | v0.1.0；`r-20261023T022529-1079900`，源码 `10799003cd38a28629dc5ea39835c8313b1e9d79` |
| 本机访问与安装包 | 见[当前实例接手卡](operations/current-instance.md)，使用方法见[用户手册](user-guide/README.md) |
| GitLab Release | [v0.1.0](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)，feature/main/tag完整五门禁通过 |
| 发布后文档基线 | `d397d05124ae490d6ada762296909b910e82a7e0`，feature684125与main684208完整通过，运行镜像仍绑定上面的发布源码 |
| 本轮文档重构 | 五角色建议已落实为用户/架构/前端/后端/运维路线；本地完整门禁通过，静态复评与提交后CI核对入口见[整改记录](reviews/documentation-2026-10-23.md) |

纯文档提交不重建当前运行镜像，不把文档HEAD当作部署版本。每项运行结果只覆盖原报告列明的版本与条件。

## 里程碑

| 阶段 | 状态 | 实际证据与交付边界 |
| --- | --- | --- |
| MS0 工程底座 | DONE | 固定Java21/Node24/PG18；独立test首次安装、默认完整check通过；feature/main/tag三条完整五job均通过，正式Release已发布 |
| MS1 公共层 | DONE | 管理9项、共享7项真实PG，账号/范围/会话HTTP、SMTP失败恢复、三类实际邮件通过；历史R3/R4/V5审计保留，提交前限定审计见本轮报告 |
| MS2 M1 预约 | DONE | 20用户争抢、同用户冲突、签到时间/码/限流、取消/统计已验；V6手机历史再约/真实409通过；跨升级实际午夜签到后被维修中断；R4 core真实10任务补偿及65秒去重通过 |
| MS3 M2 审批 | DONE | 自批/同人两级、并发同一步、审批/到期竞争及事务回滚已验；V6两级批准/撤销/理由必填拒绝页面通过；R4真实进程重启PENDING→EXPIRED通过 |
| MS4 M3 维修 | DONE | 真实HTTP、三期限×9收件人、双来源、响应丢失和网络恢复已验；纳秒往返缺陷修复与R3远端集成通过；V6手机报修→维修→独立验收及加载门禁通过；R4真实repair恢复证据保留 |
| MS5 验收恢复 | DONE | A/B回退、保留卷重建、隔离备份恢复、启动失败自动恢复及每日备份已验；R4完整62892请求，读p95 231.914ms/写390.488ms/5xx0，repair20任务20.393秒，core首12.860秒/全10任务14.057秒及65秒去重通过；本机验收及feature/main/tag完整CI通过，正式Release已发布 |
| MS6 交接发布 | DONE | V6浏览器6/6、privacy1/1、450源码/五镜像导入与原秘密/业务保持、两原会话/三业务正式回退通过；A/B/R3/R4/V5/V6组合及最终ARM64运输包保留；feature/main/tag完整五job PASS、MR已FF合入、tag及正式Release已发布；提交前限定审计见本轮报告 |
| MS7 P1 Agent | NOT_STARTED | 可选附加项，不替代P0验收 |

## 怎样核对结果

- 按需求验收：读[需求追踪表](requirements-traceability.md)，保留全部39项ID及具体证据。
- 按运行版本验收：从[证据索引](evidence/README.md)选择V6浏览器、导入、回退或GitLab记录；性能与故障恢复保留R4的原运行身份。
- 了解曾经失败和如何修复：读[交付过程归档](evidence/delivery-ledger-2026-10-23.md)与对应原始证据，不在日常操作指南中复述整段流水线历史。
- 维护或迁移：读[运维入口](operations/README.md)；安装包不包含业务数据库和秘密，完整数据迁移/在线切换需独立方案。

当前仍需后续条件才能开展的项目是正式主机/TLS/外部邮箱、不同CPU架构安装和可选P1；这些不冒称已通过。


## 2026-10-23 — GitHub / codex-lb 生产交付进行中（REQ-ENG-05/07）

用户已指定后续开发、CI 与生产运行均在 codex-lb，使用 GitHub PR rebase merge，默认不 squash。实现位于 `ycy/github-operations`，历史 GitLab 记录保留，现行边界见 ADR 0004。

- codex-lb 专用 GitHub Actions runner 2.338.1 已注册 online，由 systemd 自动启动；Java 21.0.12.1、Node 24.22.1、Docker 29.1.3、Compose 2.40.3 已核实。
- 私有仓库仅允许 rebase merge；API 与网页都明确当前套餐无法强制私有仓库 branch protection，不能报告已强制。production environment 已建立，无伪造人工审批。
- 公网 IP `49.51.193.169` 已取得受信任的短期 HTTPS 证书，80/443 外部可达；nginx 与六小时续期检查 timer 已安装。应用尚未部署，当前 502 不算上线成功。
- 新增 prod 随机管理员 bootstrap、无 demo 门禁、同机内存限制、完整 GitHub CI、GHCR digest/Release 发布和不可变源码部署；针对性单测与 actionlint 已通过。完整 `./scripts/check` 已在 codex-lb 通过：7 组 HTTP / 18 测试、9 组 PostgreSQL / 66 测试、27 项维修通知断言与响应丢失重放；新增及既有运维单测 144 项通过。真实 Actions/镜像/E2E/生产/备份恢复尚待验证。

这是一条实施进度，后续完成证据另附；没有把计划记成生产验收结果。
