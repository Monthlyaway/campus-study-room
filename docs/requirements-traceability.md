# P0 需求、实现与验收追踪

更新时间：2026-10-22。本表逐条保留 [PRD](../01_PRD.md) 的 **39 个 P0 REQ**，并关联 [技术方案](../02_Technical_Design.md)、[milestone](../03_Development_Milestones.md)。测试通过只证明其实际断言；一个有关联的测试通过，不等于整条 TC 或阶段门禁通过。当前仍有明确缺口与未执行验收，**尚不能声明全部 P0 完成**。

用户调整的交付边界见 [ADR 0002](adr/0002-delivery-and-gitlab.md)：GitLab 取代 GitHub Actions；用户授权自主提交和本地发版；正式公网/TLS、外部SMTP实际收信、正式服务器生产发布待后续目标信息。业务、权限、并发、数据保护要求不缩减。本表中的“部分验证”表示已有运行证据但尚欠该 TC 的具体子场景；“缺口”表示已经识别实现或交付不足；“待执行”不能视为通过。

## 实现与证据索引

- [Core 模块](modules/core.md)、[账号与管理](modules/administration.md)、[维修模块](modules/repair.md)、[前端模块](modules/web.md)。
- [Core 实测](evidence/core/acceptance.md)：7个规则单测、6个真实HTTP、16个隔离PG、3个故意破坏反证及恢复通过；[剩余验收矩阵][matrix]另有15个隔离PG＋真实HTTP用例，以及1个真实双服务动态角色用例。各轮结果按对应代码与运行环境记录，不合并冒充最终发布镜像验收。
- [独立 M1/M2 浏览器试用](evidence/core/browser-review.md)：实际桌面与375px、多账号操作；不模拟资源裁决。
- [维修实测](evidence/repair-validation-2026-10-22.md)：10个规则单测、4个隔离PG、2个真实HTTP、提交成功后响应丢失重试、独立手机试用。
- [D 管理实测](modules/administration.md#迁移与验证)：7个隔离PG、2个真实HTTP、SELF范围和跨服务旧会话失效。
- 共享层真实PG 7/7由根代理执行；断言见 [SharedPersistenceTest][shared-test]。真实SMTP失败恢复与三种提醒收信见[新增矩阵][repair-matrix]；进程恢复专项仍另验。
- 主逻辑首轮提交：`34aef3c`（构建/迁移基础）、`49300fb`（D/M1/M2行为）、`1ec7ccb`（M3）、`8c50781`（前端）、`e68cedb`（前端细化）。JPA整改、日期修复、原子审批、容器基线、离线运维、结构化日志与GitLab CI均已分模块提交；发布tag/manifest由发版任务补齐；不把工作区冒充已发布版本。

## D 公共层

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-D-01 / T10-01、T10-06 | [认证][auth]、[安全][security]、[账号][admin] | [矩阵][matrix]实际HTTP验证退出后重放旧Cookie拒绝、禁用后旧会话与直接登录401、无新增订单；跨服务旧会话core/repair均401 | TC-D-01组合已验证；最终部署的Cookie与会话恢复仍随工程验收复核 |
| REQ-D-02 / T10-02、T50-01 | [RBAC][access]、[角色管理][admin] | Admin PG验证同绑定权限/范围与SELF隔离；[双服务矩阵][matrix]新建仅统计权限动态角色→统计成功→真实维修派单拒绝且工单不变→撤销后两服务旧会话401、新登录统计403；关联用户角色删除409 | 动态角色完整TC已验证；最终部署仍跑权限回归 |
| REQ-D-03 / T10-02、T50-01 | [范围授权][access]、[可用性][availability] | 跨院系拒绝/共享教室成功及同绑定隔离；[矩阵][matrix]仅R1管理员改R1成功、直接改R2拒绝且数据库逐字段不变；范围mutation被捕获 | 关键越权组合已验证；所有管理列表与写接口范围仍需逐端点核对 |
| REQ-D-04 / T10-03 | [主数据][admin]、[房间锁与来源][resource] | Admin PG规则版本及父停用/子创建并发；[矩阵][matrix]占用座位停用409→取消后停用成功→历史仍读，整室占用期间新增座位仍不可约 | 主数据关键TC已验证；禁用房间影响待审批申请、维修解除不得重新启用等组合仍需逐项补证 |
| REQ-D-05 / T10-03 | [配置管理][admin]、[订单配置快照][booking] | [矩阵][matrix]旧4小时订单逐字段不变；改为2小时后3小时拒绝/2小时成功；0/13/小数及非管理员拒绝；新审计旧4/新2、操作者与时间严格核对 | TC-D-05已验证；发现并修复配置审计before_json缺失，未放宽断言 |
| REQ-D-06 / T10-05、T20-04 | [通知][notifications]、[任务与事件][events] | Shared PG去重/租约；[真实SMTP矩阵][repair-matrix]421连续8失败、299秒不发/300秒第9次、管理员原记录重试后Mailpit第10次收信，readAt保持且预约不回滚；M1三种提醒实际收到；他人已读404 | 本地真实SMTP失败恢复已验证；进程重启后发送仍归工程验收，正式邮箱按ADR延期 |
| REQ-D-07 / T10-05、T50-01 | [审计事件][events]、[审计查询][queries] | Shared PG审计UPDATE/DELETE权限拒绝及JOB类型；Core PG objectId/动作/房间筛选、学生403；[矩阵][matrix]配置旧/新值、操作者与时间，重复取消/签到仅1条审计 | 部分验证：维修BLOCK/RELEASE按同工单关联两侧requestId、拒绝审批/越权审计与完整部署日志秘密扫描仍待补；本轮三份日志密码/探针扫描无命中仅限本轮 |

## M1 自习座位预约

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-M1-01 / T20-01 | [可用性SQL分页][availability]、[查询][queries] | Core PG过滤/分页total；[矩阵][matrix]混合插座/占用精确过滤、整室边界阻断、前一end可用；网格返回不可用原因且无用户/来源字段；真实浏览器选日期/座位 | 关键DB/API组合已验证；最终页面完整空态/不可用交互仍归页面矩阵 |
| REQ-M1-02 / T20-01 | [预约事务][booking]、[资源裁决][resource] | HTTP20用户屏障1成功19冲突且1claim；同用户跨2室1成功、幂等重放、冲突mutation；[矩阵][matrix]同用户首尾相接两次持久化成功 | 部分验证：全部输入边界已有规则单测，14天/无时区/粒度等关键实际HTTP组合仍需补证 |
| REQ-M1-03 / T20-02 | [取消][booking] | 浏览器桌面创建→手机取消；[矩阵][matrix]start前1秒成功/start当刻拒绝、非本人404且状态不变，同键重放1次转换/审计、claim释放且零违约 | TC-M1-03边界与归属组合已验证；发布镜像业务回归仍待执行 |
| REQ-M1-04 / T20-03 | [每日码][codes]、[签到][booking]、[限流][limits] | [矩阵][matrix]昨日/他室码拒绝，start前1秒拒绝/start当刻大小写空白归一成功，成功重放1次审计，第6次429、60秒后恢复、+15分钟拒绝；授权屏幕与普通接口明码/hash/密文隔离 | 每日码关键API矩阵已验证；完整部署日志的码/会话/口令秘密扫描另行验收 |
| REQ-M1-05 / T20-04 | [持久任务][tasks]、[预约处理][booking] | Core PG8线程仅1违约/通知、维修公平处理；[矩阵][matrix]三个持久提醒任务各重跑3次仅1EMAIL记录，+14:59/+15:00签到与NO_SHOW双线程屏障仅产生对应合法终态 | 三类真实邮件已由[SMTP矩阵][repair-matrix]验证；进程停启跨多个时点不补过期提醒和最终镜像恢复仍待执行 |
| REQ-M1-06 / T20-05 | [历史与再次创建][booking]、[页面][web] | 日期半开边界/状态组合/分页total/400的PG与HTTP、375px真实日期筛选；[矩阵][matrix]历史停用与M2占用再约失败、合法时段新编号且原历史逐字段不变 | 日期与再次预约DB/API组合已验证；再次预约按钮的完整真实浏览器操作仍待页面矩阵确认 |
| REQ-M1-07 / T20-05 | [统计聚合][queries] | [矩阵][matrix]真实JPA固定历史含取消/NO_SHOW/INTERRUPTED/跨日/未来/范围外：总数7、取消2、未签到1、签到率3/4、座位小时7.5；空集rate=null，范围外排除 | 固定复杂数据手算与实际API通过；统计fixture不是历史全流程，页面指标名称和隐私仍需页面复核 |

## M2 整室申请

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-M2-01 / T30-01、T30-04 | [提交及步骤][application] | 独立浏览器提交；[矩阵][matrix]纯学生403→动态叠加申请人角色后旧会话失效→新登录创建成功，同键同编号，PENDING不占用，expires_at两个分支 | 角色叠加/幂等/期限关键API组合已验证；输入边界完整API矩阵仍需对照 |
| REQ-M2-02 / T30-02 | [两级审批事务][application] | 第二级抢先403、冲突回滚、两人浏览器批准；[PG屏障与claim写后回滚](evidence/core/atomic-workflows.md)；[矩阵][matrix]申请人即使有权限也不可自批，同人不可两级且状态/动作数量保持合法 | 关键顺序/角色/并发TC已验证；发布部署组合仍跑审批回归 |
| REQ-M2-03 / T30-03 | [撤销与过期任务][application] | 浏览器开始前撤销；[截止当刻审批/超时竞争](evidence/core/atomic-workflows.md)；[矩阵][matrix]开始当刻本人取消拒绝且claim保留，范围管理员空理由拒绝、带理由成功释放 | 权限/时点/过期数据库与API组合已验证；真实进程恢复期限补偿仍归工程验收 |
| REQ-M2-04 / T30-02、T30-03、T30-05 | [整室claim][resource]、[最终审批][application] | HTTP M1阻止最终审批/释放后批准；[矩阵][matrix]10–12整室占用后4座位（含新建）10–11及11–12都409，12开始成功，取消后恢复；M3双来源实测 | 整室半开边界与新增座位已验证；保留其他有效M1/M2/维修来源的完整组合仍见REQ-X-01 |
| REQ-M2-05 / T30-01、T30-04 | [历史与读取范围][application] | 三账号浏览器显示顺序/时间/人员；[矩阵][matrix]拒绝理由保留，无关学生编号访问404且不含内容；撤销创建权限后本人仍读历史、新建拒绝 | 关键历史/隐私API通过；审批角色撤销后的历史范围规则及拒绝页面显示仍需明确操作证据 |

## M3 报修与验收

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-M3-01 / T40-01、T40-06 | [维修服务][repair] | [真实HTTP矩阵][repair-matrix]空白/超长输入拒绝、100 emoji/2000字边界通过、跨院系403、他人/范围外详情404且列表不泄漏；原有幂等/手机/同室双来源链路 | TC-M3-01关键输入与范围组合已验证；最终发布环境回归另验 |
| REQ-M3-02 / T40-02、T40-06 | [派单与权限][repair] | [真实HTTP矩阵][repair-matrix]非维修角色/停用人员422、范围外404；重派历史保留且firstAssignedAt/workDueAt相同，旧维修人404、新维修人开始成功；已开始不能转派；各维修人收到对应一次ASSIGNED | TC-M3-02完整关键组合已验证；最终发布环境回归另验 |
| REQ-M3-03 / T40-03 | [outbox与worker][repair]、[core封禁][resource] | 真实HTTP BLOCK_PENDING→IN_REPAIR；透明代理丢失已提交响应后原命令重试成功 | 部分验证：单独补core完全断网→页面显示同步中→恢复自动收敛；生产入口拒绝内部封禁请求验证 |
| REQ-M3-04 / T40-04 | [完工][repair] | [真实HTTP矩阵][repair-matrix]他维修人404/非执行物业403、空白/2001字422、2000字通过，失败版本/历史不变；仅报修人收到AWAITING_ACCEPTANCE，claim持续有效 | TC-M3-04关键组合已验证；最终发布环境回归另验 |
| REQ-M3-05 / T40-04、T40-05 | [验收/来源释放][repair]、[资源][resource] | [真实HTTP矩阵][repair-matrix]报修人兼维修人兼物业仍403自验，另一合格物业通过后CLOSED；空白/超长驳回理由422，驳回仍封禁；原有双来源/来源mutation通过 | 独立验收与驳回已验证；释放失败后的重试/状态矩阵仍待专项 |
| REQ-M3-06 / T40-06 | [评价][repair] | [真实HTTP矩阵][repair-matrix]0/6/1.5/4.0/布尔/字符串/超长说明拒绝，IN_REPAIR/待验收不能评分；同键重放原评价、不同键409；非报修人403且原评分/状态不变 | 评分类型、状态、归属与幂等已验证；最终发布环境回归另验 |
| REQ-M3-07 / T40-02、T40-04 | [期限任务][repair] | 原有PG轮次/8线程/返修；[三deadline真实core HTTP][repair-matrix]前1ns不触发、当刻重复3次仅1命令，同命令重复HTTP仍去重；3事件×9角色收件计数正确；验收超时公开M1仍不可约，独立验收后恢复；[性能环境](evidence/performance.md)重启20任务12.09秒处理 | 三期限、通知对象与超时仍封禁已验证；最终发布镜像恢复另验 |
| REQ-M3-08 / T40-01 | [取消][repair] | [真实HTTP矩阵][repair-matrix]本人SUBMITTED取消、ASSIGNED本人拒绝/调度有原因成功；IN_REPAIR/待验收取消失败且claim不变；测试Clock+真实core HTTP验证两同步状态也拒绝；取消后派单/开始拒绝 | TC-M3-08关键状态组合已验证；最终发布环境回归另验 |

## 跨模块一致性

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-X-01 / T10-04、T40-05、T40-07 | [来源实体/资源][resource] | Core PG双来源与墓碑；repair真实HTTP双工单先后解除；来源限定mutation被检测 | 主路径已验证，待按管理查询核对全部来源历史、释放时间/requestId，并补释放后仍有M1/M2来源的组合 |
| REQ-X-02 / T10-04、T30-02、T30-05 | [统一房间锁][resource]、[审批事务][application] | 同座20线程；[M1/M2最终审批双线程屏障只1成功，以及POST_INSERT确认claim落库后PG约束失败的整笔回滚](evidence/core/atomic-workflows.md) | TC-X-02两项专项通过；最终部署组合仍须完整集成回归，不据此关闭工程门禁 |
| REQ-X-03 / T10-05、T40-03、T40-05 | [outbox][repair]、[永久去重/墓碑][resource] | repair故障代理保留相同commandId/requestId/payload两次HTTP；Core PG先RELEASE后旧BLOCK不复活 | 已验证提交后丢响应与墓碑；补双侧审计关联、重启与租约过期/网络不可达组合；正式发布下恢复另验 |
| REQ-X-04 / T10-04、T40-03、T40-07 | [维修级联][resource] | repair HTTP未来预约取消且不复活；Core PG维修/NO_SHOW两种先后均无错误追责；[同室五态、3条正确通知/审计、零违约、释放不复活](evidence/core/atomic-workflows.md) | TC-X-04数据库级联专项通过；对应SMTP实际接收仍与通知/工程验收联合验证 |

## 工程交付

| REQ / milestone | 实现 | 已执行证据 | 当前状态与剩余验收 |
| --- | --- | --- | --- |
| REQ-ENG-01 / T00-01、T60-01 | [协作规则](../AGENTS.md)、[分工](team.md)、本表 | 分模块连续commit与agent实测记录；原规格与ADR已入库 | 部分验证：逐REQ补最终commit/MR/tag/manifest闭环；独立agent审核不冒充人工签字；原人工提交流程按用户授权调整 |
| REQ-ENG-02 / T00-02、T50-02 | [HTTP测试][core-http]、[PG测试][core-test]、[反证][mutation]、浏览器证据 | 真实PG与两个进程HTTP、3项变异均业务断言失败且恢复通过，独立桌面/手机 | 反证子门禁通过；完整M1签到/完成、M2拒绝/过期和多角色全部UI E2E仍未覆盖，不能据此关闭G5 |
| REQ-ENG-03 / T00-04、T50-04 | [迁移程序][migration]、[版本SQL](../db/migration) | 空库迁移/reference/demo、重复seed；新增SELF纠正迁移在原库后应用，未改原迁移 | 部分验证：补旧版业务数据→新schema升级→旧版镜像仍读写的完整演练及数量报告 |
| REQ-ENG-04 / T00-03、T00-04、T50-05 | [环境与发布脚本][ops]、[GitLab CI][ci] | local与隔离PG套件使用不同数据库；独立test Compose安装由根代理执行 | 待执行test/prod（或等效隔离验收环境）不串数据证据；同环境运维跨进程排队及另一环境并行已实测；集成测试入口锁仍需统一 |
| REQ-ENG-05 / T00-05、T50-05 | [GitLab CI][ci]、[全仓检查](../scripts/check) | 本机全仓check通过；有构建/生成漂移/PG/HTTP任务配置 | **未闭环：GitLab真实提交触发、故意失败阻断部署与合并、修复后整套test自动升级及E2E。** 配置文件存在不等于pipeline已运行 |
| REQ-ENG-06 / T00-06、T50-04～06 | [发布/回退][ops]、[版本API][controller] | 不可变组合校验与错误manifest的ops单测；本地版本接口实现 | 待执行带新增可空列V2→旧组合V1回退、关键业务仍可用且schema/数据保留；三版可拉取留存与最终tag/manifest。正式生产审批按ADR保留待验收 |
| REQ-ENG-07 / T00-03、T60-02 | [安装入口](../install.sh)、[部署脚本][ops] | 根代理已完成本机独立test Compose首次安装；前端生产入口独立复核 | 待在该新环境跑完整预约/审批/报修并归档安装/健康/业务证据；另一服务器迁移复现与正式目标条件分开记录 |
| REQ-ENG-08 / T00-01、T50-01 | [安全][security]、[伦理记录](ethics-and-risks.md) | HTTP CSRF/越权拒绝、Cookie HttpOnly/SameSite配置，维修公平处理PG；非真实学生测试数据 | 部分验证：全运行日志/提交秘密扫描、签到码泄露接口矩阵、统计无个人违约排名与四类伦理记录的具体commit关联；正式HTTPS按ADR延期 |

## 无REQ编号但仍属于验收基线的项目

| 基线 | 已知事实 | 仍需执行 |
| --- | --- | --- |
| PRD§10 必备页面和错误反馈 | React/Umi页面已实现，独立M1/M2/M3与admin部分流程已操作 | 逐页面角色可见性、409刷新、未知结果同幂等键重试、401/403/422/429/503、加载/空态、键盘与375px完整矩阵 |
| PRD§12 数据规模 | 数据库分页/聚合已整改 | 已完成指定规模及真实SQL/EXPLAIN，[两轮性能证据](evidence/performance.md)；最终发布镜像复验待执行 |
| PRD§12 性能 | 已有20请求资源正确性测试 | 共享四CPU/总8GiB容器限额、20并发、60秒预热+5分钟80:20：复测读p95 153.50ms/写252.93ms/5xx=0；宿主共享条件与镜像ID已记录，正式release复验待执行 |
| PRD§12 定时与恢复 | Clock测试+数据库任务/租约持久化；M3响应丢失重试30秒左右收敛 | 真实停启跨期限、恢复≤60秒开始补偿、健康延迟≤60秒；各类型误提醒/重复业务核对 |
| PRD§12 容器持久化 | 命名volume与共享JDBC Session实现 | 重建应用容器前后核对订单、审批、维修、会话与审计，保留数据卷且验证数量/关键对象 |
| 技术方案§1/§2.3/§6 契约 | 日常写入已JPA实体化；OpenAPI生成可编译 | 部分管理/业务请求仍Map和通用Envelope，补精确DTO/schema及错误契约对照；不能将类型生成成功等同契约完整 |
| milestone G0～G6 | 多项实际实现/测试已完成，原milestone初始状态保留为规格 | 只有本表所有相应证据闭环后更新工作进度门禁，不能回填不存在的人工验收或历史pipeline |

## 下一轮优先验收清单

1. 日期、再次预约、签到码/限流、取消时点、M2自批/同人两级与复杂统计已补真实DB/API矩阵；继续M1/M2时间输入边界与管理接口逐端点权限矩阵，勿重复把已通过子场景列为缺口。
2. 三类提醒实际邮件及SMTP失败恢复已通过；继续M1/M3真实进程停启、迟到任务补偿与最终发布镜像60秒指标。
3. 维修主要负面HTTP矩阵已补齐；继续释放失败、跨服务审计关联与完整来源保留组合；最终发布环境再跑关键业务链。
4. 完成新test容器三条业务链、保留卷重建、备份恢复与V2→V1兼容回退，生成可核验release manifest。
5. 实际运行GitLab pipeline正反门禁与整套自动部署，归档提交/MR/流水线/版本链接。
6. 已有两轮指定规模/5分钟性能证据，继续最终发布镜像复验；补精确DTO/OpenAPI、全秘密扫描、接口/页面矩阵、文档复现后再决定阶段门禁状态。

P1 `REQ-AGENT-01` 未启动，不计入以上39项，也不用于替代P0验收。

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
