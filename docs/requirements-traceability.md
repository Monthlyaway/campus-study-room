# P0 需求、实现与验收追踪

[当前进度](progress.md) · [证据索引](evidence/README.md) · [规格导读](reference/README.md)

快速查找：[公共层](#d-公共层)、[M1](#m1-自习座位预约)、[M2](#m2-整室申请)、[M3](#m3-报修与验收)、[跨模块](#跨模块一致性)、[工程交付](#工程交付)。本表面向验收；使用系统请读[用户手册](user-guide/README.md)。

当前新增 GitHub / codex-lb 目标的状态见[后续验收](#github-与-codex-lb-的后续验收)：v0.2.2 正式发布/部署与 11 个课程账号登录已验证；本次备份/隔离恢复42表一致、独立只读UI通过；资源观察仅覆盖12次无PR CI负载的采样，生成器修复的新提交以PR Checks为准。以下 39 项 VERIFIED 保留原 v0.1.0 交付范围。

更新时间：2026-10-23。V6本机浏览器、正式离线导入、同schema正式回退/恢复及源码绑定运输包均通过；feature683506完整五job PASS，MR !1已Fast-forward合main，annotated tag已指向同一S。main683689与tag683743完整五job均已PASS，[v0.1.0正式Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已发布；用户确定的本机与可迁移交付范围内39项P0均为VERIFIED。提交前限定审计见[报告](evidence/runtime-audit-final-v6.md)，GitLab最终事实见[发布记录](evidence/gitlab-release-v0.1.0.md)。本表逐条保留[PRD](../01_PRD.md)的**39个P0 REQ**，关联[技术方案](../02_Technical_Design.md)和[milestone](../03_Development_Milestones.md)；每项测试只证明其实际断言与运行条件。

用户调整的交付边界见 [ADR 0002](adr/0002-delivery-and-gitlab.md)：GitLab 取代 GitHub Actions；用户授权自主提交和本地发版；正式公网/TLS、外部SMTP实际收信、正式服务器生产发布待后续目标信息。业务、权限、并发、数据保护要求不缩减。本表中的“部分验证”表示已有运行证据但尚欠该 TC 的具体子场景；“缺口”表示已经识别实现或交付不足；“待执行”不能视为通过。

## 状态口径与当前候选

39项由31条业务需求（D7、M1 7、M2 5、M3 8、X4）和8条工程需求组成。`VERIFIED`表示该行列明行为及对应交付门禁已有实际证据，不扩展其运行条件或冒充人工签字。当前V6为 `r-20261023T022529-1079900`，运行源码S为 `10799003cd38a28629dc5ea39835c8313b1e9d79`，对应main与tag；后续纯文档/证据HEAD另行记录，不为归档重建镜像。[242份生产/构建输入](evidence/release/final-v6-production-equivalence.json)与R4/V5一致，因此性能/SQL及core/repair恢复保留R4原标签，不冒称V6重跑或镜像ID相同。31条业务与8条工程证据保留各自实际范围，main/tag完整门禁及正式Release已闭合；提交前限定审计范围以报告为准。

- B `66f2b92`：新隔离离线安装及真实浏览器6/6通过；随后补充375px历史再次预约、拒绝理由与终态页面通过。
- R1 `14b9813`：首轮浏览器5/6；确认按钮实际可访问名称被残留loading图标污染。`3350d5b`显式设置稳定aria-label/aria-busy，独立代码审查确认未削弱业务校验，浏览器恢复精确角色定位。
- R2 `3350d5b`：完整浏览器5/6，原R1预约/原Cookie在真实午夜后通过375px页面签到；后被同室维修转为INTERRUPTED，**不是自然COMPLETED证据**。第二次报修在教室加载中打开导致空options，真实1800ms延迟复现。
- R3 `3c7a93c`：`a5695c3`等待房间加载，`3c7a93c`统一持久化微秒精度；[前端6/6及1800ms延迟回归](evidence/frontend-release.md)通过，0跳过/重试，真实失败报告隐私反证1/1通过。入口19282的三个服务版本相同。
- GitLab682941/682949的firstAssignedAt严格一致性失败已用固定纳秒Clock+真实PG/HTTP复现。修复后[两个期限HTTP、四个PG回归](evidence/repair-timestamp-precision.md)通过，原Python断言不变；远端R3真实集成已通过；容器发布在runner初始化时失败，该轮完整门禁未通过。
- [R3→B兼容探针](evidence/release/final-compatibility.json)和[实际rollback](evidence/release/final-actual-rollback.json)均通过：三镜像、两原Cookie、三类业务和扩展schema保留。A/B/R3三个清单及R3五镜像包已留存。[R3实际导入核对](evidence/release/final-portable-import.json)通过，385份源码、5镜像、3版本一致，7份密钥与三类业务行摘要不变；目标无Git，导入没有pull/build。
- [R3运行隐私审计](evidence/final-runtime-audit.md)：116份运行产物（含15份限定日志）和HTML内嵌ZIP，对181个已知实际值/727种形式0命中；收尾412份源码/新增文档以174个当时已知值/701种形式补扫0命中。原运行快照与源码补扫分开记录，远端GitLab日志、截止后的活动及R4运行不在该轮范围；旧源码审计保持原样。
- R4 `3012051` / `r-20261022T170200-3012051`：已包含支持工具`4a83b6e`的CI/身份门禁/采集器与真实规模修正；[浏览器6/6](evidence/frontend-release-v4.md)和privacy1/1、[414源码/五镜像正式导入](evidence/release/v4-portable-import.json)、[两原会话/三业务兼容](evidence/release/v4-compatibility.json)及[正式回退](evidence/release/v4-actual-rollback.json)均实际通过。A/B/R3/R4四清单与R4五镜像arm64包保留；R3原证据未改名。
- R4[完整性能/SQL/repair恢复](evidence/performance.md)与[core真实恢复](evidence/core/restart-recovery-v4.md)通过：62892请求，读p95 231.914ms/写390.488ms，5xx/传输/意外4xx为0；repair20任务20.393秒，core首12.860秒/全10任务14.057秒及65秒无重复。保留历史100002条，之后core合成夹具增至100003。[R4统一源码/运行审计](evidence/runtime-audit-v4.md)已完成，限定范围已知秘密0命中。
- GitLab pipeline683030：backend4478860通过4分10秒、frontend4478861通过1分20秒、integration4478862通过15分56秒；112ops、18HTTP、9组真实PG、27期限收件人及已提交响应丢失重放均执行。**三job通过不等于pipeline通过**：共享Runner #5186的官方DinD entrypoint报挂载权限拒绝、无法挂载`/sys/kernel/security`，daemon未启动，需要支持DinD的隔离环境；该轮container-release与完整正门禁未闭环。
- V5 `e95b025`：正式离线入口核对[442份源码/五镜像/三版本](evidence/release/final-v5-portable-import.json)，原7份秘密及三类业务完整行保持；[浏览器6/6与privacy1/1](evidence/frontend-release-final-v5.md)通过，97.599秒，无跳过或重试。[V5→R4→V5正式回退/恢复](evidence/release/final-v5-r4-rollback.json)在独立兼容环境保留两原会话、三类业务完整行、schema与原秘密，最终恢复V5。该轮两版schema相同，原R4→B结构演进证据仍单独保留。
- 用户已明确授权，专用[Runner5666实际注册启用](evidence/gitlab-runner-registration.md)，Protected/项目锁定/拒绝untagged，17.8.5/linux-arm64。真实诊断pipeline683461/job4481126通过43秒，双端Docker28.5.2、Compose2.40.3、TLS与嵌套只读checkout均通过；可选service-helper警告按原日志保留。诊断不等于完整应用流水线。
- V5 [pipeline683462](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683462)的backend/frontend/integration均PASS；integration4481129约15分10秒，122项ops、27个期限收件人断言及9份JUnit报告均执行/产出。container-release4481130在官方Alpine HTTPS CDN安装Python/Git时发生TLS EOF/超时，FAILED且未执行业务构建，package跳过。[CI-only HTTPS镜像修正](evidence/gitlab-alpine-mirror.md)保留五job、needs、TLS和包签名验证。后续11803dac曾启动[pipeline683500](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683500)验证修正；最终采用S的683506实际完整结果，旧pipeline不回填为成功。
- V6 `10799003` / `r-20261023T022529-1079900`：[450源码/五镜像正式导入](evidence/release/final-v6-portable-import.json)、[浏览器6/6与privacy1/1](evidence/frontend-release-final-v6.md)、[V6→V5→V6正式回退](evidence/release/final-v6-v5-rollback.json)均通过。原7秘密和导入时21预约/18申请/13维修完整行保持；回退保留两原会话、三业务及结构/Flyway，首次夹具重叠409保留。运输包精确绑定S，414289744字节，SHA256 `7b143161a31778be71ebabc20a1dffbc8d744cda454335df1fa82b3f571be7ee`，位于 `.local/releases/v0.1.0/`，旧V5包不覆盖。
- [feature683506](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683506)的backend/frontend/integration/container-release/package五job全部PASS；MR !1已Fast-forward合main至S，保留67提交、0 merge commit、未squash。`v0.1.0` annotated tag已推送并指向S；[main683689](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683689)与[tag683743](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683743)均完整五job PASS。两轮各执行66项数据库测试/124项ops及真实浏览器6/6，tag另记录隐私反证957.42035ms；[正式Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已发布并绑定同一S、manifest及运输包。
- GitLab负面合并门禁：[MR !2](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/2)使用独立分支`ycy/ci-negative-gate`的故意失败JUnit提交`d6a4d29`，pipeline 682968已因预期测试失败，非Draft页面明确阻止合并，integration/container-release/package均跳过。已关闭测试MR，破坏提交不在交付分支历史中；见[负向门禁证据](evidence/gitlab-negative-gate.md)。

[R4统一运行审计](evidence/runtime-audit-v4.md)覆盖97份冻结运行输入（含13份应用及过程日志）与30份源码增量，对184个已知私有值、741种形式匹配均为0；HTML内嵌ZIP已解码检查。应用日志快照截至2026-10-22 17:24:41 UTC，源码收尾范围与精确时刻见[机器结果](evidence/runtime-audit-v4.json)。远端GitLab日志、日志截止后的活动和后续文件改动均不在本次快照范围。

[V5限定隐私审计](evidence/runtime-audit-final-v5.md)在2026-10-23 00:26:37 UTC冻结128份输入，对129个实际已知值、513种形式为0命中；[提交前限定补扫](evidence/precommit-final-v5-supplement.json)在00:39:29 UTC覆盖14份当时待提交文件和7份指定CI/归档元数据，共21份输入，同一已知值集合0命中。两轮范围分别冻结，PNG只做原始字节匹配；本审计未读取VM内Runner token，不将较早的独立token扫描扩展到新增文件。后续CI/main/tag日志及本次追踪表新增字节不在这些旧快照内。

## 实现与证据索引

- [Core 模块](modules/core.md)、[账号与管理](modules/administration.md)、[维修模块](modules/repair.md)、[前端模块](modules/web.md)。
- [Core 实测](evidence/core/acceptance.md)：7个规则单测、6个真实HTTP、16个隔离PG、3个故意破坏反证及恢复通过；[剩余验收矩阵][matrix]另有15个隔离PG＋真实HTTP用例，以及1个真实双服务动态角色用例。另有[契约](evidence/core/contracts.md)5例与[typed输入/管理矩阵][typed]6例；各轮结果按对应代码与运行环境记录，不合并冒充最终发布镜像验收。
- [独立 M1/M2 浏览器试用](evidence/core/browser-review.md)：实际桌面与375px、多账号操作；不模拟资源裁决。
- [维修实测](evidence/repair-validation-2026-10-22.md)：10个规则单测、4个隔离PG、2个真实HTTP、提交成功后响应丢失重试、独立手机试用。
- [D 管理实测](modules/administration.md#迁移与验证)：9个隔离PG、2个真实HTTP、SELF范围和跨服务旧会话失效。
- 共享层真实PG 7/7由根代理执行；断言见 [SharedPersistenceTest][shared-test]。真实SMTP失败恢复与三种提醒收信见[新增矩阵][repair-matrix]；进程恢复专项仍另验。
- 主逻辑首轮提交：`34aef3c`（构建/迁移基础）、`49300fb`（D/M1/M2行为）、`1ec7ccb`（M3）、`8c50781`（前端）、`e68cedb`（前端细化）。JPA整改、日期修复、原子审批、容器基线、离线运维、结构化日志与GitLab CI均已分模块提交；发布tag/manifest已绑定S，正式Release已创建；后续文档工作区不冒充新的运行镜像。

## D 公共层

| REQ / milestone | 状态 | 实现 | 已执行证据 | 验收结论与边界 |
| --- | --- | --- | --- | --- |
| REQ-D-01 / T10-01、T10-06 | VERIFIED | [认证][auth]、[安全][security]、[账号][admin] | [矩阵][matrix]实际HTTP验证退出后重放旧Cookie拒绝、禁用后旧会话与直接登录401、无新增订单；跨服务旧会话core/repair均401 | TC-D-01组合已验证；R4浏览器刷新会话及正式回退两原会话复核均通过 |
| REQ-D-02 / T10-02、T50-01 | VERIFIED | [RBAC][access]、[角色管理][admin] | Admin PG验证同绑定权限/范围与SELF隔离；[双服务矩阵][matrix]新建仅统计权限动态角色→统计成功→真实维修派单拒绝且工单不变→撤销后两服务旧会话401、新登录统计403；关联用户角色删除409 | 动态角色完整TC已验证；R4浏览器已复验学生/管理员及维修角色边界，原动态角色矩阵保留其实际运行身份 |
| REQ-D-03 / T10-02、T50-01 | VERIFIED | [范围授权][access]、[可用性][availability] | 同绑定隔离与范围mutation；[输入/管理矩阵][typed]学生管理读写拒绝，R1管理者不能修改R2房间/座位/开放时间/送达重试，不能看R2占用/码，R2审计/送达/统计排除；失败逐字段不变，R1写成功 | TC-D-03的院系/资源范围与失败不变已验证；R4既定权限浏览器场景已通过，原资源范围矩阵不改写为本版全量重跑 |
| REQ-D-04 / T10-03 | VERIFIED | [主数据][admin]、[房间锁与来源][resource] | 父停用/子创建并发；[矩阵][matrix]占用座位停用保护/历史保留/整室中新座位不可约；[输入矩阵][typed]停用房间使最终审批拒绝、维修解除仍停用且不可预约，PENDING保持 | 关键资源停用/新增/审批组合已验证；R4页面业务链通过，原主数据矩阵保留其独立证据 |
| REQ-D-05 / T10-03 | VERIFIED | [配置管理][admin]、[订单配置快照][booking] | [矩阵][matrix]旧4小时订单逐字段不变；改为2小时后3小时拒绝/2小时成功；0/13/小数及非管理员拒绝；新审计旧4/新2、操作者与时间严格核对 | TC-D-05已验证；发现并修复配置审计before_json缺失，未放宽断言 |
| REQ-D-06 / T10-05、T20-04 | VERIFIED | [通知][notifications]、[任务与事件][events] | Shared PG去重/租约；[真实SMTP矩阵][repair-matrix]421连续8失败、299秒不发/300秒第9次、管理员原记录重试后Mailpit第10次收信，readAt保持且预约不回滚；M1三种提醒实际收到；他人已读404 | 本地真实SMTP失败恢复已验证；R4进程恢复业务通知去重通过，实际SMTP收信保持本矩阵独立证据，正式邮箱按ADR延期 |
| REQ-D-07 / T10-05、T50-01 | VERIFIED | [审计事件][events]、[审计查询][queries] | Shared PG审计UPDATE/DELETE权限拒绝及JOB类型；Core PG objectId/动作/房间筛选、学生403；[矩阵][matrix]配置旧/新值、操作者与时间，重复取消/签到仅1条审计 | [故障矩阵](evidence/repair-fault-matrix.md)已核对BLOCK/RELEASE两侧审计、outbox和日志的同一requestId，五份日志对34个实际秘密及编码变体无命中；[R3限定范围运行日志扫描](evidence/final-runtime-audit.md)已归档0命中，[R4统一运行审计](evidence/runtime-audit-v4.md)已完成，限定范围已知秘密0命中 |

## M1 自习座位预约

| REQ / milestone | 状态 | 实现 | 已执行证据 | 验收结论与边界 |
| --- | --- | --- | --- | --- |
| REQ-M1-01 / T20-01 | VERIFIED | [可用性SQL分页][availability]、[查询][queries] | Core PG过滤/分页total；[矩阵][matrix]混合插座/占用精确过滤、整室边界阻断、前一end可用；网格返回不可用原因且无用户/来源字段；真实浏览器选日期/座位 | TC-M1-01已验证；B原记录保留，R4真实浏览器已复验查询、选择与空结果 |
| REQ-M1-02 / T20-01 | VERIFIED | [预约事务][booking]、[资源裁决][resource] | 20用户1成功19冲突、同人跨室互斥、首尾相接成功；[输入矩阵][typed]无时区/粒度/当前起点/零长度/14天边界真实HTTP及失败无数据；原Map幂等成功记录按原JSON重放，字段差异409 | TC-M1-02及指定输入/幂等风险已验证；R4性能窗口外同座20用户争抢仍为1成功19冲突 |
| REQ-M1-03 / T20-02 | VERIFIED | [取消][booking] | 浏览器桌面创建→手机取消；[矩阵][matrix]start前1秒成功/start当刻拒绝、非本人404且状态不变，同键重放1次转换/审计、claim释放且零违约 | TC-M1-03边界与归属组合已验证；R4发布镜像真实创建、取消与409回归通过 |
| REQ-M1-04 / T20-03 | VERIFIED | [每日码][codes]、[签到][booking]、[限流][limits] | [矩阵][matrix]昨日/他室码拒绝，start前1秒拒绝/start当刻大小写空白归一成功，成功重放1次审计，第6次429、60秒后恢复、+15分钟拒绝；授权屏幕与普通接口明码/hash/密文隔离 | 每日码关键API矩阵已验证；[输入矩阵][typed]补小数/字符串/负数/溢出/缺失version仍计数，第6次429；R2在真实午夜后用原Cookie完成375px页面签到，随后被同室维修中断；[R3限定范围部署/运行日志秘密扫描](evidence/final-runtime-audit.md)已通过，[R4统一运行审计](evidence/runtime-audit-v4.md)已完成，限定范围已知秘密0命中 |
| REQ-M1-05 / T20-04 | VERIFIED | [持久任务][tasks]、[预约处理][booking] | Core PG8线程仅1违约/通知、维修公平处理；[矩阵][matrix]三个持久提醒任务各重跑3次仅1EMAIL记录，+14:59/+15:00签到与NO_SHOW双线程屏障仅产生对应合法终态 | 三类真实邮件已验证；R3真实core正常stop/start同容器，10积压任务11.183秒处理，NO_SHOW/COMPLETED/EXPIRED、零过期PRE/LATE、唯一通知/违约及65秒去重通过；原数据行摘要不变。首次采集器JSON解析失败保留，修复后完整重跑通过，见[恢复证据](evidence/core/restart-recovery.md)；R4独立复验首12.860秒/全10任务14.057秒及65秒稳定性通过，见[R4恢复](evidence/core/restart-recovery-v4.md) |
| REQ-M1-06 / T20-05 | VERIFIED | [历史与再次创建][booking]、[页面][web] | 日期半开边界/状态组合/分页total/400的PG与HTTP、375px真实日期筛选；[矩阵][matrix]历史停用与M2占用再约失败、合法时段新编号且原历史逐字段不变 | TC-M1-06及B版[375px真实再次预约](../tests/browser/REVIEW.md#同一旧-b-版补充历史复用与拒绝页面)已验证：新时段查询、新编号、原历史逐字段不变；R4发布镜像已复验同一用例，新编号及原历史保持不变 |
| REQ-M1-07 / T20-05 | VERIFIED | [统计聚合][queries] | [矩阵][matrix]真实JPA固定历史含取消/NO_SHOW/INTERRUPTED/跨日/未来/范围外：总数7、取消2、未签到1、签到率3/4、座位小时7.5；空集rate=null，范围外排除 | TC-M1-07固定数据手算与实际API通过；页面静态复核为聚合预约/签到指标并解释口径，无个人违约排名。统计fixture不声称历史全流程 |

## M2 整室申请

| REQ / milestone | 状态 | 实现 | 已执行证据 | 验收结论与边界 |
| --- | --- | --- | --- | --- |
| REQ-M2-01 / T30-01、T30-04 | VERIFIED | [提交及步骤][application] | 独立浏览器提交；[矩阵][matrix]纯学生403→动态叠加申请人角色后旧会话失效→新登录创建成功，同键同编号，PENDING不占用，expires_at两个分支 | TC-M2-01与[输入矩阵][typed]的时区/整点/起点/零长度/14天边界均通过；实际页面提交及必填校验已验证，R4已复验提交、两级批准、撤销与拒绝页面链路 |
| REQ-M2-02 / T30-02 | VERIFIED | [两级审批事务][application] | 第二级抢先403、冲突回滚、两人浏览器批准；[PG屏障与claim写后回滚](evidence/core/atomic-workflows.md)；[矩阵][matrix]申请人即使有权限也不可自批，同人不可两级且状态/动作数量保持合法 | 关键顺序/角色/并发TC已验证；R4发布组合两名独立审批人逐级批准通过 |
| REQ-M2-03 / T30-03 | VERIFIED | [撤销与过期任务][application] | 浏览器开始前撤销；[截止当刻审批/超时竞争](evidence/core/atomic-workflows.md)；[矩阵][matrix]开始当刻本人取消拒绝且claim保留，范围管理员空理由拒绝、带理由成功释放 | 权限/时点/过期数据库与API组合已验证；R4真实core停启后PENDING→EXPIRED、原任务完成及65秒去重通过，见[恢复证据](evidence/core/restart-recovery-v4.md) |
| REQ-M2-04 / T30-02、T30-03、T30-05 | VERIFIED | [整室claim][resource]、[最终审批][application] | HTTP M1阻止最终审批/释放后批准；[矩阵][matrix]10–12整室占用后4座位（含新建）10–11及11–12都409，12开始成功，取消后恢复；M3双来源实测 | TC-M2-04整室半开边界、新增座位及来源限定释放已验证；与双维修来源、资源查询组合证据共同覆盖REQ-X-01 |
| REQ-M2-05 / T30-01、T30-04 | VERIFIED | [历史与读取范围][application] | 三账号浏览器显示顺序/时间/人员；[矩阵][matrix]拒绝理由保留，无关学生编号访问404且不含内容；撤销创建权限后本人仍读历史、新建拒绝 | TC-M2-05通过；B版[真实拒绝页面](../tests/browser/REVIEW.md#同一旧-b-版补充历史复用与拒绝页面)另验空理由无请求、拒绝理由/人员/时间可见、刷新后终态无审批按钮；不把创建权限撤销的本人例外扩大为管理历史特权；R4同样真实拒绝/手机理由及终态权限回归通过 |

## M3 报修与验收

| REQ / milestone | 状态 | 实现 | 已执行证据 | 验收结论与边界 |
| --- | --- | --- | --- | --- |
| REQ-M3-01 / T40-01、T40-06 | VERIFIED | [维修服务][repair] | [真实HTTP矩阵][repair-matrix]空白/超长输入拒绝、100 emoji/2000字边界通过、跨院系403、他人/范围外详情404且列表不泄漏；原有幂等/手机/同室双来源链路 | 服务端输入/范围/双来源已验证；R3完整6流程及真实1800ms延迟定向回归通过，加载中入口busy/disabled、响应后3个实际选项，创建/取消成功，见[最终前端证据](evidence/frontend-release.md)；[R4六流程](evidence/frontend-release-v4.md)已重复验证同一慢网络主链 |
| REQ-M3-02 / T40-02、T40-06 | VERIFIED | [派单与权限][repair] | [真实HTTP矩阵][repair-matrix]非维修角色/停用人员422、范围外404；重派历史保留且firstAssignedAt/workDueAt相同，旧维修人404、新维修人开始成功；已开始不能转派；各维修人收到对应一次ASSIGNED | 本地TC及固定纳秒反证已验证；3c7a93c修复持久化微秒精度，生命周期首次响应/PG回读/幂等重放严格一致，两个期限HTTP/四个PG回归通过，见[精度证据](evidence/repair-timestamp-precision.md)。远端完整门禁由ENG-05跟踪 |
| REQ-M3-03 / T40-03 | VERIFIED | [outbox与worker][repair]、[core封禁][resource] | 真实HTTP BLOCK_PENDING→IN_REPAIR；透明代理丢失已提交响应后原命令重试成功 | [故障矩阵](evidence/repair-fault-matrix.md)已验证写回网络阻断、8次失败后attention/300秒退避、恢复后自动IN_REPAIR及375px状态刷新；整个core停机时开始动作因无法校验权限返回503、工单不变。两种故障按真实行为区分；公开内部路径404已验 |
| REQ-M3-04 / T40-04 | VERIFIED | [完工][repair] | [真实HTTP矩阵][repair-matrix]他维修人404/非执行物业403、空白/2001字422、2000字通过，失败版本/历史不变；仅报修人收到AWAITING_ACCEPTANCE，claim持续有效 | TC-M3-04关键组合已验证；R4实际维修人完工、资源仍下架及独立验收链通过 |
| REQ-M3-05 / T40-04、T40-05 | VERIFIED | [验收/来源释放][repair]、[资源][resource] | [真实HTTP矩阵][repair-matrix]报修人兼维修人兼物业仍403自验，另一合格物业通过后CLOSED；空白/超长驳回理由422，驳回仍封禁；原有双来源/来源mutation通过 | 独立验收、驳回及释放失败后的自动重试已验证；[故障矩阵](evidence/repair-fault-matrix.md)实际RELEASE_PENDING→网络恢复→CLOSED，封禁解除且旧BLOCK不复活；R4发行组合实际独立验收、资源恢复及CLOSED已通过 |
| REQ-M3-06 / T40-06 | VERIFIED | [评价][repair] | [真实HTTP矩阵][repair-matrix]0/6/1.5/4.0/布尔/字符串/超长说明拒绝，IN_REPAIR/待验收不能评分；同键重放原评价、不同键409；非报修人403且原评分/状态不变 | 评分类型、状态、归属与幂等已有真实HTTP矩阵证据；R4六流程未重复评分矩阵，不把未执行场景写为本版浏览器通过 |
| REQ-M3-07 / T40-02、T40-04 | VERIFIED | [期限任务][repair] | 原有PG轮次/8线程/返修；[三deadline真实core HTTP][repair-matrix]前1ns不触发、当刻重复3次仅1命令，同命令重复HTTP仍去重；3事件×9角色收件计数正确；验收超时公开M1仍不可约，独立验收后恢复；[性能环境](evidence/performance.md)重启20任务12.09秒处理 | 三期限、通知对象与超时仍封禁已验证；R4实际repair停启20项积压20.393秒处理，见[性能与恢复](evidence/performance.md) |
| REQ-M3-08 / T40-01 | VERIFIED | [取消][repair] | [真实HTTP矩阵][repair-matrix]本人SUBMITTED取消、ASSIGNED本人拒绝/调度有原因成功；IN_REPAIR/待验收取消失败且claim不变；测试Clock+真实core HTTP验证两同步状态也拒绝；取消后派单/开始拒绝 | TC-M3-08关键状态组合已验证；R4手机真实创建/取消及终态无活动截止通过 |

## 跨模块一致性

| REQ / milestone | 状态 | 实现 | 已执行证据 | 验收结论与边界 |
| --- | --- | --- | --- | --- |
| REQ-X-01 / T10-04、T40-05、T40-07 | VERIFIED | [来源实体/资源][resource] | Core PG双来源与墓碑；repair真实HTTP双工单先后解除；来源限定mutation被检测 | TC-X-01双维修封禁先后解除、来源历史保留已验证；永久来源/墓碑、管理查询与M1/M2可用性使用同一裁决模型，关联REQ-D-07/X-03的来源及requestId实测 |
| REQ-X-02 / T10-04、T30-02、T30-05 | VERIFIED | [统一房间锁][resource]、[审批事务][application] | 同座20线程；[M1/M2最终审批双线程屏障只1成功，以及POST_INSERT确认claim落库后PG约束失败的整笔回滚](evidence/core/atomic-workflows.md) | TC-X-02两项专项通过；pipeline683030真实集成job已通过，完整容器发布/合并门禁仍由ENG-05闭环 |
| REQ-X-03 / T10-05、T40-03、T40-05 | VERIFIED | [outbox][repair]、[永久去重/墓碑][resource] | repair故障代理保留相同commandId/requestId/payload两次HTTP；Core PG先RELEASE后旧BLOCK不复活 | [故障矩阵](evidence/repair-fault-matrix.md)补齐网络不可达/退避恢复、双侧审计与原命令重放；已释放来源对新command的旧版本返回STALE_IGNORED。R4真实repair/core恢复已通过；既有响应丢失与旧BLOCK故障矩阵保留其独立运行身份 |
| REQ-X-04 / T10-04、T40-03、T40-07 | VERIFIED | [维修级联][resource] | repair HTTP未来预约取消且不复活；Core PG维修/NO_SHOW两种先后均无错误追责；[同室五态、3条正确通知/审计、零违约、释放不复活](evidence/core/atomic-workflows.md) | TC-X-04五态、三条目标通知/审计及零违约通过；本地SMTP及收件对象由D-06矩阵独立证明 |

## 工程交付

| REQ / milestone | 状态 | 实现 | 已执行证据 | 验收结论与边界 |
| --- | --- | --- | --- | --- |
| REQ-ENG-01 / T00-01、T60-01 | VERIFIED | [协作规则](../AGENTS.md)、[分工](team.md)、本表 | 分模块连续commit与agent实测记录；原规格与ADR已入库 | 逐REQ实现/TC/逻辑commit已有索引；[MR !1](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/1)已按FF合入S，feature683506五job PASS，V6清单/源码包已绑定。main/tag完整门禁与正式Release均已完成；后续文档HEAD与运行源码S分开，不冒充人工签字 |
| REQ-ENG-02 / T00-02、T50-02 | VERIFIED | [HTTP测试][core-http]、[PG测试][core-test]、[反证][mutation]、浏览器证据 | 真实PG与两个进程HTTP、3项变异均业务断言失败且恢复通过，独立桌面/手机 | 默认完整check与三项反证通过；R3/R4/V5历史保留，[V6浏览器6/6及privacy1/1](evidence/frontend-release-final-v6.md)通过。[R4性能/SQL](evidence/performance.md)62892请求/读p95 231.914ms/写390.488ms/零错误，repair20任务20.393秒；[core恢复](evidence/core/restart-recovery-v4.md)首12.860秒/全10任务14.057秒及65秒去重，原运行标签不改。main自动部署门禁归ENG-05 |
| REQ-ENG-03 / T00-04、T50-04 | VERIFIED | [迁移程序][migration]、[版本SQL](../db/migration) | 空库迁移/reference/demo、重复seed；新增SELF纠正迁移在原库后应用，未改原迁移 | [A/B及R3历史演练](evidence/release/README.md)保留；[R4→B正式回退](evidence/release/v4-actual-rollback.json)已核对operator_note扩展、两原会话、三业务及实际Flyway历史不变。R4清单与回退目标已绑定，最终tag/Release对应ENG-06证据 |
| REQ-ENG-04 / T00-03、T00-04、T50-05 | VERIFIED | [环境与发布脚本][ops]、[GitLab CI][ci] | local与隔离PG套件使用不同数据库；独立test Compose安装由根代理执行 | 独立test/portable/check项目使用分离源码/数据库/会话/卷和随机秘密；[默认check](evidence/core/default-check.md)确认原native进程不变。`8b8a3d3`统一12个legacy入口锁；`247b02d`使原生build/migrate/copy全生命周期按环境→workspace顺序持锁，真实无关PID拒绝和异常释放通过 |
| REQ-ENG-05 / T00-05、T50-05 | VERIFIED | [GitLab CI][ci]、[全仓检查](../scripts/check) | [默认完整check](evidence/core/default-check.md)实际exit0，18 HTTP/65 PG/27收件人及响应丢失重放；CI调用相同函数与清单 | [feature683506](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683506)在S上完整五job PASS，MR !1已FF合main；[main683689](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683689)与[tag683743](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683743)自动完整部署/E2E均通过，每轮66项数据库测试、124项ops、浏览器6/6，五job无跳过。专用Runner5666的项目/保护边界保持，[负面MR !2](evidence/gitlab-negative-gate.md)阻断已验证。历史共享Runner挂载与V5 Alpine依赖安装失败保留，不混作本轮结果 |
| REQ-ENG-06 / T00-06、T50-04～06 | VERIFIED | [发布/回退][ops]、[版本API][controller] | 不可变组合校验与错误manifest的ops单测；本地版本接口实现 | A/B/R3/R4/V5历史机制证据保留；[V6正式导入](evidence/release/final-v6-portable-import.json)及[V6→V5→V6回退](evidence/release/final-v6-v5-rollback.json)通过，六套清单和组合留存。S对应feature全绿与FF main，annotated tag已推送；450源码/五镜像运输包已[完整核对](evidence/release/final-v6-archive-verification.json)。main/tag最终门禁通过，[正式Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已发布，prod按ADR保留边界 |
| REQ-ENG-07 / T00-03、T60-02 | VERIFIED | [安装入口](../install.sh)、[部署脚本][ops] | 根代理已完成本机独立test Compose首次安装；前端生产入口独立复核 | B新隔离安装及R3/R4/V5导入历史保留；[V6正式导入](evidence/release/final-v6-portable-import.json)核对450源码内容/权限、五镜像/三版本、原7秘密及21/18/13业务完整行保持，浏览器6/6。目标无Git/Java/Node；本机ARM64同daemon、既有状态升级边界明确，不冒称异机/断网/另建空库；README已补两层归档校验、固定状态目录和准确环境参数 |
| REQ-ENG-08 / T00-01、T50-01 | VERIFIED | [安全][security]、[伦理记录](ethics-and-risks.md) | HTTP CSRF/越权拒绝、Cookie HttpOnly/SameSite配置，维修公平处理PG；非真实学生测试数据 | 签到码/权限/CSRF、聚合统计与伦理记录已有证据；R3/R4/V5原审计保留范围。V6浏览器privacy1/1通过，61份原始产物对7运行秘密及HTML内嵌ZIP0命中；提交前限定审计见[V6报告](evidence/runtime-audit-final-v6.md)，main/tag/Release事实见[发布记录](evidence/gitlab-release-v0.1.0.md)，不扩展报告的范围与截止时刻。V5审计与补扫保持旧范围；正式HTTPS按ADR保留边界 |

## 无REQ编号但仍属于验收基线的项目

| 基线 | 已知事实 | 当前证据与边界 |
| --- | --- | --- |
| PRD§10 必备页面和错误反馈 | React/Umi页面及B/R3/R4/V5真实浏览器历史证据保留 | [V6完整6流程](evidence/frontend-release-final-v6.md)通过，0跳过/重试，真实加载、离线/错误/空态/375px及报告隐私反证通过。R2跨升级签到后INTERRUPTED仍按原版本记录，本轮不重复签到或额外慢网络专项 |
| PRD§12 数据规模 | 数据库分页/聚合已整改，R3原规模结果保留 | R4实际1000账号/20房/1000座/100002 COMPLETED历史，负载前后完成数不变，总预约123061→130862；后续明确合成core夹具才增至100003。采用至少10万门槛，保留所有累计数据，不过滤或伪装为100000 |
| PRD§12 性能 | R3原数值及运行标签差异如实保留 | R4共享4CPU/总8GiB限额，20会话、60秒预热+300秒80:20：62892请求，读p95 231.914ms、写390.488ms、5xx/传输/意外4xx0；同座1成功19冲突。三镜像及三版本一致，SQL房间1/20均13条、预约9/11条无逐行关系增长，见[性能证据](evidence/performance.md) |
| PRD§12 定时与恢复 | Clock/数据库矩阵、R3通过及首次采集失败记录保留 | R4健康repair20期限最大41.171秒，停启20积压20.393秒；core同容器正常停启首12.860秒/全10任务14.057秒，3终态、零过期提醒、单通知/违约、65秒无重复及原行摘要不变。不冒称合成夹具为UI自然完成 |
| PRD§12 容器持久化 | 命名volume与共享JDBC Session实现 | [保留卷重建](evidence/release/backup-and-recreate.json)确认三容器ID均改变、两原会话可用、三类订单及两侧审计完整行摘要不变；隔离备份恢复同样逐表匹配 |
| 技术方案§1/§2.3/§6 契约 | 日常写入已JPA；[56公开+5内部具名schema与真实HTTP](evidence/core/contracts.md)；[关键M1/M2 typed命令及原hash重放][typed] | 保留已有Java Map投影不单独作为业务缺陷；关键输入已类型化，OpenAPI具名schema与实际HTTP检查约束边界。新增接口或变更时同步契约；默认完整check已实际纳入5例契约和6例输入边界，不再列为未执行 |
| milestone G0～G6 | 对应39项P0在本次交付范围内已闭合，原milestone初始状态保留为规格 | [工作进度](progress.md)的MS0～MS6为DONE、MS7为NOT_STARTED；实际证据按版本记录，不回填人工签字或历史pipeline |

## 2026-10-23 GitLab / ARM64 归档交付边界

1. **本机V6已验。** S的450源码/五镜像正式导入、浏览器6/6及privacy1/1、V6→V5→V6正式回退与运输包核对通过。242生产/构建输入与R4/V5一致，性能/SQL及恢复保留R4原标签；不扩展重复性能测试，也不假称旧结果为V6实测。
2. **历史和数据保留。** R4负载100002历史、core合成恢复后100003；午夜签到属于14b→R2且后续INTERRUPTED。V6首次API夹具因旧预约重叠409的证据保留，选择真实空闲时段后通过，未删旧业务或弱化规则。
3. **feature与main。** 683506在S上完整五job PASS，MR !1已FF合main，67提交/0 merge commit/未squash；main683689与tag683743的五job、自动整套部署及真实E2E均实际通过。Runner诊断和旧三个job通过不代替这轮完整证据，V5/共享Runner失败历史不改写。
4. **tag与Release。** `v0.1.0` annotated tag已指向S，tag对象 `257c481e0432183ec059581179b95f0e93127f36`；main/tag完整门禁均通过，[正式Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已发布，精确绑定MR、pipeline、tag、manifest与已核对运输包；后续纯文档HEAD不改变运行S或触发新镜像构建。
5. **发行范围。** A/B/R3/R4/V5/V6六套清单和五镜像组合按版本保留，旧包不覆盖；仅本机linux/arm64实际验收。正式服务器、公网TLS、外部SMTP及prod按[ADR 0002](adr/0002-delivery-and-gitlab.md)保留边界，不伪造签字或另一架构验收。
6. **有限隐私与追踪。** 39条REQ不增删、均为VERIFIED。提交前限定审计见[V6报告](evidence/runtime-audit-final-v6.md)，以报告列明输入、已知值与截止时刻为准；旧审计不自动覆盖新增文件或后续活动。P0完成限于本次已授权交付范围，不意味着完成公网生产部署或未执行的跨架构验收。

P1 `REQ-AGENT-01`未启动，不计入以上39项，也不用于替代P0验收。

[auth]: ../services/core-service/src/main/java/com/campus/studyroom/core/AuthController.java
[security]: ../services/core-service/src/main/java/com/campus/studyroom/core/SecurityConfiguration.java
[admin]: ../services/core-service/src/main/java/com/campus/studyroom/core/AdminService.java
[access]: ../services/core-service/src/main/java/com/campus/studyroom/core/AccessService.java
[booking]: ../services/core-service/src/main/java/com/campus/studyroom/core/BookingService.java
[application]: ../services/core-service/src/main/java/com/campus/studyroom/core/ApplicationService.java
[resource]: ../services/core-service/src/main/java/com/campus/studyroom/core/ResourceService.java
[availability]: ../services/core-service/src/main/java/com/campus/studyroom/core/AvailabilityReadRepository.java
[queries]: ../services/core-service/src/main/java/com/campus/studyroom/core/QueryService.java
[codes]: ../services/core-service/src/main/java/com/campus/studyroom/core/CheckinCodes.java
[limits]: ../services/core-service/src/main/java/com/campus/studyroom/core/RateLimits.java
[notifications]: ../services/core-service/src/main/java/com/campus/studyroom/core/Notifications.java
[events]: ../services/core-service/src/main/java/com/campus/studyroom/core/Events.java
[tasks]: ../services/core-service/src/main/java/com/campus/studyroom/core/TaskExecutor.java
[controller]: ../services/core-service/src/main/java/com/campus/studyroom/core/CoreController.java
[repair]: ../services/repair-service/src/main/java/com/campus/studyroom/repair
[web]: ../apps/web/src
[core-http]: ../tests/integration/core_http.py
[core-test]: ../services/core-service/src/test/java/com/campus/studyroom/core/CorePersistenceTest.java
[shared-test]: ../services/core-service/src/test/java/com/campus/studyroom/core/SharedPersistenceTest.java
[mutation]: ../tests/integration/core_mutation.py
[migration]: ../tools/migration-runner/src/main/java/com/campus/studyroom/migration/MigrationRunner.java
[ops]: ../scripts/studyroom.py
[ci]: ../.gitlab-ci.yml

[matrix]: evidence/core/acceptance-matrix.md

[repair-matrix]: evidence/repair-acceptance.md

[typed]: evidence/core/typed-inputs.md

## GitHub 与 codex-lb 的后续验收

上表 VERIFIED 与原交付边界按原版本保留。2026-10-23 用户新增 GitHub Actions、codex-lb 同机 CI/生产、GHCR/Release 与公网 IP HTTPS 目标，决策见 [ADR 0004](adr/0004-github-codex-lb-production.md)，操作见 [GitHub 手册](operations/github.md)。新范围按下表的**实际验证范围与限制**记录：最新 PR、tag 与 Actions 结果见 [v0.2.2 记录](evidence/github-production-v0.2.2.md)，此前 [v0.2.1 浏览器失败](evidence/github-production-v0.2.1.md)与 [v0.2.0 发布中断](evidence/github-production-v0.2.0.md)保留原范围；宿主 TLS 配置与观测见[接手卡](operations/current-instance.md#codex-lb-生产快照)。

| 新平台范围 | 已执行证据 | 限制与后续检查 |
| --- | --- | --- |
| REQ-ENG-04：环境隔离 | codex-lb 独立 CI 状态、三镜像 test 验证及正式 prod 部署通过，生产持久状态与候选测试分离；部署后 12 次采样的四入口与 5 个容器正常 | 采样窗口无 PR CI 负载，不代表完整共同运行或性能验收 |
| REQ-ENG-05：GitHub CI | PR #1 / #2 / #3 完整 verify 通过并 rebase merge；PR #3 的 164 ops、真实 PostgreSQL/HTTP、期限邮件/重放及 6 项浏览器验证包含弹窗修复；v0.2.2 正式 verify/publish/deploy 全部成功；仓库公开后 General ruleset 已 active，main 强制 PR、最新基线上的 verify/ci-gate、线性历史且无 bypass | 分支保护设置已由 API 读回；轻量 ci-gate 工作流新增及本次文档的实际完整结论以对应 PR 最新 SHA Checks 为准，未要求第二位人工批准 |
| REQ-ENG-06：固定版本与推广 | v0.2.2 稳定 Release、6 个资产 digest、3 个已测试 image ID 核对一致，同一 run/attempt 的固定组合正式推广成功 | 新主机生产回退未在本次执行；旧 v0.2.0 部分上传与 v0.2.1 失败独立保留 |
| REQ-ENG-07：服务器安装与维护 | Linux AMD64 正式部署、HTTPS 版本与课程登录通过；宿主 unit 修正后实际 systemd 备份、正式隔离恢复及 42 表全部 COPY 行比较通过；生成器修复两轮 11 项定向测试与独立复核通过 | 生成器修复的完整结论按贡献指南由对应 PR Checks 确认，旧 v0.2.2 包不重复 install；本次恢复未切换在线库，不扩展到后续恢复点或异机切换 |
| REQ-ENG-08：安全与隐私 | 公网 IP 证书、外部 TLS 校验、续期 dry-run、CI 报告隐私及 11 个课程账号的真实 HTTPS 登录通过；独立学生/管理员 × 桌面/375px 只读试用、会话属性及双向路由权限通过 | 外部 SMTP 实际收信仍待配置，未在生产重跑写入 E2E |

生产应用与课程登录已有实际结果；备份调度首次失败、宿主修正与恢复成功按各自事实保留，独立UI与12次部署后采样已有证据；后续CI负载不在该窗口内，生成器修复的新提交以PR Checks为准，不由 deploy 成功推断全部完成。课程初始应用凭据曾按用户在私有阶段的授权集中于[演示账号](development/demo-accounts.md)；公开仓库不自动扩大凭据授权，既有账号另按用户确认处理，基础设施秘密不写入源码或报告。2 核/2 GiB 的串行 CI 成功不代表同机生产容量或原规格性能已验收；后续新证据由[进度](progress.md)和[证据索引](evidence/README.md)串联。
