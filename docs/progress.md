# 开发进度与验收索引

更新时间：2026-10-23。当前最终候选V5为 `r-20261023T001633-e95b025`，完整源码 `e95b025de6f03c393d8ad32063ed51b2cbf7babe`。**V5本机验收已通过，P0整体仍为IN_PROGRESS**：本轮浏览器6/6、隐私反证1/1、442份源码与五镜像正式导入、V5→R4→V5正式回退和恢复均通过。[V5限定隐私审计](evidence/runtime-audit-final-v5.md)已完成，128份输入、129个已知值/513种编码形式为0命中。完整性能/SQL及repair/core恢复继续引用R4原版证据，依据是[242份生产及构建输入与R4一致](evidence/release/final-v5-production-equivalence.json)，不改标为V5重跑。GitLab完整流水线、main合入、tag及正式release仍待闭环；后续文档/证据HEAD不等于运行版本，不倒改既有证据。

用户确定本机与可迁移安装、GitLab、自主提交发版及本地Mailpit，见[ADR 0002](adr/0002-delivery-and-gitlab.md)。正式服务器、域名/TLS与外部SMTP收信未执行；P1 Agent未启动。原三份规格保留，业务与工程要求不缩减。

## 实际结果与待项

[追踪表](requirements-traceability.md)保留39项：31条业务和8条工程需求。已有行为证据覆盖账号/范围、配置、通知、M1/M2/M3状态机、跨模块并发、来源恢复及三项破坏反证；运行身份分别记录，不将旧版通过自动移植到新镜像。

| 阶段 | 状态 | 实际证据 / 尚待完成 |
| --- | --- | --- |
| MS0 工程底座 | IN_PROGRESS | 固定Java21/Node24/PG18；独立test首次安装、默认完整check通过；最终GitLab及main自动整套部署待验 |
| MS1 公共层 | IN_PROGRESS | 管理9项、共享7项真实PG，账号/范围/会话HTTP、SMTP失败恢复、三类实际邮件通过；R3/R4审计保留，V5限定128份输入审计0命中 |
| MS2 M1 预约 | IN_PROGRESS | 20用户争抢、同用户冲突、签到时间/码/限流、取消/统计已验；V5手机历史再约/真实409通过；跨升级实际午夜签到后被维修中断；R4 core真实10任务补偿及65秒去重通过 |
| MS3 M2 审批 | IN_PROGRESS | 自批/同人两级、并发同一步、审批/到期竞争及事务回滚已验；V5两级批准/撤销/理由必填拒绝页面通过；R4真实进程重启PENDING→EXPIRED通过 |
| MS4 M3 维修 | IN_PROGRESS | 真实HTTP、三期限×9收件人、双来源、响应丢失和网络恢复已验；纳秒往返缺陷修复与R3远端集成通过；V5手机报修→维修→独立验收及加载门禁通过；R4真实repair恢复证据保留 |
| MS5 验收恢复 | IN_PROGRESS | A/B回退、保留卷重建、隔离备份恢复、启动失败自动恢复及每日备份已验；R4完整62892请求，读p95 231.914ms/写390.488ms/5xx0，repair20任务20.393秒，core首12.860秒/全10任务14.057秒及65秒去重通过；本机验收完成，远端完整CI仍待闭环 |
| MS6 交接发布 | IN_PROGRESS | 独立agent实际试用；V5浏览器6/6、privacy1/1、442源码/五镜像导入及原7秘密/三类业务保持；V5→R4→V5正式回退保留两原会话和三业务；A/B/R3/R4/V5清单、单一ARM64运输包及限定隐私审计已归档；GitLab/main/tag/release待闭环 |
| MS7 P1 Agent | NOT_STARTED | 可选附加项，不替代P0验收 |

## 已关闭的真实缺陷

- [默认完整check](evidence/core/default-check.md)实际exit0：18项HTTP、65项真实PG、27个期限收件人断言和已提交响应丢失重放。保留本轮卷/凭据，只停止自有进程；原native PID不变。
- `8b8a3d3`统一12个旧runner的环境→workspace锁；`247b02d`覆盖原生build/migrate/copy/readiness全生命周期，精确校验PID归属，拒绝向无关进程发信号。相关锁与PID回归通过；当前支持工具修正后完整Python运维回归112项通过。
- R1浏览器5/6暴露隐藏loading图标污染确认按钮可访问名称；`3350d5b`提供稳定aria-label和aria-busy，严格按钮定位未弱化。R2主维修链通过，但第二次报修打开时房间仍加载，弹窗永久捕获空选项。真实1800ms延迟复现后，`a5695c3`使入口等到数据就绪，并给出失败/空结果反馈；R3同条件及完整6流程均通过。见[最终前端证据](evidence/frontend-release.md)。
- GitLab682941及682949真实失败于改派前后firstAssignedAt严格一致性，并非SMTP。固定纳秒Clock+真实PostgreSQL/HTTP复现第一次响应纳秒与回读微秒不同；`3c7a93c`在写入前统一至数据库微秒精度，原严格Python断言不变。两个期限HTTP及四个PG回归全部通过，见[时间精度反证与修复](evidence/repair-timestamp-precision.md)。

## 发布证据与边界

当前本机入口 `http://localhost:19282`，本地Mailpit `http://localhost:19227`，状态目录 `.local/portable-install/`，随机密码只从其 `secrets/demo_password` 私密文件读取。V5三服务身份一致；[最终浏览器6/6](evidence/frontend-release-final-v5.md)使用Chromium、1440×1000及375×812，97.599秒，无mock、无跳过/隐藏重试，真实失败报告隐私反证1/1通过。独立代理目视复核桌面预约、手机拒绝原因及关闭工单，没有新增使用阻塞；[R4原浏览器报告](evidence/frontend-release-v4.md)100.806秒和R3报告均保留。

R1预约保留原Cookie跨升级到R2，于真实午夜后通过375px页面签到；后续同室维修使其INTERRUPTED，违约为0。此项不写成自然COMPLETED，也不冒称在R3重复签到。原始分版本证据位于`output/browser-release-final/checkin/`。

[R3→B兼容证明](evidence/release/final-compatibility.json)和[实际rollback](evidence/release/final-actual-rollback.json)均通过：三镜像、两原会话、三类业务、新增assigned_to_name列与实际Flyway历史保持。回退不降级数据库。已有[A/B、备份恢复、保留卷重建和启动故障恢复](evidence/release/README.md)仍保留各自版本和条件。

[R4正式导入](evidence/release/v4-portable-import.json)核对414份源码/完整权限、五实际镜像和三版本一致，目标无Git，7原秘密及三类业务完整行摘要保持；[R4→B兼容证明](evidence/release/v4-compatibility.json)和[正式回退](evidence/release/v4-actual-rollback.json)核对两原会话、三业务、operator_note扩展列及数据库历史保留。A/B/R3/R4清单、R3和R4五镜像包分别保留，平台为linux/arm64，不冒称异机/AMD64或隔绝外网验收。

[V5正式导入](evidence/release/final-v5-portable-import.json)将准确提交解包到无Git的 `.local/portable-source-final-v5/`，442份源码内容及完整权限、五实际镜像与三版本一致，原源码目录保留。复用既有状态时，原7份秘密及导入前19预约/16申请/11报修的完整行摘要均保持；这些数量是导入快照，不是后续浏览器创建记录后的总数。[V5→R4→V5正式回退](evidence/release/final-v5-r4-rollback.json)在独立19182环境保留两原会话、三业务完整行与数据库结构/迁移历史，无pending/维护遗留；本轮没有启动B或降级数据库，不能替代旧版扩展schema兼容证明。

主目录 `.local/releases/` 现保留A/B/R3/R4/V5五份清单。V5五镜像包在 `.local/releases/r-20261023T001633-e95b025/portable/`，单一运输文件为 `.local/releases/campus-study-room-v0.1.0-linux-arm64.tar.gz` 及同名 `.sha256`：414259208字节，SHA256 `cc13877ab94d3394640a82508c42fc924acaa95a9b5226ebad3f71f2f84584f0`。仅支持该linux/arm64构建，AMD64需要对应平台镜像；归档名称不表示GitLab tag或正式release已经创建。

[R4完整性能及SQL](evidence/performance.md)通过：62892统计请求，读p95 231.914ms、写390.488ms，5xx/传输/意外4xx均0；20项repair积压20.393秒处理。[R4 core真实恢复](evidence/core/restart-recovery-v4.md)首12.860秒、全部10任务14.057秒、65秒无重复，原业务行摘要不变。负载保留100002条历史，之后合成恢复夹具增至100003，没有删数据或回填旧结果；三服务运行标签与镜像ID均实际绑定R4。

GitLab [MR !1](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/1)保持Draft等待完整验收。用户要求的Fast-forward、默认不squash、pipeline必须成功且skipped不算成功已实际配置。隔离故意失败的[MR !2 / pipeline682968](evidence/gitlab-negative-gate.md)已验证非Draft状态也阻止合并，后续integration/container-release/package跳过；测试MR已关闭，破坏提交不在交付分支。

[源码审计](evidence/final-source-audit.md)快照d5b4f84：387跟踪文件、近8提交404唯一blob，对140个实际secret/cookie及563变体命中0。可读项目名已统一，锁文件随机摘要保留；临时下载代理没有固化。[R3运行隐私审计](evidence/final-runtime-audit.md)随后归档：116份运行产物（含15份限定日志）及HTML内嵌ZIP，对181个实际已知值/727种形式为0命中；收尾412份源码/新增文档以174个当时已知值/701种形式补扫，同样0命中。扫描截止时间、源码HEAD与镜像身份独立记录；远端GitLab日志、截止后的活动及R4运行不在该轮范围，旧源码快照未改写。

[R4统一运行审计](evidence/runtime-audit-v4.md)覆盖97份冻结运行输入（含13份应用及过程日志）与30份源码增量，对184个已知私有值、741种形式匹配均为0；HTML内嵌ZIP已解码检查。应用日志快照截至2026-10-22 17:24:41 UTC，源码收尾范围与精确时刻见[机器结果](evidence/runtime-audit-v4.json)。远端GitLab日志、日志截止后的活动和后续文件改动均不在本次快照范围。

R4已将CI环境名称、旧Runner可选探针兼容、性能验收工具版本标签/身份门禁、恢复采集器完整JSON解析及真实数据规模报告修正纳入同一完整源码包，支持工具提交为`4a83b6e`。主业务代码与R3一致，但R4上述结果来自独立实际复验，仍绑定当轮3012051运行身份。V5的242份生产及构建输入与R4路径/字节/Git权限模式一致，CI、测试与文档变更在该范围之外；这是保留R4性能/恢复证据的依据，不意味着两版OCI镜像ID相同。

[V5限定隐私审计](evidence/runtime-audit-final-v5.md)截止 `2026-10-23T00:26:37.902392Z`，128份唯一输入对129个已知值/513种形式0命中，2个HTML内嵌ZIP的4个成员已解码。Runner凭据另由根代理在较早时刻作独立扫描，不在这129值内；本索引新增字节、后续CI日志和截止后的活动不属于已冻结审计范围。

## 剩余闭环顺序

1. V5本机既定浏览器、正式导入、同schema回退/恢复及限定隐私审计已完成；R4性能/SQL和repair/core恢复保留原身份，并有生产输入等价证据。所有早期失败及R3/R4记录保留，不因远端CI未闭合抹掉本机事实，也不无依据扩展验收排列。
2. 当前最终源码e95b025的[pipeline **683462**](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683462)已结束且未通过：backend/frontend/integration均PASS；[integration4481129](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4481129)约15分10秒，122项ops、27个期限收件人断言及9份JUnit报告均已执行/产出。[container-release4481130](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4481130)在`apk add`安装Python/Git阶段访问官方Alpine HTTPS CDN发生TLS EOF/超时，任务FAILED，业务构建尚未执行；package为skipped。CI-only的可信HTTPS镜像修正正在准备，尚无修正后通过结果。历史[pipeline **683030**](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683030)的backend4478860（4分10秒）、frontend4478861（1分20秒）、integration4478862（15分56秒）均通过，执行112ops、18HTTP、9组PG、27期限收件人及已提交响应丢失重放；这些历史结果不代替当前提交完整CI。
3. 完整同提交pipeline真实成功后，按Fast-forward、不squash合入main，核对main自动整套部署/E2E，再将tag与已验清单精确绑定。当前main/tag门禁未关闭。
4. 最终MR/pipeline/tag/manifest须精确绑定已验源码；`v0.1.0`的保护规则已配置，实际tag及正式release尚未创建。V5限定审计已完成，但本次状态索引新增字节不属于该已冻结快照，最终CI产物仍须单独收尾。

根代理随后取消了683030的container-release job4478863，避免在同一已确认无DinD挂载权限的#5186上重复等待；三个先行PASS保持，完整pipeline记录为Canceled。挂载权限拒绝的实测来源是诊断pipeline683047/job4478933，其官方entrypoint报告`mount permission denied` / `Could not mount /sys/kernel/security`，daemon未启动；不能把被取消任务写为已实际失败，也不能归为应用测试失败。

后续`098d8a2`准备精确受保护分支工作流与专用ARM64 Runner标签，新增10项配置回归，当轮全Python运维回归122项通过。本地TLS正反探针及嵌套目录通过；用户明确授权后，2026-10-23[专用Runner #5666已注册并启用](evidence/gitlab-runner-registration.md)：17.8.5/linux-arm64，项目锁定、Protected开启、untagged关闭，来宾服务enabled/active/running。原main保护保持，仅为两条确切工作分支新增Maintainers/no-force保护；注册时受保护tag规则为0，后续已新增确切`v0.1.0`保护。诊断提交`2b3c32f`的pipeline683461/job4481126已在Runner5666通过（43秒、排队1秒），实际helper拉取、checkout、双端ARM64/TLS和只读嵌套marker均通过；可选service helper的No HOST or PORT警告保留，未执行业务build，完整五job CI仍待通过。[GitLab服务端默认分支模拟](evidence/gitlab-config-validation.md)解析全部五个阻断job，不替代真实发布或MR最新SHA门禁。

既有Docker test 18082/邮件18027和原生开发8000为分离的旧运行环境，不代表当前V5。另一服务器、正式TLS/外部邮箱未实测，按ADR保留限制。
