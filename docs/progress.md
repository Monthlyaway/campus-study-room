# 当前进度与交付状态

[文档首页](README.md) · [39项P0追踪](requirements-traceability.md) · [验收证据](evidence/README.md)

当前最新状态：仓库已公开，main 的 General 分支保护已启用；本次 `ci-gate` 与文档修改仍须通过对应 PR Actions，不提前记为通过。详见本页末尾与 [ADR 0005](adr/0005-public-repository-protection.md)。

生产交付快照：v0.2.2 正式发布并部署，11 个课程账号、独立桌面/375px 只读 UI、实际备份/隔离恢复的 42 表内容核对通过。部署后 12 次资源采样正常，窗口未覆盖 PR CI 负载；备份生成器修复及本轮文档交由 PR Actions 验证。实际结果与首次 unit 安装失败见[发布记录](evidence/github-production-v0.2.2.md)。

更新时间：2026-10-23。下方 v0.1.0 与 MS0～MS6 表格记录原本机 / ARM64 可迁移交付：P0 的39项需求已验证，v0.1.0已发布，边界见[ADR 0002](adr/0002-delivery-and-gitlab.md)。后续 GitHub / codex-lb / Linux AMD64 的实际进度见本页末尾；不能把历史结果当作新生产环境验收。P1尚未启动。

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

上述历史验收不覆盖后续正式主机/TLS/外部邮箱、不同CPU架构安装和可选P1；新交付结果按下面的独立记录核对。


## 2026-10-23 — GitHub / codex-lb 生产交付进行中（REQ-ENG-05/07）

用户已指定后续开发、CI 与生产运行均在 codex-lb，使用 GitHub PR rebase merge，默认不 squash。实现位于 `ycy/github-operations`，历史 GitLab 记录保留，现行边界见 ADR 0004。

- codex-lb 专用 GitHub Actions runner 2.338.1 已注册 online，由 systemd 自动启动；Java 21.0.12.1、Node 24.22.1、Docker 29.1.3、Compose 2.40.3 已核实。
- 私有仓库仅允许 rebase merge；API 与网页都明确当前套餐无法强制私有仓库 branch protection，不能报告已强制。production environment 已建立，无伪造人工审批。
- 公网 IP `49.51.193.169` 已取得受信任的短期 HTTPS 证书，80/443 外部可达；nginx 与六小时续期检查 timer 已安装。应用尚未部署，当前 502 不算上线成功。
- 新增 prod 随机管理员 bootstrap、无 demo 门禁、同机内存限制、完整 GitHub CI、GHCR digest/Release 发布和不可变源码部署；针对性单测与 actionlint 已通过。完整 `./scripts/check` 已在 codex-lb 通过：7 组 HTTP / 18 测试、9 组 PostgreSQL / 66 测试、27 项维修通知断言与响应丢失重放；新增及既有运维单测 144 项通过。真实 Actions/镜像/E2E/生产/备份恢复尚待验证。

这是一条实施进度，后续完成证据另附；没有把计划记成生产验收结果。


## 2026-10-23 — GitHub 首次版本发布验证结果（REQ-ENG-05/07/08）

[PR #1](https://github.com/Monthlyaway/campus-study-room/pull/1) 已在完整验证通过后 rebase merge 到 main，保留 9 个逻辑提交、作者信息和源码树。PR verify 和正式 v0.2.0 verify 均通过，包括 158 项运维单测、真实 PostgreSQL/HTTP、三镜像隔离部署和 6 项浏览器测试。

v0.2.0 的 publish 在 core 私有镜像上传后失败：GHCR REST 元数据未返回脚本要求的 repository 字段。只读 Actions 查询与 GitHub 页面已定位该误判，deploy 因上游失败没有执行。现有部分版本保留，修复后重新经 PR 验证并发布新版本。生产应用、实际课程账号、生产备份恢复与公网 UI 验收仍待完成；HTTPS 已可用不等于应用已上线。详见[本次验证与中断记录](evidence/github-production-v0.2.0.md)。


## 2026-10-23 — v0.2.1 浏览器验证失败（REQ-ENG-05/07/08）

GHCR 元数据兼容性修复在 [PR #2](https://github.com/Monthlyaway/campus-study-room/pull/2) 完整 CI 通过后 rebase merge 到 `655cc322b96118827efa9c5572aeab323a055f60`，保留 2 个逻辑提交、作者/日期及源码树。正式 [Actions 39946065589](https://github.com/Monthlyaway/campus-study-room/actions/runs/39946065589) 的全仓检查通过：164 项 ops、7 套 HTTP / 18 项、9 套 PostgreSQL / 66 项、27 项期限收件人断言与响应丢失重放。

正式浏览器验证 6 项中 1 项失败，总耗时 125.841992 秒。失败发生在关闭桌面真实 409 弹窗后缩至 375px，尚未打开历史再约弹窗；现有证据同时显示退出动画沿旧桌面 origin 造成真实越界，以及 helper 枚举可见元素与读取尺寸之间的竞争。修复正在进行，后续验证尚未完成。verify 未通过，publish/deploy 跳过，本版本无新 Release/package；生产部署、课程账号、备份恢复和同机运行观测继续待执行。详见 [v0.2.1 失败记录](evidence/github-production-v0.2.1.md)，旧版本报告保留原结果。


## 2026-10-23 — 手机弹窗退出动画与布局采样修复（REQ-ENG-02/05）

v0.2.1 浏览器失败已在独立测试环境复现：桌面点击位置在缩至 375px 后成为屏幕外的退出动画原点，旧布局 helper 又可能在弹窗消失后等待动态定位器。修复将手机弹窗动画原点固定在中心，并在一次页面快照中读取布局；原选择器、1px 容差和 5 秒上限保留。

新增逐帧回归在旧 CSS 上失败、修复候选上通过。基于未提交工作树构建的独立诊断镜像完成真实浏览器 6/6（124.465 秒）和隐私探针 1/1；另一名 agent 实际检查桌面与 375px，完全展开后关闭分别采样 18/20 帧，均无越界、无 JavaScript 错误。诊断镜像未覆盖原 v0.2.1，不作为正式 CI 或发布证明；开发目录的完整检查已按用户新指示中止，不计为完整通过；后续完整集成验收交由 PR/版本 Actions 执行。该避免重复检查的规则已写入 AGENTS.md 和贡献指南。生产仍未部署。

## 2026-10-23 — v0.2.2 生产交付与验收（REQ-ENG-04/05/06/07/08）

[PR #3](https://github.com/Monthlyaway/campus-study-room/pull/3) 的 [Actions 39952736484](https://github.com/Monthlyaway/campus-study-room/actions/runs/39952736484) 在 head `4d000a175a25cfbddb37e9005e4d9282591cf059` 完整通过：164 项 ops、7 套 HTTP / 18 项、9 套 PostgreSQL / 66 项、27 项期限收件人断言、响应丢失重放及 6 项真实浏览器（127.121 秒）。随后 rebase merge 到 `524134c7ad9b730538f535aa2245c7f8f8d611c0`，3 个逻辑提交、作者日期与源码树保留。

正式 `v0.2.2` 的 [Actions 39955632806](https://github.com/Monthlyaway/campus-study-room/actions/runs/39955632806) attempt 1 的 verify、publish、deploy 均 success，完整检查及 6 项真实浏览器（127.263 秒）通过；稳定私有 [Release v0.2.2](https://github.com/Monthlyaway/campus-study-room/releases/tag/v0.2.2) 已发布。6 个发行资产 digest、3 个已测试 image ID 与发布组合一致，公网首页及三应用版本接口均 200，运行 SHA 为 `524134c7ad9b730538f535aa2245c7f8f8d611c0`。课程初始化完成 11 个账号实际登录验证及 3 房间 / 30 座位，不预造业务订单；凭据只在现有[账号文档](development/demo-accounts.md)维护。重复 main 运行 39955626968 在 check 阶段取消，上传因缺少产物失败，不计为通过。

生产备份调度首次安装因生成的 `WorkingDirectory` 带引号被 systemd 判为 bad-setting，timer 未启动。只修正宿主 service 对应行后，user unit 校验通过，timer enabled/active waiting，Linger=yes；v0.2.2 不可变源码未改写，不应再用旧包重复安装调度。真实 systemd service 于 13:13:58Z 成功生成 `20261023T131356842634Z.dump`，归档 TOC 与完整解码均通过。正式 restore 创建隔离库 `studyroom_restore_20261023131409`，在线库未修改；12 用户、业务订单为 0，原 dump 与隔离库全部 42 表 COPY 行多重集一致、0 序列、无差异。生成器修复在真实 systemd 解析器旧实现上先出现 6 个失败子例，修复后两轮 11 项定向测试及独立代码复核通过。

另一名 agent 在生产对学生/管理员 × 1440px/375px 的四个独立上下文完成只读试用：登录、刷新、查询、导航、双向路由权限及 Secure/HttpOnly/SameSite=Lax 均通过；手机管理员表格可内部横向滚动到右侧操作列。没有业务写入、JavaScript 或 5xx 错误，浏览器均已关闭。

13:14:20.899763Z–13:19:50.924237Z 的 12 次资源采样中，四公开端点均 200/TLS0、三版本一致，5 个生产容器 healthy、重启和 OOM 为 0。该窗口未覆盖 PR CI，不扩展为同机完整 CI 容量结论。完整绑定、失败与修复、验证范围及私有证据位置见 [v0.2.2 发布记录](evidence/github-production-v0.2.2.md)。本次生成器修复和交付文档按[贡献指南](contributing.md)的 PR 流程验证，完整结论以对应 PR 的 Checks 为准，不在开发目录重复整套门禁。

## 2026-10-23 — 公开仓库与 General 分支保护（REQ-ENG-05/07）

用户将仓库改为 public 并要求启用分支保护。GitHub API 已返回 [General ruleset 25729390](https://github.com/Monthlyaway/campus-study-room/rules/25729390) 为 active，目标 main，无 bypass（当前 owner 为 never）：PR 合入、会话解决、线性历史、仅 rebase、禁止强推/删除，以及来自 GitHub Actions App 15368 的 `verify` 与 `ci-gate`，required checks 使用最新基线策略。人工批准数为 0，不伪造独立审批；外部 fork 工作流审批已设为 `all_external_contributors`。

本次工作流增加 `always()` 的轻量结果门禁，仅当 verify 真正 success 才通过；它在无权限、无 checkout 的 GitHub-hosted runner 执行，完整集成和浏览器测试仍在 codex-lb。设置读回证明配置已生效，不替代本次 PR 最新提交的实际 CI；对应完整结果仍待 PR Checks 确认，开发目录不重复整套检查。现有生产 v0.2.2 与冻结发布报告保持原身份和验证范围。发布器同步适配精确仓库身份与 public 可见性校验，GHCR 三镜像仍要求 Private；这项修改由本次 PR 验证，本次不发布新 tag、不重新部署生产。


## 2026-10-24 — 全角色交互清晰度改进（提交前记录）

用户要求在 codex-lb 调整全部角色界面，并明确本次合入 squash、保持线性历史。任务 UX-ADMIN / UX-M1-M2 / UX-M3-D6 / UX-SHELL 对应 REQ-D-01 至 07、REQ-M1-01 至 07、REQ-M2-01 至 05、REQ-M3-01 至 08、REQ-ENG-02/08。

三名 agents 按管理授权、座位与审批、维修与通知分工，root 负责导航与公共交互、集成检查。当前已实现任务化文案、角色默认工作入口、完整时段与下一步提示、名称化的授权对象目录、通知状态独立翻译；业务权限、资源裁决与状态机保持后端执行。新目录只返回名称等元数据，不扩大普通教室业务接口权限。维修列表/详情通过真实内部 HTTP 取得已授权工单的教室名称。

开发预览使用 codex-lb 独立 `.local/local` 状态，与生产隔离。三名 agents 实际试用后修复开发 MFSU 重复 React 白屏、375px 抽屉裁切、禁止浏览器缩放、文本域计数重叠及慢接口下维修轮询请求堆积。最终定向 TypeScript 检查通过；前端 6 项单元测试通过；后端原生构建和重启成功；改动文件秘密扫描未发现已知秘密。

实际通过管理端 20 个桌面/375px场景和手机跨院系授权保存、学生预约/取消、申请人→院系→教室两级审批/撤销，以及派单→维修→拒绝验收返修→再次完工→物业验收→报修人评价。所有临时资源占用已正常释放，测试账号停用。原始截图与边界见[本轮证据索引](evidence/README.md#本轮全角色交互检查)。个别审批员手机详情的修复后人工式复测未完成，已加入正式套件的明确断言，不将未执行项写成通过。

正式浏览器套件新增 13 项，合计 19 项；新增套件已通过加载检查，覆盖11个基础账号、全部适用页面、名称授权保存与签到屏。完整 `./scripts/check`（PostgreSQL/HTTP/SMTP）和不可变三镜像浏览器验收将由[本次 PR 的原生 Actions](https://github.com/Monthlyaway/campus-study-room/pulls?q=is%3Apr+head%3Aycy%2Fclear-role-workflows)执行。本段冻结提交前事实，最终门禁和合入状态以 PR Checks 为准；现有生产仍为 v0.2.2。本次按用户要求保留开发阶段逻辑提交后压成单提交，在不放宽仅 rebase/线性历史规则的前提下合入。

提交后交叉复核另外定位并修复三处边界：相同条件点击查询仍重新读取实时余位；409 冲突阻止原生表单/回车再次提交；邮件多次重试后成功不再同时显示“持续重试”。前两项加入真实浏览器回归。首个 PR CI 主动取消以验证修复后的新提交，不将取消计为成功。

后续原生 CI [40090422345](https://github.com/Monthlyaway/campus-study-room/actions/runs/40090422345) 在 `RepairDeadlineHttpTest` 的完整响应相等断言失败：GET 已附带教室名称，但写操作响应遗漏名称。已统一新写操作响应与幂等持久化结果；保留原有整对象、时间精度与重放断言，未进入镜像浏览器阶段的该次运行不计为通过。修复后在独立 PostgreSQL、core HTTP 和 Mailpit 环境执行定向验证：repair HTTP 1 项、原 `RepairDeadlineHttpTest` 2 项及原收件人精确断言 27 项全部通过（0 跳过）；名称查询在授权后、写事务外完成，旧幂等响应不追补字段。定向证据保留在服务器忽略目录 `output/repair-roomname-check/20261024T115956-42c4ba7b/`，自有进程与容器已关闭、数据卷保留。独立 agent 静态评审通过；最新提交仍须由原生 CI 全量验证。

原生 CI [40092361642](https://github.com/Monthlyaway/campus-study-room/actions/runs/40092361642) 的完整 `./scripts/check` 已通过，包含真实数据库、HTTP、SMTP、27 项收件人断言与已提交响应丢失恢复。三个不可变镜像上的浏览器结果为 8 通过、11 失败：新增角色矩阵的精确导航名称匹配失败，装饰图标将英文图标名混入链接可访问名称。修复导航图标的辅助技术语义，并使选择当前页面时也收起手机菜单（原来仅路径变化时关闭）；保留精确可见文字断言与全部 19 项套件，新增菜单关闭断言，继续由最新提交原生 CI 全量复验。M2 两位审批员的最终 375px 详情截图已由独立 agent 实际查看，布局完整；本轮无查询抽屉截图，不将其视觉复核记为通过。

导航修复提交后独立评审发现新增关闭断言把 Drawer 根节点与内容面板类名误写为同一元素，已改成真实父子选择器，并成对检查打开时存在、关闭后不存在。中间运行 [40094710006](https://github.com/Monthlyaway/campus-study-room/actions/runs/40094710006) 主动取消，最新提交重新执行完整门禁。
