# 本机发布、回退与离线安装演练

对应 REQ-ENG-03/05/06/07。执行日期2026-10-22，ARM64 Docker Desktop；下列发布候选用于验证交付机制，不代表尚未闭环的全量P0验收已完成。

| 候选 | 完整源码提交 | 数据库变化 |
| --- | --- | --- |
| A `r-20261022T131500-6c9c47359404` | `6c9c47359404d9083ae67dad003ea7feb92d4544` | 含历史查询索引，不含新列 |
| B `r-20261022T132000-66f2b928550f` | `66f2b928550f50d89fe39ed23057beefd66f39cd` | 新增可空 `core.reservation.operator_note`；保留全部既有迁移 |

两个版本从干净的独立Git工作目录构建，清单绑定完整提交、三个镜像内容ID、Compose/依赖基线/全部迁移摘要。A首次部署创建隔离项目 `studyroom-compat-release`。用真实公共HTTP创建M1预约、经两名不同审批人批准的M2申请和M3报修，并保存两名用户的原会话到0700状态目录下0600私密夹具；夹具不进入仓库。

随后正常部署B、执行迁移、核对原会话和三类数据。向新列写入哨兵值后，维护/排空→旧A三个镜像→原会话读取并取消三类业务→核对新列及Flyway不变→恢复B，才产生绑定双方清单、镜像与数据库的兼容证明。

正式 `scripts/rollback` 使用该证明回到A；[实际回退](actual-rollback.json)再次核对三个运行镜像及版本、两名原会话、三类取消记录、新列和Flyway。当前应用清单见[current-release.json](current-release.json)；数据库仍是B迁移状态，独立记录于[schema-release.json](schema-release.json)。没有运行数据库降级。

[兼容证明](r-20261022T132000-66f2b928550f--r-20261022T131500-6c9c47359404.json)由真实演练工具生成，不是人工填写passed。工具在 `tests/integration/release_compatibility.py`，只允许明确标识的隔离项目。

## 备份与保留卷重建

正式备份入口生成`pg_dump -Fc`、0600文件、SHA-256并验证归档可读。恢复到新的独立数据库；11用户、1预约、1申请、1报修。三类订单及两侧审计表的排序完整行摘要均与在线库一致。

再以 `--force-recreate` 重建core、repair、web，确认三个容器ID均改变，原两会话仍可登录态读取同三条记录，业务与审计行摘要和新列数据完全不变。[机器可读证据](backup-and-recreate.json)。演练完成后正常停止该项目的三个应用以释放本机资源；数据库、Mailpit、数据卷、秘密、发布历史保留。

## 启动失败自动恢复

在该隔离项目重试部署B，监测到B core进入starting后只断开该容器的private网络，导致真实数据库启动失败。部署命令非零退出，运维入口使用已有B→A兼容证明恢复A三个镜像，并重新开放公开入口。121.9秒后核对原数据库库存、新列哨兵、已取消业务及运行版本全部保留，失败记录归档且pending/维护标记正确清除。见[故障证据](startup-recovery.json)与`tests/integration/release_startup_recovery.py`。

该演练只验证迁移成功后的应用启动故障；部分迁移失败时禁止自动回退的路径仍由专门门禁单测覆盖，未把它冒充已注入真实迁移故障。

## 离线包独立安装

B导出包括固定core/repair/web/PostgreSQL/Mailpit五个镜像、指定提交源码、清单、平台和逐文件SHA-256。导入器解包到不含`.git`的新源码目录，在新项目 `studyroom-portable-install`、独立秘密与数据卷上完成迁移和启动；入口 `http://localhost:19282`，Mailpit `http://localhost:19227`。

[离线导入证据](portable-install.json)核对完整包、源码摘要、五个实际运行镜像ID及全部秘密与来源环境不同。导入器未执行pull/build，目标无需Git/Java/Node。不过本次未切断宿主外网，不能将其称为隔绝网络的实测；两套环境使用同一个ARM64 Docker daemon，也不冒充另一台服务器或AMD64验收。完整浏览器业务链由另一agent独立执行，结果另行归档。

大镜像归档、备份和日志保留在本机忽略目录，不提交数据库或秘密。后续完整发布候选应包含新的契约、接口和浏览器验证，不将A/B的已通过运维证据冒充未执行的新版本业务结果。
