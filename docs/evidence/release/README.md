# 本机发布、回退与离线安装演练

对应 REQ-ENG-03/05/06/07。执行日期2026-10-22至23（Asia/Shanghai），ARM64 Docker Desktop。当前最终V5入口为应用 `http://localhost:19282`、Mailpit `http://localhost:19227`，发布号 `r-20261023T001633-e95b025`、完整源码 `e95b025de6f03c393d8ad32063ed51b2cbf7babe`。状态目录 `.local/portable-install/`，随机密码只从 `.local/portable-install/secrets/demo_password` 读取，不写入证据。源码安装默认18080；既有18082/8000实例不代表当前V5。

**V5本机验收已通过，整体交付仍未完成。** V5浏览器6/6、正式离线导入、V5→R4→V5正式回退/恢复及[限定隐私审计](../runtime-audit-final-v5.md)均通过。当前源码的[pipeline683462](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683462)已结束：backend/frontend/integration均PASS，integration4481129约15分10秒，执行122项ops、27个期限收件人断言并产出9份JUnit报告；container-release4481130在`apk add`安装Python/Git时访问官方Alpine HTTPS CDN发生TLS EOF/超时而FAILED，尚未执行业务构建，package跳过。完整CI、main、tag和正式release仍待完成，CI-only可信HTTPS镜像修正尚无通过结果。已授权的[专用Runner5666](../gitlab-runner-registration.md)注册启用并通过真实诊断；共享Runner5186的历史挂载失败另行保留，不能将其当作本轮结果。

V5已通过正式离线入口导入19282。其[242份生产及构建输入与R4一致](final-v5-production-equivalence.json)，因此保留R4完整性能/SQL及core/repair恢复的原标签，不重复标作V5实测，也不声称镜像ID相同。不同候选的清单与验收记录按以下版本分别保留。

| 候选 | 完整源码提交 | 数据库变化 |
| --- | --- | --- |
| A `r-20261022T131500-6c9c47359404` | `6c9c47359404d9083ae67dad003ea7feb92d4544` | 含历史查询索引，不含新列 |
| B `r-20261022T132000-66f2b928550f` | `66f2b928550f50d89fe39ed23057beefd66f39cd` | 新增可空 `core.reservation.operator_note`；保留全部既有迁移 |
| R3 `r-20261022T161900-3c7a93c` | `3c7a93cae56e68d2a96adb1e022f4fffc8112c5e` | 保留既有迁移，并含可空 `repair.repair_order.assigned_to_name` |
| R4 `r-20261022T170200-3012051` | `3012051f06c601e6c22a03e50d9d307a3054d1d9` | 业务与迁移和R3相同，离线源码补齐CI及验收工具修正 |
| V5 `r-20261023T001633-e95b025` | `e95b025de6f03c393d8ad32063ed51b2cbf7babe` | 242份生产/构建输入与R4相同，包含后续CI、测试与文档修正 |

A/B两个版本从干净的独立Git工作目录构建，清单绑定完整提交、三个镜像内容ID、Compose/依赖基线/全部迁移摘要。A首次部署创建隔离项目 `studyroom-compat-release`。用真实公共HTTP创建M1预约、经两名不同审批人批准的M2申请和M3报修，并保存两名用户的原会话到0700状态目录下0600私密夹具；夹具不进入仓库。

随后正常部署B、执行迁移、核对原会话和三类数据。向新列写入哨兵值后，维护/排空→旧A三个镜像→原会话读取并取消三类业务→核对新列及Flyway不变→恢复B，才产生绑定双方清单、镜像与数据库的兼容证明。

正式 `scripts/rollback` 使用该证明回到A；[实际回退](actual-rollback.json)再次核对三个运行镜像及版本、两名原会话、三类取消记录、新列和Flyway。首轮A/B演练的应用清单快照见[current-release.json](current-release.json)；该轮数据库仍是B迁移状态，快照记录于[schema-release.json](schema-release.json)。这些历史快照不代表当前V5运行身份。没有运行数据库降级。

[兼容证明](r-20261022T132000-66f2b928550f--r-20261022T131500-6c9c47359404.json)由真实演练工具生成，不是人工填写passed。工具在 `tests/integration/release_compatibility.py`，只允许明确标识的隔离项目。

## R3 发布版回退与前端验收

R3从干净独立工作目录构建，三应用运行身份均为完整提交 `3c7a93cae56e68d2a96adb1e022f4fffc8112c5e`。[R3→B兼容证明](final-compatibility.json)由真实演练工具产生，绑定两份清单SHA256、全部镜像内容ID及实际PostgreSQL/Flyway版本指纹，验证旧应用仍能读写升级后的数据库。该指纹不是完整DDL摘要，不声称检测所有人工结构改动。

随后通过正式回退入口恢复B三个应用，[实际回退证据](final-actual-rollback.json)再次核对原2个会话、3类取消后的业务记录，以及扩展列 `repair.repair_order.assigned_to_name` 和迁移历史保留；没有数据库降级。回退演练在独立compat项目进行，不把其运行版本与19282的R3混淆。

19282的R3完整真实浏览器 **6/6通过，119.255秒，无跳过/重试**，覆盖预约/再次预约/真实409、两级审批与拒绝、多角色报修、移动布局、加载/离线/错误恢复；真实报告隐私反证通过。两项界面故障的失败与修复证据，以及14b创建→R2实时签到→维修中断零违约的独立版本边界见[前端发布验收](../frontend-release.md)。没有在R3重复签到或声称该记录自然到期完成。

## R4 发布版回退与前端验收

R4完整提交 `3012051f06c601e6c22a03e50d9d307a3054d1d9` 的[R4→B兼容证明](v4-compatibility.json)由真实工具生成，核对三镜像、三版本、core/repair原会话、业务数据、旧版本写入与扩展列保留。随后通过正式回退命令验证，[实际回退证据](v4-actual-rollback.json)记录原2个会话、3类已取消业务、扩展列 `core.reservation.operator_note` 和实际数据库指纹保持；无pending、维护或暂停遗留，未降级数据库。演练运行于独立compat项目，当轮19282仍提供R4。

正式离线导入后的19282完整浏览器 **6/6通过，100.806秒，无跳过/重试**，包含桌面/375px、真实409、再次预约、拒绝必填理由、维修资源同步与可读姓名，以及报修慢网络加载门禁。真实失败报告隐私反证1/1通过，7份运行秘密扫描报告及HTML内嵌ZIP无命中。新报告保存在 `output/browser-release-v4/`，详见[R4前端验收](../frontend-release-v4.md)，不覆盖R3或先前失败报告。

R4独立完整六分钟性能、repair恢复、SQL检查及core恢复执行均exit0。测量窗口统计62892请求，读p95为231.914ms、写p95为390.488ms，5xx、传输错误和意外4xx均为0，见[性能记录](../performance.md)。[core恢复证据](../core/restart-recovery-v4.json)记录首次处理12.860秒、全部10任务14.057秒完成，随后65秒观察无重复；保留全部历史，core演练使COMPLETED总数由100002增加到100003，没有删数据缩小规模。

## 最终V5正式导入、回退与浏览器验收

[V5导入证据](final-v5-portable-import.json)记录正式入口exit0：准确提交解包到新的 `.local/portable-source-final-v5/`，442份源码内容及完整权限匹配，无`.git`；五实际镜像和三公开版本一致。复用既有 `.local/portable-install/` 状态，导入前7份秘密及19预约/16申请/11报修的完整行摘要均保持，旧源码与数据卷保留。这些行数是导入时快照，后续真实浏览器会创建业务记录。导入未执行pull/build，但宿主网络未切断，不冒称异机、AMD64或隔绝网络安装。

[V5→R4→V5正式回退与恢复](final-v5-r4-rollback.json)在独立19182兼容项目执行，三个阶段的实际镜像ID及版本均符合各自清单。两原会话、三类业务完整行、7份秘密、数据库结构和迁移历史保持；夹具建立后仅作只读业务核对，没有修改业务状态、删除卷或降级数据库，最终无维护/pending遗留。本轮两版schema相同，没有启动B；旧R4→B含扩展schema的兼容证据继续单独保留。

[V5真实浏览器](../frontend-release-final-v5.md) **6/6通过，97.599秒，无跳过/重试**，涵盖1440×1000桌面及375×812手机、真实409、历史再约、两级审批/拒绝、报修资源同步及终态、加载/离线/错误恢复。真实失败报告隐私反证1/1通过；输出独立保存在 `output/browser-release-final-v5/`。独立代理实际操作及目视截图未发现新的使用阻塞，没有重跑历史午夜签到或把R4证据改标为V5。

[V5限定隐私审计](../runtime-audit-final-v5.md)截至 `2026-10-23T00:26:37.902392Z`，128份输入对129个已知值/513种形式0命中，HTML内嵌ZIP已解码；Runner token为另一次较早的独立扫描，不在这129值内。该快照不包含本次状态索引新增字节、未来GitLab日志或截止后的活动。

## 备份与保留卷重建

正式备份入口生成`pg_dump -Fc`、0600文件、SHA-256并验证归档可读。恢复到新的独立数据库；11用户、1预约、1申请、1报修。三类订单及两侧审计表的排序完整行摘要均与在线库一致。

再以 `--force-recreate` 重建core、repair、web，确认三个容器ID均改变，原两会话仍可登录态读取同三条记录，业务与审计行摘要和新列数据完全不变。[机器可读证据](backup-and-recreate.json)。演练完成后正常停止该项目的三个应用以释放本机资源；数据库、Mailpit、数据卷、秘密、发布历史保留。

## 启动失败自动恢复

在该隔离项目重试部署B，监测到B core进入starting后只断开该容器的private网络，导致真实数据库启动失败。部署命令非零退出，运维入口使用已有B→A兼容证明恢复A三个镜像，并重新开放公开入口。121.9秒后核对原数据库库存、新列哨兵、已取消业务及运行版本全部保留，失败记录归档且pending/维护标记正确清除。见[故障证据](startup-recovery.json)与`tests/integration/release_startup_recovery.py`。

该演练只验证迁移成功后的应用启动故障；部分迁移失败时禁止自动回退的路径仍由专门门禁单测覆盖，未把它冒充已注入真实迁移故障。

## 离线包独立安装

首轮B导出包括固定core/repair/web/PostgreSQL/Mailpit五个镜像、指定提交源码、清单、平台和逐文件SHA-256。导入器解包到不含`.git`的新源码目录，在新项目 `studyroom-portable-install`、独立秘密与数据卷上完成迁移和启动；入口 `http://localhost:19282`，Mailpit `http://localhost:19227`。

[离线导入证据](portable-install.json)核对完整包、源码摘要、五个实际运行镜像ID及全部秘密与来源环境不同。导入器未执行pull/build，目标无需Git/Java/Node。不过本次未切断宿主外网，不能将其称为隔绝网络的实测；两套环境使用同一个ARM64 Docker daemon，也不冒充另一台服务器或AMD64验收。该首轮B的浏览器结果与后续R3分别归档，不混用版本。

本机主目录 `.local/releases/` 现保留A、B、R3、R4、V5五份 `manifest.json`。历史R3包仍在 `.local/releases/r-20261022T161900-3c7a93c/portable/`，包含core、repair、web、PostgreSQL、Mailpit五个linux/arm64镜像、该提交的 `source.tar.gz`、清单及 `SHA256SUMS`。R3导入命令exit0；独立代理核对385份源码内容及执行位、五个实际镜像和三个版本一致，目标无`.git`，7份原密钥未变，预约17/申请14/维修9条的行数及完整行摘要均未变。57行导入日志有成功标记且无pull/build活动。该轮使用既有独立B来源环境升级后再导入R3，不冒称R3另建空库，见[R3导入证据](final-portable-import.json)。

历史R4包位于 `.local/releases/r-20261022T170200-3012051/portable/`，同样包含五个linux/arm64镜像、指定提交源码及校验和。[R4正式导入证据](v4-portable-import.json)确认解包到新的 `.local/portable-source-v4/` 后414份源码内容及完整权限匹配、无`.git`，旧源码目录仍保留；实际五镜像/三版本一致。导入复用既有 `.local/portable-install/` 状态，原7份秘密及三类业务完整行摘要保持，不冒称新建空库安装。目标未执行pull/build，但没有切断宿主网络，不能声称完成断网或远程AMD64验收。

当前V5五镜像包位于 `.local/releases/r-20261023T001633-e95b025/portable/`。单一运输归档为 `.local/releases/campus-study-room-v0.1.0-linux-arm64.tar.gz`，同目录附 `.sha256`，414259208字节，SHA256 `cc13877ab94d3394640a82508c42fc924acaa95a9b5226ebad3f71f2f84584f0`。其`v0.1.0`文件名只标识拟发布包；GitLab对应tag保护规则已配置，但实际tag和正式release仍未创建，完整CI/main门禁尚待通过。

大镜像归档、备份和日志保留在本机忽略目录，不提交数据库或秘密。当前ARM64包不能宣称同包适用于AMD64；跨架构应构建对应平台镜像。不同版本、不同阶段的证据分别保留，不将首轮A/B演练或导入命令成功代替尚未完成的最终验收。
