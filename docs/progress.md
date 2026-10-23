# 开发进度与验收索引

更新时间：2026-10-23。当前V6为 `r-20261023T022529-1079900`，运行源码S为 `10799003cd38a28629dc5ea39835c8313b1e9d79`。浏览器6/6、privacy1/1、450源码/五镜像导入及V6→V5→V6正式回退均通过；feature683506完整五job PASS，MR !1已Fast-forward合入同一main提交，67提交/0 merge commit、未squash。`v0.1.0` annotated tag已推送并指向S，main683689与tag683743均完整五job PASS，[v0.1.0正式Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已发布；在用户确定的本机与可迁移交付范围内，39项P0均为VERIFIED、MS0～MS6为DONE。提交前限定审计见[报告](evidence/runtime-audit-final-v6.md)，GitLab发布事实见[发布记录](evidence/gitlab-release-v0.1.0.md)。运行镜像、manifest与包内源码绑定S；后续纯文档/证据HEAD单独记录，不因归档再次构建运行镜像。

用户确定本机与可迁移安装、GitLab、自主提交发版及本地Mailpit，见[ADR 0002](adr/0002-delivery-and-gitlab.md)。正式服务器、域名/TLS与外部SMTP收信未执行；P1 Agent未启动。原三份规格保留，业务与工程要求不缩减。

## 实际结果

[追踪表](requirements-traceability.md)保留39项：31条业务和8条工程需求。已有行为证据覆盖账号/范围、配置、通知、M1/M2/M3状态机、跨模块并发、来源恢复及三项破坏反证；运行身份分别记录，不将旧版通过自动移植到新镜像。

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

## 已关闭的真实缺陷

- [默认完整check](evidence/core/default-check.md)实际exit0：18项HTTP、65项真实PG、27个期限收件人断言和已提交响应丢失重放。保留本轮卷/凭据，只停止自有进程；原native PID不变。
- `8b8a3d3`统一12个旧runner的环境→workspace锁；`247b02d`覆盖原生build/migrate/copy/readiness全生命周期，精确校验PID归属，拒绝向无关进程发信号。相关锁与PID回归通过；该轮支持工具修正后完整Python运维回归112项通过，后续CI修正另有对应记录。
- R1浏览器5/6暴露隐藏loading图标污染确认按钮可访问名称；`3350d5b`提供稳定aria-label和aria-busy，严格按钮定位未弱化。R2主维修链通过，但第二次报修打开时房间仍加载，弹窗永久捕获空选项。真实1800ms延迟复现后，`a5695c3`使入口等到数据就绪，并给出失败/空结果反馈；R3同条件及完整6流程均通过。见[最终前端证据](evidence/frontend-release.md)。
- GitLab682941及682949真实失败于改派前后firstAssignedAt严格一致性，并非SMTP。固定纳秒Clock+真实PostgreSQL/HTTP复现第一次响应纳秒与回读微秒不同；`3c7a93c`在写入前统一至数据库微秒精度，原严格Python断言不变。两个期限HTTP及四个PG回归全部通过，见[时间精度反证与修复](evidence/repair-timestamp-precision.md)。

## 发布证据与边界

当前本机入口 `http://localhost:19282`，Mailpit `http://localhost:19227`，状态目录 `.local/portable-install/`，随机密码仅读其 `secrets/demo_password`。V6三服务身份一致；[六流程](evidence/frontend-release-final-v6.md)使用Chromium、1440×1000及375×812，6/6通过、113.061秒，无mock/跳过/重试，真实失败报告隐私反证1/1通过。代理目视复核桌面预约、手机拒绝与工单终态，没有新增使用阻塞。[V5](evidence/frontend-release-final-v5.md)、[R4](evidence/frontend-release-v4.md)和R3报告保留原身份。

R1预约保留原Cookie跨升级到R2，于真实午夜后通过375px页面签到；后续同室维修使其INTERRUPTED，违约为0。此项不写成自然COMPLETED，也不冒称在R3重复签到。原始分版本证据位于`output/browser-release-final/checkin/`。

[R3→B兼容证明](evidence/release/final-compatibility.json)和[实际rollback](evidence/release/final-actual-rollback.json)均通过：三镜像、两原会话、三类业务、新增assigned_to_name列与实际Flyway历史保持。回退不降级数据库。已有[A/B、备份恢复、保留卷重建和启动故障恢复](evidence/release/README.md)仍保留各自版本和条件。

[R4正式导入](evidence/release/v4-portable-import.json)核对414份源码/完整权限、五实际镜像和三版本一致，目标无Git，7原秘密及三类业务完整行摘要保持；[R4→B兼容证明](evidence/release/v4-compatibility.json)和[正式回退](evidence/release/v4-actual-rollback.json)核对两原会话、三业务、operator_note扩展列及数据库历史保留。A/B/R3/R4清单、R3和R4五镜像包分别保留，平台为linux/arm64，不冒称异机/AMD64或隔绝外网验收。

[V5正式导入](evidence/release/final-v5-portable-import.json)将准确提交解包到无Git的 `.local/portable-source-final-v5/`，442份源码内容及完整权限、五实际镜像与三版本一致，原源码目录保留。复用既有状态时，原7份秘密及导入前19预约/16申请/11报修的完整行摘要均保持；这些数量是导入快照，不是后续浏览器创建记录后的总数。[V5→R4→V5正式回退](evidence/release/final-v5-r4-rollback.json)在独立19182环境保留两原会话、三业务完整行与数据库结构/迁移历史，无pending/维护遗留；本轮没有启动B或降级数据库，不能替代旧版扩展schema兼容证明。

[V6正式导入](evidence/release/final-v6-portable-import.json)确认450份源码内容/权限和Git路径集合/可执行模式、五镜像/三版本一致，原7秘密及导入前21预约/18申请/13维修完整行保持。随后[V6→V5→V6回退](evidence/release/final-v6-v5-rollback.json)保留两原会话、三业务完整行及结构/迁移历史；首次夹具重叠409如实保留，改用实际空闲输入后正式流程通过，没有删除旧业务。两个环节与后续浏览器新增数据分别记录。

A/B/R3/R4/V5/V6六份发布清单保留；当前包位于 `.local/releases/r-20261023T022529-1079900/portable/`，运输文件 `.local/releases/v0.1.0/campus-study-room-v0.1.0-linux-arm64.tar.gz` 及相邻`.sha256`：414289744字节，SHA256 `7b143161a31778be71ebabc20a1dffbc8d744cda454335df1fa82b3f571be7ee`，已[逐文件核验](evidence/release/final-v6-archive-verification.json)。旧V5根目录运输包不覆盖；包仅支持linux/arm64，AMD64须另构建。

[R4完整性能及SQL](evidence/performance.md)通过：62892统计请求，读p95 231.914ms、写390.488ms，5xx/传输/意外4xx均0；20项repair积压20.393秒处理。[R4 core真实恢复](evidence/core/restart-recovery-v4.md)首12.860秒、全部10任务14.057秒、65秒无重复，原业务行摘要不变。负载保留100002条历史，之后合成恢复夹具增至100003，没有删数据或回填旧结果；三服务运行标签与镜像ID均实际绑定R4。

GitLab [feature pipeline683506](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683506)在S上完整五job PASS；[MR !1](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/1)已Fast-forward合入同一main提交，保留67提交、0 merge commit、未squash。成功门禁及skipped不算成功保持；[负面MR !2](evidence/gitlab-negative-gate.md)原实际阻断证据保留，测试MR已关闭，破坏提交不在交付历史。main自动[pipeline683689](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683689)五job全部PASS，35分42秒：integration4482291用时15分43秒，完成66项数据库测试与124项ops；container4482292用时16分07秒，真实浏览器6/6、约1.8分钟。[tag pipeline683743](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683743)五job全部PASS，34分05秒：integration4482637用时16分50秒，同样66项数据库测试/124项ops；container4482638用时13分00秒，浏览器6/6、约1.5分钟及隐私反证957.42035ms；package4482639通过、56秒。这些CI运行与本机113.061秒的V6浏览器分别记录。

[源码审计](evidence/final-source-audit.md)快照d5b4f84：387跟踪文件、近8提交404唯一blob，对140个实际secret/cookie及563变体命中0。可读项目名已统一，锁文件随机摘要保留；临时下载代理没有固化。[R3运行隐私审计](evidence/final-runtime-audit.md)随后归档：116份运行产物（含15份限定日志）及HTML内嵌ZIP，对181个实际已知值/727种形式为0命中；收尾412份源码/新增文档以174个当时已知值/701种形式补扫，同样0命中。扫描截止时间、源码HEAD与镜像身份独立记录；远端GitLab日志、截止后的活动及R4运行不在该轮范围，旧源码快照未改写。

[R4统一运行审计](evidence/runtime-audit-v4.md)覆盖97份冻结运行输入（含13份应用及过程日志）与30份源码增量，对184个已知私有值、741种形式匹配均为0；HTML内嵌ZIP已解码检查。应用日志快照截至2026-10-22 17:24:41 UTC，源码收尾范围与精确时刻见[机器结果](evidence/runtime-audit-v4.json)。远端GitLab日志、日志截止后的活动和后续文件改动均不在本次快照范围。

R4纳入支持工具`4a83b6e`并独立完成性能/恢复，原证据仍绑定3012051。V6的[242份生产及构建输入](evidence/release/final-v6-production-equivalence.json)与R4/V5路径、字节和Git模式一致；CI、测试及文档变更在该集合之外。这支持保留原性能/恢复结果，不代表V6重跑或OCI镜像ID相同。

[V5限定隐私审计](evidence/runtime-audit-final-v5.md)截止 `2026-10-23T00:26:37.902392Z`，128份唯一输入对129个已知值/513种形式0命中，2个HTML内嵌ZIP的4个成员已解码。Runner凭据另由根代理在较早时刻作独立扫描，不在这129值内；本索引新增字节、后续CI日志和截止后的活动不属于已冻结审计范围。

## 最终交付记录

1. feature683506、main683689及tag683743均已实际完整五job通过；MR已Fast-forward合入同一S，三轮结果各自保留。
2. `v0.1.0` annotated tag已推送，tag对象 `257c481e0432183ec059581179b95f0e93127f36`，peeled commit为S；main/tag门禁均已通过，[正式Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)精确关联S、manifest及本次运输包，旧包保留。tag保留的两项制品链接及实际UI核验见[发布记录](evidence/gitlab-release-v0.1.0.md)。
3. 提交前限定审计见[V6报告](evidence/runtime-audit-final-v6.md)，范围和截止时刻以报告为准。本机浏览器原始报告、GitLab记录、当前状态文档及历史审计分别留存，不将任一旧快照扩展为对以后活动的保证。运行镜像仍绑定S，纯文档HEAD另行记录。

## GitLab失败历史保留

683030的backend/frontend/integration均PASS，container-release4478863由根代理取消，完整pipeline为Canceled。实际挂载权限失败来自诊断683047/4478933：共享Runner5186的官方DinD entrypoint不能挂载`/sys/kernel/security`，daemon未启动，不能把被取消任务写成业务测试失败。

[专用Runner5666](evidence/gitlab-runner-registration.md)经用户授权注册启用，限定项目/受保护引用/唯一标签；683461诊断实际通过TLS、checkout与嵌套目录验证。V5源码e95b025的683462三个先行job通过，container-release4481130在官方Alpine HTTPS CDN安装Python/Git时TLS EOF/超时失败，业务构建未执行、package跳过。[CI-only可信HTTPS修正](evidence/gitlab-alpine-mirror.md)保留原签名/TLS/五job门禁，后续S的683506完整通过，不回填旧失败。

既有18082/18027及原生8000是分离旧环境，不代表当前V6。正式服务器、外网TLS/SMTP及AMD64未实测，按ADR保留边界。
