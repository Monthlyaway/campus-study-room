# 开发进度与验收索引

更新时间：2026-10-22。当前仍在执行P0，不以已通过的部分测试替代全量验收。

| 阶段 | 状态 | 实际证据 / 尚待完成 |
| --- | --- | --- |
| MS0 工程底座 | IN_PROGRESS | Java21/Node24/PG18运行；GitLab仓库已改名；`./install.sh test`完整Docker安装通过；GitLab pipeline已实际触发：原四任务682662通过；新增发布/E2E门禁仍在验证，main设置线性FF合并、默认保留commit |
| MS1 公共层 | IN_PROGRESS | 管理9项、共享7项真实PG；账号/范围/会话HTTP2项通过；真实SMTP失败/恢复、提醒送达与原记录重试2项通过；M1/M2完整停启跨期限组合待补 |
| MS2 M1 预约 | IN_PROGRESS | 20用户争抢及同用户跨室并发HTTP；签到边界/期限/来源锁PG；独立桌面与手机试用通过；日期历史筛选已修复并通过PG11/HTTP6及375px实操 |
| MS3 M2 审批 | IN_PROGRESS | 真实三账号两级审批、版本冲突及撤销通过；M1/M2竞争、同一步并发、审批/到期竞争及claim写后回滚专项通过 |
| MS4 M3 维修 | IN_PROGRESS | 单元10项/PG4项/HTTP2项；丢失已提交BLOCK响应后相同命令重试通过；同室五态级联PG专项通过，真实负面HTTP5项、三期限真实跨服务收件矩阵通过；BLOCK八次失败/RELEASE失败恢复、双侧审计和375px轮询修复通过 |
| MS5 验收恢复 | IN_PROGRESS | 独立恢复库验证备份；3项故意破坏均被测试捕获；容量性能首轮失败后复测达标（读p95 154ms/写253ms/5xx为0），重启积压12.09秒；新增可空列后应用回退、保留卷重建、隔离恢复逐行摘要核对及启动故障自动恢复已通过；每日备份实际launchd触发与截断负例通过 |
| MS6 交接发布 | IN_PROGRESS | 多次逻辑commit；两名代理实际试用并修复UI；A/B发布、五镜像离线包独立安装、真实浏览器6项通过；默认本地检查18 HTTP/65 PG/27收件人及响应丢失重放通过；GitLab完整门禁与最终发布继续验证 |
| MS7 P1 Agent | NOT_STARTED | P0优先，不启动可选P1 |

已部署入口：Docker测试环境`http://localhost:18082`、Mailpit`http://localhost:18027`；原生开发前端`http://localhost:8000`。两者独立数据库、会话和随机密钥。完整容器入口下管理HTTP2项、核心HTTP5项、维修HTTP2项全部通过，内部公开路径404。生产构建375px浏览器试用无横向溢出、无CSP错误。

用户选择本机与可迁移交付、使用本地邮件测试服务，见[ADR 0002](adr/0002-delivery-and-gitlab.md)。详细剩余项逐条记录于[39条P0需求追踪](requirements-traceability.md)，实现与证据分别见`docs/modules/`及`docs/evidence/`。正式服务器、域名与真实SMTP验收未执行，不计为已完成。

新隔离离线安装入口 `http://localhost:19282`，Mailpit `http://localhost:19227`；源码B=`66f2b92`，完整构建/迁移/回退/恢复证据见[发布演练](evidence/release/README.md)。新的契约、维修姓名和轮询修复尚待下一版本发布，不将旧镜像测试视为新代码已验证。
