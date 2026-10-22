# M3 负面矩阵与 D-06 真实 SMTP 验收

2026-10-22 执行。使用本机真实 core/repair HTTP、PostgreSQL 18.6 和 Mailpit；未修改生产业务代码，未用 capture 目录或模拟业务服务替代结果。该证据补充已有[维修生命周期及响应丢失测试](repair-validation-2026-10-22.md)，不替代后续正式部署邮箱验收。

## 实际结果

| 套件 | 结果 | 隔离与时间控制 |
| --- | --- | --- |
| `repair_acceptance_http.py` | **5/5 通过，309.835 秒** | 真实 8081/8082 API；独立合成账号、两院系、两教室；使用真实后台扫描周期 |
| `RepairDeadlineHttpTest` + `run_repair_deadlines.py` | **1/1 通过，12.923 秒**；随后 **27 项收件对象断言通过** | 独立 `studyroom_repair_deadlines` 数据库，测试专用 Clock；core 授权、资源与通知均真实 HTTP |
| `CoreSmtpAcceptanceTest` | **2/2 通过，19.119 秒** | 独立 `studyroom_core_smtp` 数据库，测试专用 Clock；实际 SMTP 421 拒收连接与真实 Mailpit SMTP |

随后增加IN_REPAIR直接评分断言并单独重跑第4组，**1/1通过，119.478秒**；完整矩阵与增量结果分开记录，不合并成未执行的整套回归。

机器可读结果：[HTTP](repair-acceptance/http-result.json)、[期限与收件对象](repair-acceptance/deadline-result.json)、[SMTP](repair-acceptance/smtp-result.json)。完整控制台日志留在忽略目录 `output/repair-acceptance/`，不提交运行凭证或邮件内容。

## 公共 HTTP 负面矩阵

| 需求 | 已实际验证 |
| --- | --- |
| M3-01 / D-04 | 空标题/说明、101 字标题、2001 字说明拒绝；100 个 Unicode emoji 标题及2000字说明通过；跨院系上报403；其他学生和范围外管理者详情404、列表不泄漏 |
| M3-02 | 非维修角色与停用维修员422；范围外调度员404；重派保留历史、首次派单时间及原48小时截止；原维修人404不能开始，新维修人可开始；开始后不能转派 |
| M3-03 / M3-08 | 未开始的取消不会创建封禁；终态不能派单/开始；报修人不能取消ASSIGNED；调度员取消须有原因；封禁、待验收时取消失败，工单版本、历史与资源claim不变 |
| M3-04 | 其他维修人404、可见工单的非执行物业403不能完工；空白、2001字完工说明422，2000字通过；仅报修人收到待验收通知，其他测试角色无该事件 |
| M3-05 | 空白/501字驳回理由、非法决定422；驳回仍下架；报修人兼维修人兼有范围物业管理员仍403不能自行通过或拒绝；另一合格物业管理员验收后才解除资源 |
| M3-06 | IN_REPAIR及待验收不能评分；0、6、小数1.5/4.0、布尔、字符串、501字说明422；非报修人403；同幂等键重放201且同评价，另一键再次评分409；封禁仍为已解除、工单仍CLOSED |
| D-06 | CANCELLED通知只有本人可标已读；他人404；刷新保留readAt；ASSIGNED仅当前派单事件对应维修人各一条，报修人/调度员/物业无该事件；CLOSED邮件经真实SMTP进入Mailpit，正文工单ID与收件地址匹配 |

首轮测试中的状态码与资源DTO假设不准确：资源历史使用 `releasedAt`，不是 `state`；服务按当前状态只查询适用操作权限，因此已关闭派单、封禁期管理取消等返回403，报修人已派单取消返回409。修正测试后保持精确状态码和“失败无副作用”断言；没有改变生产实现或放宽业务规则。失败首轮留下的独立夹具通过正常完工、独立验收流程清理，未删除封禁或直接改业务表。

## 三种期限跨服务验证

测试专用 Clock 分别推进到截止前1纳秒及精确截止。前者没有升级命令，后者重复扫描三次只生成一条NOTIFY；真实core端再次接收同一命令仍保持每收件对象一条记录。状态保持SUBMITTED、ASSIGNED、AWAITING_ACCEPTANCE；待验收超时的resourceVersion仍1，outbox只有既有BLOCK而没有RELEASE。用报修人的真实core登录会话查询公开M1可用座位接口，超时后仍为空；独立验收并收到RELEASE确认后再次查询恢复可用。

随后从core公开通知API逐一核对9个启用夹具账号，共3×9=27项收件对象计数：

| 事件 | 应收到的夹具角色 | 明确排除 |
| --- | --- | --- |
| ASSIGN_OVERDUE | 有本教室范围调度员、两位有本教室范围物业管理员 | 报修人、维修员、范围外调度员/物业、其他学生 |
| WORK_OVERDUE | 被派维修员、两位有本教室范围物业管理员 | 未派维修员、调度员、报修人、范围外管理者、其他学生 |
| ACCEPT_OVERDUE | 报修人、两位有本教室范围物业管理员 | 执行与未执行维修员、调度员、范围外管理者、其他学生 |

第二位有范围物业管理员同时有STUDENT/REPAIRER角色，用于验证多角色不产生重复收件记录。环境中原有ALL范围演示管理员也可能按业务规则收到通知；27项断言针对本次新建的九个角色账号，不把它们描述成整个环境的唯一收件人。

测试还在真实BLOCK_PENDING和RELEASE_PENDING阶段尝试取消并断言拒绝。最终通过正常取消或独立物业验收清理三张工单，core资源历史中的唯一M3来源已解除。测试入口不直接向core业务表写数据。

## 真实 SMTP 失败与恢复

测试创建正常预约与PRE_START任务。预约、claim、通知均由生产服务代码生成。Spring自动扫描保持暂停；测试手动调用生产 `Jobs.sendMail()`，其claim/send/delivered事务和真实网络代码不变，只有Clock与SMTP不可用对端为测试夹具。

1. 实际TCP SMTP对端返回421临时不可用，连续8次投递失败。每次断言原通知ID不变、尝试数递增、错误码持久化、租约释放，预约仍RESERVED且claim仍有效。
2. 前七次指数退避；第8次后为300秒。推进299秒不发送，到300秒执行第9次失败，下一次仍300秒；管理列表可见attempts=8及失败状态。
3. 本人将原记录标已读。管理员立即重试只更改原记录的nextAttemptAt，不重置attempts、不创建新通知。
4. SMTP目标恢复为Mailpit，实际第10次成功。通过Mailpit HTTP API按唯一合成收件人查到邮件，正文预约ID一致；原记录SENT、sentAt非空、lastErrorCode清除，readAt保持，预约不受失败影响。
5. 另一独立预约的M1_PRE_START、M1_LATE、NO_SHOW三种提醒均实际进入Mailpit；重复任务扫描后每种事件/收件人只有一条通知，NO_SHOW只记一次违约。

这里证明本地SMTP实际收取和恢复，不宣称外部邮件严格只投递一次；SMTP确认丢失仍可能产生外部重复邮件。生产专用邮箱收取仍按部署环境另行验收。

## 复现

先启动本机依赖与原生core/repair并完成迁移、reference和demo种子。脚本从忽略目录读取现有secret；不需要把密码写到命令或版本库。

```sh
python3 tests/integration/run_repair_acceptance.py
python3 tests/integration/run_repair_deadlines.py
python3 tests/integration/run_smtp_acceptance.py
```

公共API套件也可直接运行 `repair_acceptance_http.py`，通过 `STUDYROOM_TEST_PASSWORD`、`STUDYROOM_CORE_URL`/`STUDYROOM_REPAIR_URL`（或统一 `STUDYROOM_BASE_URL`）、`STUDYROOM_MAILPIT_URL` 指向对应测试部署。SMTP测试支持 `STUDYROOM_SMTP_HOST`、`STUDYROOM_SMTP_PORT` 和 `STUDYROOM_MAILPIT_URL`。两个JVM测试必须由显式runner启用条件标志；普通Maven跳过数据库测试不能算此次验收。

## CI 接入与证据门禁

`scripts/ci-integration.py` 使用GitLab的Mailpit服务（SMTP1025/API8025），核心服务显式 `MAIL_MODE=smtp`。新增公共HTTP套件沿用同一环境锁；SMTP/期限套件各自创建迁移后的数据库，并暂停自动调度。期限夹具准备和后置收件对象断言复用 `run_repair_deadlines.prepare_fixture/assert_recipients`，CI不调用本机Docker或读取`.local`。三个本机runner复用`deployment.lock`，与同环境发布/回退互斥。

Python套件必须报告非零测试且无skip；JUnit必须有本轮新生成的精确报告，tests>0且skip/error/failure全零。CI报告门禁的三个本地单测通过，覆盖成功、零测试、跳过、失败和缺失摘要。真实GitLab流水线尚由发布任务执行，不能把本地入口验证写成已跑过CI。
