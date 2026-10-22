# 开发进度与验收索引

更新时间：2026-10-23。当前运行候选为 R3 `r-20261022T161900-3c7a93c`，完整源码 `3c7a93cae56e68d2a96adb1e022f4fffc8112c5e`。已在独立便携安装入口通过浏览器6/6、真实慢网络回归及R3→B兼容回退。**P0仍为IN_PROGRESS**：R3性能、core真实重启及限定范围运行隐私审计已通过；正在准备包含后续CI和验收工具修正的R4完整源码包，尚未构建或验收。GitLab完整流水线/main合入及tag仍待闭环。

用户确定本机与可迁移安装、GitLab、自主提交发版及本地Mailpit，见[ADR 0002](adr/0002-delivery-and-gitlab.md)。正式服务器、域名/TLS与外部SMTP收信未执行；P1 Agent未启动。原三份规格保留，业务与工程要求不缩减。

## 实际结果与待项

[追踪表](requirements-traceability.md)保留39项：31条业务和8条工程需求。已有行为证据覆盖账号/范围、配置、通知、M1/M2/M3状态机、跨模块并发、来源恢复及三项破坏反证；运行身份分别记录，不将旧版通过自动移植到新镜像。

| 阶段 | 状态 | 实际证据 / 尚待完成 |
| --- | --- | --- |
| MS0 工程底座 | IN_PROGRESS | 固定Java21/Node24/PG18；独立test首次安装、默认完整check通过；最终GitLab及main自动整套部署待验 |
| MS1 公共层 | IN_PROGRESS | 管理9项、共享7项真实PG，账号/范围/会话HTTP、SMTP失败恢复、三类实际邮件通过；R3限定范围运行日志隐私审计已归档 |
| MS2 M1 预约 | IN_PROGRESS | 20用户争抢、同用户冲突、签到时间/码/限流、取消/统计已验；R3手机历史再约/409通过；跨升级实际午夜签到后被维修中断，core真实10任务补偿及65秒去重通过 |
| MS3 M2 审批 | IN_PROGRESS | 自批/同人两级、并发同一步、审批/到期竞争及事务回滚已验；R3两级批准/撤销/拒绝页面通过；真实进程重启PENDING→EXPIRED通过 |
| MS4 M3 维修 | IN_PROGRESS | 真实HTTP、三期限×9收件人、双来源、响应丢失和网络恢复已验；纳秒与PG微秒往返缺陷已严格复现并修复；R3手机报修→维修→独立验收及1800ms延迟回归通过，R3完整远端集成通过 |
| MS5 验收恢复 | IN_PROGRESS | 旧诊断镜像完整性能复测通过；A/B回退、保留卷重建、隔离备份恢复、启动失败自动恢复及每日备份已验；R3读p95 154.36ms/写231.88ms/5xx0，repair20任务7.926秒，core10任务11.183秒及65秒去重通过 |
| MS6 交接发布 | IN_PROGRESS | 独立agent实际试用；R3浏览器6/6、报告隐私及R3→B回退通过；A/B/R3清单和R3五镜像包已留存；最终离线导入核对通过；R3运行产物扫描已归档；R4打包验收及GitLab/main/tag待闭环 |
| MS7 P1 Agent | NOT_STARTED | 可选附加项，不替代P0验收 |

## 已关闭的真实缺陷

- [默认完整check](evidence/core/default-check.md)实际exit0：18项HTTP、65项真实PG、27个期限收件人断言和已提交响应丢失重放。保留本轮卷/凭据，只停止自有进程；原native PID不变。
- `8b8a3d3`统一12个旧runner的环境→workspace锁；`247b02d`覆盖原生build/migrate/copy/readiness全生命周期，精确校验PID归属，拒绝向无关进程发信号。相关锁与PID回归通过；当前支持工具修正后完整Python运维回归112项通过。
- R1浏览器5/6暴露隐藏loading图标污染确认按钮可访问名称；`3350d5b`提供稳定aria-label和aria-busy，严格按钮定位未弱化。R2主维修链通过，但第二次报修打开时房间仍加载，弹窗永久捕获空选项。真实1800ms延迟复现后，`a5695c3`使入口等到数据就绪，并给出失败/空结果反馈；R3同条件及完整6流程均通过。见[最终前端证据](evidence/frontend-release.md)。
- GitLab682941及682949真实失败于改派前后firstAssignedAt严格一致性，并非SMTP。固定纳秒Clock+真实PostgreSQL/HTTP复现第一次响应纳秒与回读微秒不同；`3c7a93c`在写入前统一至数据库微秒精度，原严格Python断言不变。两个期限HTTP及四个PG回归全部通过，见[时间精度反证与修复](evidence/repair-timestamp-precision.md)。

## 发布证据与边界

最终本机入口 `http://localhost:19282`，本地Mailpit `http://localhost:19227`。R3三服务身份一致；[浏览器6/6](evidence/frontend-release.md)使用Chromium、1440×1000及375×812，无mock、无跳过/隐藏重试。另有真实失败报告隐私反证1/1通过。

R1预约保留原Cookie跨升级到R2，于真实午夜后通过375px页面签到；后续同室维修使其INTERRUPTED，违约为0。此项不写成自然COMPLETED，也不冒称在R3重复签到。原始分版本证据位于`output/browser-release-final/checkin/`。

[R3→B兼容证明](evidence/release/final-compatibility.json)和[实际rollback](evidence/release/final-actual-rollback.json)均通过：三镜像、两原会话、三类业务、新增assigned_to_name列与实际Flyway历史保持。回退不降级数据库。已有[A/B、备份恢复、保留卷重建和启动故障恢复](evidence/release/README.md)仍保留各自版本和条件。

GitLab [MR !1](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/1)保持Draft等待完整验收。用户要求的Fast-forward、默认不squash、pipeline必须成功且skipped不算成功已实际配置。隔离故意失败的[MR !2 / pipeline682968](evidence/gitlab-negative-gate.md)已验证非Draft状态也阻止合并，后续integration/container-release/package跳过；测试MR已关闭，破坏提交不在交付分支。

[源码审计](evidence/final-source-audit.md)快照d5b4f84：387跟踪文件、近8提交404唯一blob，对140个实际secret/cookie及563变体命中0。可读项目名已统一，锁文件随机摘要保留；临时下载代理没有固化。[R3运行隐私审计](evidence/final-runtime-audit.md)随后归档：116份运行产物（含15份限定日志）及HTML内嵌ZIP，对181个实际已知值/727种形式为0命中；收尾412份源码/新增文档以174个当时已知值/701种形式补扫，同样0命中。扫描截止时间、源码HEAD与镜像身份独立记录；远端GitLab日志、截止后的活动及未来R4不在范围，旧源码快照未改写。

R4用于把后续修正纳入同一可迁移源码包与版本标签：CI环境名称、旧Runner可选探针兼容、性能验收工具版本标签/身份门禁、恢复采集器完整JSON解析及真实数据规模报告已修正，工具提交为`4a83b6e`。主业务代码与R3一致，既有R3通过事实保留；R4尚未构建，不能继承为已通过的R4运行证据。

## 剩余闭环顺序

1. R3完整性能、SQL计划与真实repair/core恢复已通过。core首轮采集器JSON解析错误保留；修复后完整第二轮正常停启、10任务/3终态/零过期提醒、唯一通知/违约及65秒稳定性均通过，原数据不变。
2. R3五镜像包实际导入核对及限定范围隐私扫描已通过。构建包含上述修正的R4完整源码/五镜像组合，再按其实际清单执行既定浏览器、性能/恢复、兼容回退与离线导入核对，保留原数据；R4结果尚待执行。
3. R3 backend/frontend/integration已通过，container-release因Runner17.2可选探针在PodPending时提前失败；两次18秒失败证据保留，已按官方源码修正，完整流水线仍须复验。通过后按Fast-forward、不squash合入main，再验main自动整套部署/E2E；发版tag与清单精确绑定。
4. R4运行完成后另行扫描对应源码、报告与限定日志，将机器结果、MR/pipeline/tag/manifest关联到原39条追踪，按实际证据关闭门禁。R3审计不覆盖未来运行。

既有Docker test 18082/邮件18027和原生开发8000为分离的旧运行环境，不标记为R3。另一服务器、正式TLS/外部邮箱未实测，按ADR保留限制。
