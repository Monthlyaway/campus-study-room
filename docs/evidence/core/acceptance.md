# Core 验收证据

2026-10-22，本地 Java 21 + Spring Boot 4.1.1 + PostgreSQL 18.6。

## 已实际执行

- `./mvnw -pl services/core-service,tools/migration-runner -am package -DskipTests`：通过；两个共享模块、core、独立迁移程序可打包。
- `./mvnw -pl services/core-service -am test`：7个 DomainPolicyTest 用例通过，覆盖时间偏移量、整点与14天限制、签到左右边界、版本和终态、文本与幂等规范化。
- `python3 tests/integration/core_http.py`：5个真实HTTP验收用例通过，运行15.760秒。使用真实PG、Spring Security会话和数据库资源锁，没有mock资源裁决。

| 验收 | 观察到的结果 |
| --- | --- |
| TC-D-01 / TC-D-03 | 学生读取管理API403；跨院系详情404；账号禁用后旧会话401 |
| TC-M1-02 / TC-X-01 | 20个不同学生线程通过屏障同时预约；1个201，19个409；只存在1个有效claim |
| TC-M1-02 跨房间 | 同一个学生2个会话同时预约2间教室重叠时段；1个201、1个409 |
| 幂等与隐私 | 相同键同body重放相同data；同键改body409；他人订单详情404 |
| TC-M2-02 | 第二级抢先审批403；第一级通过后遇M1占用，最终审批409且状态仍PENDING、仅1条决定；释放冲突后批准成功 |

## 调试中发现并修复

1. PostgreSQL advisory lock函数返回void，直接按Long读取失败。改为`select 1 from pg_advisory_xact_lock(...)`，保持同事务锁。
2. 共享record principal默认toString超过Session principalName列长度。实现标准`Principal.getName()`返回UUID，登录与共享Session正常。
3. 在代理对象外访问注入EntityManager字段为空。改为Repository方法封装访问，真实预约与主数据操作验证通过。
4. HTTP验收之后增加requestId贯通、来源房间校验、数据库分页与统计聚合；这些变更会在最终整套回归再次执行，未将尚未运行的回归记为已通过。

原始控制台输出保存在任务执行记录；持续集成通过`tests/integration/core_http.py`重放。测试没有故意破坏后的反证证据，不能用当前绿灯代替该门禁。
