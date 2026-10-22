# M3 时间精度：Linux CI 失败反证与修复

2026-10-23（Asia/Shanghai），关联 REQ-M3-02 / M3-04 / M3-07、T40-02。

GitLab pipeline `682941` / job `4478341` 的真实 HTTP 重派验收发现：首次响应与重派后的 `firstAssignedAt` 不相等。原测试的 `firstAssignedAt`、`workDueAt` 严格相等断言保持不变。

## 根因与真实反证

`RepairService` 原先直接使用 `Clock.instant()`；`saveAndFlush` 后首响应仍引用内存实体的纳秒值。PostgreSQL `timestamptz` 持久化为微秒，下一次事务读取时已经舍入。

在独立的 `studyroom_repair_deadlines` PostgreSQL 数据库及真实 core 授权 HTTP 上注入固定纳秒时钟，复现出：

| 读取位置 | `firstAssignedAt` |
| --- | --- |
| 首次派单响应 | `2026-10-22T16:15:21.123456789Z` |
| 重派后从 PostgreSQL 读取 | `2026-10-22T16:15:21.123457Z` |

修复前 `RepairDeadlineHttpTest` 共 2 项：1 项失败、0 error、0 skip；既有期限验收通过，新增重派精度断言失败。不是通过放宽比较精度制造通过。

## 修复边界

`RepairService.now()` 在事件生成时截取到微秒，实体、期限、审计、历史、outbox payload 和幂等响应由同一个值构造。创建、业务动作、幂等记录及 `RepairWorker` 的领取、确认、重试、期限处理统一使用此入口。原始注入 `Clock` 保留，鉴权逻辑与数据库 schema 均未改变。既有数据和旧幂等响应不重写。

## 正向验收

- `python3 tests/integration/run_repair_deadlines.py`：2/2 通过（JUnit 7.063 秒，Maven 11.815 秒）；另有 3 事件 × 9 角色的 27 项真实 core HTTP 接收人断言通过。
- `.123456789` 固定时钟：首次派单与重派时间、首响应与新事务详情、原始幂等响应严格相等；提交时间保持不变。
- `.999999789` 固定时钟覆盖原数据库会向下一秒舍入的边界：创建、派单、完工、验收、评分响应中的时间及历史与真实数据库重读一致；BLOCK 命令 `occurredAt` 与存储事件时间一致；worker 关闭时间精确匹配统一微秒值。
- 期限前 1 纳秒不触发、恰好到期只触发一次的原断言继续通过。
- `python3 tests/integration/run_repair_persistence.py`：4/4 通过（JUnit 3.537 秒，Maven 8.596 秒），覆盖持久期限、返修期限、8 线程并发去重和事务回退。

机器可读结果与源码摘要见 [repair-timestamp-precision.json](repair-timestamp-precision.json)。原始失败、成功日志和 JUnit XML 留在忽略目录 `output/repair-timestamp/`。本次未重启运行服务或构建 Docker；修复后的 Linux GitLab CI 及 R3 镜像验证由后续发布流程执行，此处不声明已通过。
