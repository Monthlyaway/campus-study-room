# 本机发布、回退与离线安装演练

对应 REQ-ENG-03/05/06/07。执行日期2026-10-22至23（Asia/Shanghai），ARM64 Docker Desktop。当前R3本机入口为应用 `http://localhost:19282`、Mailpit `http://localhost:19227`，状态目录 `.local/portable-install/`，随机密码只从 `.local/portable-install/secrets/demo_password` 读取，不写入证据。源码安装默认18080；既有18082/8000实例不是R3。

**整体交付仍在收尾。** R3浏览器6/6、兼容演练、正式回退、完整性能及core/repair真实重启恢复均通过；GitLab最终流水线/main合并与发版归档仍在执行。R3离线导入及独立只读核对通过，具体包、版本和数据摘要见[最终导入证据](final-portable-import.json)。

**R4准备中，尚未构建或验收。** 为让离线源码包含CI runner兼容修正和验收工具修正，将构建新的完整发行包；业务代码与R3相同。当前19282入口、以下清单及成功证据仍对应R3，R4的构建与真实验收完成后另行记录，不倒改既有证据。

| 候选 | 完整源码提交 | 数据库变化 |
| --- | --- | --- |
| A `r-20261022T131500-6c9c47359404` | `6c9c47359404d9083ae67dad003ea7feb92d4544` | 含历史查询索引，不含新列 |
| B `r-20261022T132000-66f2b928550f` | `66f2b928550f50d89fe39ed23057beefd66f39cd` | 新增可空 `core.reservation.operator_note`；保留全部既有迁移 |
| R3 `r-20261022T161900-3c7a93c` | `3c7a93cae56e68d2a96adb1e022f4fffc8112c5e` | 保留既有迁移，并含可空 `repair.repair_order.assigned_to_name` |

A/B两个版本从干净的独立Git工作目录构建，清单绑定完整提交、三个镜像内容ID、Compose/依赖基线/全部迁移摘要。A首次部署创建隔离项目 `studyroom-compat-release`。用真实公共HTTP创建M1预约、经两名不同审批人批准的M2申请和M3报修，并保存两名用户的原会话到0700状态目录下0600私密夹具；夹具不进入仓库。

随后正常部署B、执行迁移、核对原会话和三类数据。向新列写入哨兵值后，维护/排空→旧A三个镜像→原会话读取并取消三类业务→核对新列及Flyway不变→恢复B，才产生绑定双方清单、镜像与数据库的兼容证明。

正式 `scripts/rollback` 使用该证明回到A；[实际回退](actual-rollback.json)再次核对三个运行镜像及版本、两名原会话、三类取消记录、新列和Flyway。首轮A/B演练的应用清单快照见[current-release.json](current-release.json)；该轮数据库仍是B迁移状态，快照记录于[schema-release.json](schema-release.json)。这些历史快照不代表当前R3运行身份。没有运行数据库降级。

[兼容证明](r-20261022T132000-66f2b928550f--r-20261022T131500-6c9c47359404.json)由真实演练工具生成，不是人工填写passed。工具在 `tests/integration/release_compatibility.py`，只允许明确标识的隔离项目。

## R3 发布版回退与前端验收

R3从干净独立工作目录构建，三应用运行身份均为完整提交 `3c7a93cae56e68d2a96adb1e022f4fffc8112c5e`。[R3→B兼容证明](final-compatibility.json)由真实演练工具产生，绑定两份清单SHA256、全部镜像内容ID及实际PostgreSQL/Flyway版本指纹，验证旧应用仍能读写升级后的数据库。该指纹不是完整DDL摘要，不声称检测所有人工结构改动。

随后通过正式回退入口恢复B三个应用，[实际回退证据](final-actual-rollback.json)再次核对原2个会话、3类取消后的业务记录，以及扩展列 `repair.repair_order.assigned_to_name` 和迁移历史保留；没有数据库降级。回退演练在独立compat项目进行，不把其运行版本与19282的R3混淆。

19282的R3完整真实浏览器 **6/6通过，119.255秒，无跳过/重试**，覆盖预约/再次预约/真实409、两级审批与拒绝、多角色报修、移动布局、加载/离线/错误恢复；真实报告隐私反证通过。两项界面故障的失败与修复证据，以及14b创建→R2实时签到→维修中断零违约的独立版本边界见[前端发布验收](../frontend-release.md)。没有在R3重复签到或声称该记录自然到期完成。

## 备份与保留卷重建

正式备份入口生成`pg_dump -Fc`、0600文件、SHA-256并验证归档可读。恢复到新的独立数据库；11用户、1预约、1申请、1报修。三类订单及两侧审计表的排序完整行摘要均与在线库一致。

再以 `--force-recreate` 重建core、repair、web，确认三个容器ID均改变，原两会话仍可登录态读取同三条记录，业务与审计行摘要和新列数据完全不变。[机器可读证据](backup-and-recreate.json)。演练完成后正常停止该项目的三个应用以释放本机资源；数据库、Mailpit、数据卷、秘密、发布历史保留。

## 启动失败自动恢复

在该隔离项目重试部署B，监测到B core进入starting后只断开该容器的private网络，导致真实数据库启动失败。部署命令非零退出，运维入口使用已有B→A兼容证明恢复A三个镜像，并重新开放公开入口。121.9秒后核对原数据库库存、新列哨兵、已取消业务及运行版本全部保留，失败记录归档且pending/维护标记正确清除。见[故障证据](startup-recovery.json)与`tests/integration/release_startup_recovery.py`。

该演练只验证迁移成功后的应用启动故障；部分迁移失败时禁止自动回退的路径仍由专门门禁单测覆盖，未把它冒充已注入真实迁移故障。

## 离线包独立安装

首轮B导出包括固定core/repair/web/PostgreSQL/Mailpit五个镜像、指定提交源码、清单、平台和逐文件SHA-256。导入器解包到不含`.git`的新源码目录，在新项目 `studyroom-portable-install`、独立秘密与数据卷上完成迁移和启动；入口 `http://localhost:19282`，Mailpit `http://localhost:19227`。

[离线导入证据](portable-install.json)核对完整包、源码摘要、五个实际运行镜像ID及全部秘密与来源环境不同。导入器未执行pull/build，目标无需Git/Java/Node。不过本次未切断宿主外网，不能将其称为隔绝网络的实测；两套环境使用同一个ARM64 Docker daemon，也不冒充另一台服务器或AMD64验收。该首轮B的浏览器结果与后续R3分别归档，不混用版本。

本机主目录 `.local/releases/` 现保留A、B、R3三份 `manifest.json`。R3已导出到 `.local/releases/r-20261022T161900-3c7a93c/portable/`，包含core、repair、web、PostgreSQL、Mailpit五个linux/arm64镜像、该提交的 `source.tar.gz`、清单及 `SHA256SUMS`。R3导入命令exit0；独立代理核对385份源码内容及执行位、五个实际镜像和三个版本一致，目标无`.git`，7份原密钥未变，预约17/申请14/维修9条的行数及完整行摘要均未变。57行导入日志有成功标记且无pull/build活动。本次使用既有独立B来源环境升级后再导入最新包，不冒称R3另建空库。

大镜像归档、备份和日志保留在本机忽略目录，不提交数据库或秘密。当前ARM64包不能宣称同包适用于AMD64；跨架构应构建对应平台镜像。不同版本、不同阶段的证据分别保留，不将首轮A/B演练或导入命令成功代替尚未完成的最终验收。
