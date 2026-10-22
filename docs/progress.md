# 开发进度与验收索引

更新时间：2026-10-23。当前R2为`3350d5b`，已部署到独立portable入口；R2复验发现的实际问题正在修复，下一候选R3尚未构建。**P0仍为IN_PROGRESS**，不将旧镜像、工作区检查或安全单测冒充最终版本全量通过。

用户确定的交付边界是本机与可迁移安装、GitLab、自主提交发版及本地Mailpit，见[ADR 0002](adr/0002-delivery-and-gitlab.md)。正式服务器、域名/TLS与外部SMTP收信未执行；P1 Agent未启动。原三份规格保留，业务与工程要求不因本次盘点缩减。

## 已有行为证据与工程待项

[追踪表](requirements-traceability.md)逐条保留39项：31条业务需求和8条工程需求。每项均已关联实现和实际行为测试；已有证据覆盖账号/范围、配置、通知、M1/M2/M3状态机、跨模块并发、来源恢复与三项破坏反证。具体证据按运行版本记录，最终候选的验收尚未全部完成。

| 阶段 | 状态 | 实际证据 / 尚待完成 |
| --- | --- | --- |
| MS0 工程底座 | IN_PROGRESS | 固定Java21/Node24/PG18；本机独立test首次Docker安装通过；默认完整check已真实通过。GitLab原四阶段682662成功，但新完整集成/发布门禁仍有真实失败，main自动整套部署待验 |
| MS1 公共层 | IN_PROGRESS | 管理9项、共享7项真实PG；账号/范围/会话HTTP、真实SMTP失败/恢复、三类提醒实际收信均通过。最终镜像恢复、最终报告隐私和版本追踪随MS5/MS6闭环 |
| MS2 M1 预约 | IN_PROGRESS | 同座20用户争抢、同用户跨室冲突、签到时间/每日码/第6次限流、取消边界、统计手算均通过；日期筛选与375px真实历史再约通过。R2原会话实际午夜签到成功，后被同室维修中断；真实进程停机补偿仍待最终候选运行 |
| MS3 M2 审批 | IN_PROGRESS | 三账号两级批准/手机撤销、必填拒绝理由及申请人历史实际页面通过；自批/同人两级、并发同一步、审批/到期竞争、claim写后整笔回滚均通过。最终候选沿用既定浏览器链复验，PENDING过期重启由MS5验证 |
| MS4 M3 维修 | IN_PROGRESS | 真实负面HTTP、三期限×9收件人、双来源、已提交响应丢失重放、BLOCK八次失败/RELEASE恢复均通过。R2开始→完工→验收链通过；重复报修加载竞态已修复并通过typecheck/unit/build，新镜像延迟回归待验。GitLab改派firstAssignedAt一致性失败仍在修复，R3整套未验 |
| MS5 验收恢复 | IN_PROGRESS | 两轮完整性能含失败定位：修复后读p95 153.50ms/写252.93ms/5xx=0；repair20积压重启12.09秒。A/B兼容回退、保留卷重建、隔离恢复逐行摘要、启动故障自动恢复、每日备份触发与损坏负例已验。最终候选完整性能及core真实重启未执行 |
| MS6 交接发布 | IN_PROGRESS | 多次逻辑commit、独立agent桌面/375px试用；B五镜像独立安装+浏览器6/6；R1和R2实际回退通过。R3最终完整浏览器、GitLab、精确回退目标、至少3版留存核对、最终离线包/tag/manifest仍待闭环 |
| MS7 P1 Agent | NOT_STARTED | 可选附加项，P0优先，不用于替代P0验收 |

## 最近已关闭的具体缺口

- `8bc7558`的[默认完整check](evidence/core/default-check.md)实际exit0：18项HTTP、65项真实PG、27个期限收件人断言和提交响应丢失重放。GitLab使用同一函数与套件清单；`--unit-only`明确不是完整验收。检查保留本轮数据卷/0600凭据，只停止自有进程与容器，已有native PID不变。
- `8b8a3d3`为12个旧runner入口补环境锁；使用构建产物时继续取得workspace锁，fixture函数不嵌套加锁。`247b02d`补原生build/migrate/copy/readiness全生命周期锁及精确PID归属校验。真实无关子进程未收到信号，迁移/复制异常后锁释放；该批全Python运维105/105通过（含当时新增8项恢复脚本安全测试），没有借此声称执行了真正重启。既有8081/8082只读身份及健康检查均通过。
- `ee51e6e`补[真实历史再约和拒绝页面](../tests/browser/REVIEW.md#同一旧-b-版补充历史复用与拒绝页面)：375px新时段、新编号、原订单逐字段不变；拒绝理由必填、仅一次请求、申请人可见理由和终态操作消失。这些已经实测，不再列为未操作缺口。
- R1完整浏览器首轮5/6失败保留。实际DOM确认残留loading图标污染确认按钮可访问名称；`3350d5b`提供稳定aria-label与真实aria-busy，测试恢复精确角色定位并保持409禁用/响应/表单约束。独立只读review通过，未以CSS定位绕过问题。
- 审计快照`8b8a3d3`：381个跟踪文件和近8提交的411个唯一blob，对46个私有来源的89个实际secret/cookie及484种明文/常见编码变体检查，命中0。旧项目名路径/内容命中0（仅排除pnpm integrity中3个随机子串），代理端口写入命中0。该有界扫描不代替最终候选新提交和运行报告扫描。

## R2实际结果与当前未闭环问题

独立portable入口为`http://localhost:19282`，Mailpit为`http://localhost:19227`。R2运行提交`3350d5b`；原B=`66f2b92`的[独立安装](evidence/release/portable-install.json)和[浏览器6/6](../tests/browser/REVIEW.md)仍作为旧版本证据保留，不转记R2/R3成功。

R1创建的预约保留原Cookie跨升级到R2，在真实午夜后通过375px页面完成签到。证据保留在`output/browser-release-final/checkin/`；后续同室维修使其INTERRUPTED，不能标记为自然完成。R2完整浏览器为5/6：维修开始、完工、验收已实际通过，但同轮第二次报修时房间列表尚未加载，按钮可点而弹窗捕获空options。旧R2在真实网络延迟1800ms下复现该竞态。`a5695c3`增加roomsPending/roomsReady准入、错误与空结果反馈，typecheck/unit/build通过；新增延迟回归尚未在修复后的运行镜像执行，与R3完整六流程一并待验。

GitLab682703/682861的早期失败未曾定位为SMTP；之后682941已明确失败于`repair_acceptance_http.py`的`test_03`第189行：改派前后`firstAssignedAt`不相等。Linux纳秒与PostgreSQL微秒是待验证解释，维修代理正在复现和修复。失败必须继续阻断后续发布/合并，不能降低时间断言或把旧四阶段成功当作当前门禁通过。

隔离负面门禁分支`ycy/ci-negative-gate`以`d6a4d29`故意触发JUnit失败，非draft [MR !2](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/2)已显示“Pipeline must succeed”限制。Pipeline 682968已因该测试实际失败，页面显示唯一合并阻断为流水线成功要求；integration、container-release与package均跳过。测试MR已关闭，破坏提交不在交付分支中。[负向门禁证据](evidence/gitlab-negative-gate.md)已归档。

R1→B正式rollback已通过，保留`repair.repair_order.assigned_to_name`、两名用户原会话、三类业务与新schema，见本机`output/release-candidate-actual-rollback.json`。R2→B兼容探针`output/release-final-v2-compatibility.json`与实际rollback `output/release-final-v2-actual-rollback.json`均已通过，核对三镜像、两原Cookie、三类业务和扩展schema。最终R3仍需绑定自身清单与已验证回退目标。早期[A/B、备份恢复、保留卷重建及启动故障恢复](evidence/release/README.md)不再列为未执行；无相关机制变更时无需全部重复。

新增[core重启验收脚本](../tests/integration/core_restart_recovery.py)已提交`e70b917`，安全/负面断言8/8通过；真实Docker执行尚未发生。计划在最终候选完整性能之后，对独立性能环境正常stop/start同一core容器，核对10项积压任务、NO_SHOW/COMPLETED/EXPIRED、过期提醒不补发、唯一通知/违约及65秒后无重复，不改现有业务记录或删除卷。

## 下一步闭环顺序

1. 严格复验改派时间一致性修复，与已提交的UI加载修复一起冻结R3完整提交和三个镜像；在新镜像运行房间加载延迟回归。
2. 在最终组合执行既定浏览器6流程和隐私门禁；执行原规格6分钟性能、同座争抢、repair积压与core跨提醒真实重启，保留全部原始结果。
3. 让同提交GitLab完整流水线真实通过，保留失败阻断与合并门禁证据；Fast-forward合入main后验证整套test自动更新。
4. 核对最终兼容回退目标、至少三套成功版本留存、最终五镜像离线包及tag/manifest；归档最终代码和运行报告秘密扫描、MR/pipeline/版本链接，再逐项关闭39条追踪。

本机既有入口还包括Docker test `http://localhost:18082`、邮件`http://localhost:18027`及原生开发前端`http://localhost:8000`，与portable环境的数据库、会话、秘密分离。另一台服务器/AMD64、正式TLS/外部邮箱未实测；按既定交付边界保留限制，不扩充本轮范围，也不冒充已验。
