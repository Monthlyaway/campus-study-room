# 开发进度与验收索引

更新时间：2026-10-23。当前运行候选为 R4 `r-20261022T170200-3012051`，完整源码 `3012051f06c601e6c22a03e50d9d307a3054d1d9`。**R4本机验收已通过，P0整体仍为IN_PROGRESS**：浏览器6/6、隐私反证1/1、五镜像离线导入、兼容与正式回退、完整性能/SQL及真实repair/core恢复均有本版本证据。[R4统一运行审计](evidence/runtime-audit-v4.md)已完成，限定范围已知秘密0命中；GitLab完整流水线/main合入及tag仍待闭环。运行镜像绑定3012051，后续工具、文档和证据提交的HEAD不等于运行版本，不倒改既有证据。

用户确定本机与可迁移安装、GitLab、自主提交发版及本地Mailpit，见[ADR 0002](adr/0002-delivery-and-gitlab.md)。正式服务器、域名/TLS与外部SMTP收信未执行；P1 Agent未启动。原三份规格保留，业务与工程要求不缩减。

## 实际结果与待项

[追踪表](requirements-traceability.md)保留39项：31条业务和8条工程需求。已有行为证据覆盖账号/范围、配置、通知、M1/M2/M3状态机、跨模块并发、来源恢复及三项破坏反证；运行身份分别记录，不将旧版通过自动移植到新镜像。

| 阶段 | 状态 | 实际证据 / 尚待完成 |
| --- | --- | --- |
| MS0 工程底座 | IN_PROGRESS | 固定Java21/Node24/PG18；独立test首次安装、默认完整check通过；最终GitLab及main自动整套部署待验 |
| MS1 公共层 | IN_PROGRESS | 管理9项、共享7项真实PG，账号/范围/会话HTTP、SMTP失败恢复、三类实际邮件通过；R3限定范围运行审计已归档，R4统一审计已完成，限定范围0命中 |
| MS2 M1 预约 | IN_PROGRESS | 20用户争抢、同用户冲突、签到时间/码/限流、取消/统计已验；R4手机历史再约/409通过；跨升级实际午夜签到后被维修中断；R4 core真实10任务补偿及65秒去重通过 |
| MS3 M2 审批 | IN_PROGRESS | 自批/同人两级、并发同一步、审批/到期竞争及事务回滚已验；R4两级批准/撤销/拒绝页面与真实进程重启PENDING→EXPIRED通过 |
| MS4 M3 维修 | IN_PROGRESS | 真实HTTP、三期限×9收件人、双来源、响应丢失和网络恢复已验；纳秒往返缺陷修复与R3远端集成通过；R4手机报修→维修→独立验收、慢网络门禁及真实repair恢复通过 |
| MS5 验收恢复 | IN_PROGRESS | A/B回退、保留卷重建、隔离备份恢复、启动失败自动恢复及每日备份已验；R4完整62892请求，读p95 231.914ms/写390.488ms/5xx0，repair20任务20.393秒，core首12.860秒/全10任务14.057秒及65秒去重通过；本机验收完成，远端完整CI仍待闭环 |
| MS6 交接发布 | IN_PROGRESS | 独立agent实际试用；R4浏览器6/6、privacy1/1、414源码/五镜像导入、两原会话/三业务兼容与正式回退通过；A/B/R3/R4清单和离线包保留；R4统一审计已完成；GitLab/main/tag待闭环 |
| MS7 P1 Agent | NOT_STARTED | 可选附加项，不替代P0验收 |

## 已关闭的真实缺陷

- [默认完整check](evidence/core/default-check.md)实际exit0：18项HTTP、65项真实PG、27个期限收件人断言和已提交响应丢失重放。保留本轮卷/凭据，只停止自有进程；原native PID不变。
- `8b8a3d3`统一12个旧runner的环境→workspace锁；`247b02d`覆盖原生build/migrate/copy/readiness全生命周期，精确校验PID归属，拒绝向无关进程发信号。相关锁与PID回归通过；当前支持工具修正后完整Python运维回归112项通过。
- R1浏览器5/6暴露隐藏loading图标污染确认按钮可访问名称；`3350d5b`提供稳定aria-label和aria-busy，严格按钮定位未弱化。R2主维修链通过，但第二次报修打开时房间仍加载，弹窗永久捕获空选项。真实1800ms延迟复现后，`a5695c3`使入口等到数据就绪，并给出失败/空结果反馈；R3同条件及完整6流程均通过。见[最终前端证据](evidence/frontend-release.md)。
- GitLab682941及682949真实失败于改派前后firstAssignedAt严格一致性，并非SMTP。固定纳秒Clock+真实PostgreSQL/HTTP复现第一次响应纳秒与回读微秒不同；`3c7a93c`在写入前统一至数据库微秒精度，原严格Python断言不变。两个期限HTTP及四个PG回归全部通过，见[时间精度反证与修复](evidence/repair-timestamp-precision.md)。

## 发布证据与边界

当前本机入口 `http://localhost:19282`，本地Mailpit `http://localhost:19227`。R4三服务身份一致；[浏览器6/6](evidence/frontend-release-v4.md)使用Chromium、1440×1000及375×812，100.806秒，无mock、无跳过/隐藏重试，真实失败报告隐私反证1/1通过。R3原报告另行保留。

R1预约保留原Cookie跨升级到R2，于真实午夜后通过375px页面签到；后续同室维修使其INTERRUPTED，违约为0。此项不写成自然COMPLETED，也不冒称在R3重复签到。原始分版本证据位于`output/browser-release-final/checkin/`。

[R3→B兼容证明](evidence/release/final-compatibility.json)和[实际rollback](evidence/release/final-actual-rollback.json)均通过：三镜像、两原会话、三类业务、新增assigned_to_name列与实际Flyway历史保持。回退不降级数据库。已有[A/B、备份恢复、保留卷重建和启动故障恢复](evidence/release/README.md)仍保留各自版本和条件。

[R4正式导入](evidence/release/v4-portable-import.json)核对414份源码/完整权限、五实际镜像和三版本一致，目标无Git，7原秘密及三类业务完整行摘要保持；[R4→B兼容证明](evidence/release/v4-compatibility.json)和[正式回退](evidence/release/v4-actual-rollback.json)核对两原会话、三业务、operator_note扩展列及数据库历史保留。A/B/R3/R4清单、R3和R4五镜像包分别保留，平台为linux/arm64，不冒称异机/AMD64或隔绝外网验收。

[R4完整性能及SQL](evidence/performance.md)通过：62892统计请求，读p95 231.914ms、写390.488ms，5xx/传输/意外4xx均0；20项repair积压20.393秒处理。[R4 core真实恢复](evidence/core/restart-recovery-v4.md)首12.860秒、全部10任务14.057秒、65秒无重复，原业务行摘要不变。负载保留100002条历史，之后合成恢复夹具增至100003，没有删数据或回填旧结果；三服务运行标签与镜像ID均实际绑定R4。

GitLab [MR !1](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/1)保持Draft等待完整验收。用户要求的Fast-forward、默认不squash、pipeline必须成功且skipped不算成功已实际配置。隔离故意失败的[MR !2 / pipeline682968](evidence/gitlab-negative-gate.md)已验证非Draft状态也阻止合并，后续integration/container-release/package跳过；测试MR已关闭，破坏提交不在交付分支。

[源码审计](evidence/final-source-audit.md)快照d5b4f84：387跟踪文件、近8提交404唯一blob，对140个实际secret/cookie及563变体命中0。可读项目名已统一，锁文件随机摘要保留；临时下载代理没有固化。[R3运行隐私审计](evidence/final-runtime-audit.md)随后归档：116份运行产物（含15份限定日志）及HTML内嵌ZIP，对181个实际已知值/727种形式为0命中；收尾412份源码/新增文档以174个当时已知值/701种形式补扫，同样0命中。扫描截止时间、源码HEAD与镜像身份独立记录；远端GitLab日志、截止后的活动及R4运行不在该轮范围，旧源码快照未改写。

[R4统一运行审计](evidence/runtime-audit-v4.md)覆盖97份冻结运行输入（含13份应用及过程日志）与30份源码增量，对184个已知私有值、741种形式匹配均为0；HTML内嵌ZIP已解码检查。应用日志快照截至2026-10-22 17:24:41 UTC，源码收尾范围与精确时刻见[机器结果](evidence/runtime-audit-v4.json)。远端GitLab日志、日志截止后的活动和后续文件改动均不在本次快照范围。

R4已将CI环境名称、旧Runner可选探针兼容、性能验收工具版本标签/身份门禁、恢复采集器完整JSON解析及真实数据规模报告修正纳入同一完整源码包，支持工具提交为`4a83b6e`。主业务代码与R3一致，但R4上述结果来自独立实际复验；后续证据提交只新增记录，不改变当前3012051运行身份。

## 剩余闭环顺序

1. R4本机既定浏览器、导入、回退、性能/SQL和repair/core恢复已完成，保留R3及首轮失败记录，不因远端CI未通过抹掉本机事实，也不无依据扩展验收排列。
2. GitLab当前[pipeline **683030**](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683030)的backend4478860（4分10秒）、frontend4478861（1分20秒）、integration4478862（15分56秒）均已通过；112ops、18HTTP、9组PG、27期限收件人及已提交响应丢失重放均实际执行。三job通过不能写成完整pipeline成功。共享Runner **#5186** 官方DinD entrypoint已明确报告`mount permission denied` / `Could not mount /sys/kernel/security`，daemon未启动；需要支持DinD的隔离环境，不能把它归为应用测试失败或仅称Docker等待超时。旧PodPending探针问题及修正记录另行保留。
3. 完整同提交pipeline真实成功后，按Fast-forward、不squash合入main，核对main自动整套部署/E2E，再将tag与已验清单精确绑定。当前main/tag门禁未关闭。
4. [R4统一运行审计](evidence/runtime-audit-v4.md)已完成并关联原39条追踪，限定范围已知秘密0命中；最终MR/pipeline/tag/manifest绑定仍待GitLab/main/tag门禁闭合。此处为审计完成后的状态索引更新，新增字节不属于该已冻结审计快照。

根代理随后取消了683030的container-release job4478863，避免在同一已确认无DinD挂载权限的#5186上重复等待；三个先行PASS保持，完整pipeline应记录为Canceled。挂载权限拒绝的实测来源是诊断pipeline683047/job4478933，不能把被取消任务写为已实际失败。

既有Docker test 18082/邮件18027和原生开发8000为分离的旧运行环境，不标记为R4。另一服务器、正式TLS/外部邮箱未实测，按ADR保留限制。
