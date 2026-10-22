# 可复现性能验收

覆盖 PRD §12、技术方案 §10.3 和 T50-03。必须使用真实 Docker 服务与 PostgreSQL，程序固定 20 个并发会话、60 秒预热和 300 秒统计窗口，不能用缩短版结果替代验收。

## 准备和运行

在仓库根目录运行，需要 Python 3.10+、Docker Compose v2、已构建的 core/repair/web 镜像。开发诊断默认使用 `studyroom-{core,repair,web}:development`；正式发布验收传入经过验证的完整 release manifest。

```sh
python3 tests/performance/prepare.py
# 正式发版首次准备时改用：
# python3 tests/performance/prepare.py --release-manifest .local/releases/<release>/manifest.json
python3 tests/performance/run.py
python3 tests/performance/inspect_queries.py
```

修复代码并重建镜像后，保留已有基准数据再次测量：

```sh
python3 tests/performance/refresh.py
# 正式 release 可用 refresh.py --release-manifest .local/releases/<release>/manifest.json
python3 tests/performance/run.py
python3 tests/performance/inspect_queries.py
```

`refresh.py` 只停止这个独立项目的应用容器，应用追加迁移后重新启动；不清空数据库。每次测量另建 UTC 结果目录，记录本轮开始数据量。所有准备、测量、SQL 检查和镜像刷新共享独占运行锁，防止诊断动作污染正在进行的测量窗口。

准备程序只使用项目 `studyroom-performance`、状态目录 `.local/performance`、入口 `http://127.0.0.1:19080`，不会连接或修改 local/test 的数据库。已有性能项目容器时拒绝重复准备；已有业务数据时 seed 拒绝覆盖。若要重新初始化，必须明确清理这个独立项目及其专用卷，保留需要的结果，再重新运行准备；脚本不自动删除数据库。

准备阶段冻结实际镜像 ID。全部常驻服务共享 CPU 0–3；PostgreSQL 3 GiB、core 2 GiB、repair 2 GiB、web 512 MiB、mailpit 512 MiB，总额 8 GiB，swap 上限与内存相等。迁移容器在负载开始前退出。检查 Docker 宿主至少存在 CPU 0–3；共享 Docker VM 的其他负载不能视作独占硬件，应暂停构建并在报告中说明。

## 数据与负载

`seed.sql` 在 reference-only 空库中创建 1000 个真实用户、20 间教室、1000 个座位和 100000 条 COMPLETED 历史预约；同时创建相同数量的已释放资源占用、历史审计/通知和 200000 条已完成任务。每个用户分配 100 条历史预约，时间覆盖 100 天，预约之间无重叠。凭据取自隔离状态目录内生成的 secret，不进入源码、请求日志或报告。

20 个工作线程各用真实登录、共享 Session/CSRF 和独立 HTTP keep-alive 连接，循环执行 4 次读、1 次写：可用座位分页、个人预约历史分页、教室详情、个人报修历史，然后交替创建和取消预约。每位用户使用不同教室中的座位，反映正常写入；不加思考时间。登录、SQL 检查、争抢预检和最后清理不计入性能窗口。按请求开始时刻划分预热/统计窗口，截止时在途请求完成后计入延迟；窗口边界可能带来极小的 80:20 比例偏差。

另行用 20 人同时争抢同座同时间，要求 1 次 201、19 次 409 和恰好 1 条有效资源占用。负载窗口中注入 20 个真实持久化维修期限任务，测量到期到审计可见时间；窗口后停启 repair 并注入 20 个已过期任务，记录重启到开始/全部处理的耗时。没有调整服务器时钟或手动调用业务补偿方法。

## 证据与判定

结果保存在 `output/performance/<UTC run ID>/`，目录被 Git 忽略，报告在 `docs/evidence/performance.md`。保留 JSONL 每次请求、分类分位数/状态、每秒数据库锁等待/积压、任务完成记录、SQL 次数/计划、真实容器限制和镜像 ID。`run.py` 未满足读 p95 ≤ 1000 ms、正常写 p95 ≤ 2000 ms、5xx < 1%、无传输/非预期 4xx、健康期限 ≤ 60 秒或重启开始补偿 ≤ 60 秒时退出 2。409 独立统计，不能掩盖为成功或 5xx。

`inspect_queries.py` 必须在负载窗口结束后运行；临时暂停独立项目的后台扫描器，以 `pg_stat_statements` 比较页大小 1/20 的 SQL 次数，并执行真实 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`。SQL 次数包括登录态读取/更新与事务语句；应查看逐条语句确认是否随每项数据增长。每轮采样后等待 1 秒，另加 Docker/SQL 开销，无法证明从未发生更短的锁等待；数据库 `log_lock_waits` 另记录超过 100 ms 的等待。

内部认证瓶颈可独立对照 `python3 tests/performance/auth_probe.py before` / `after`，比较 20 并发公共版本、有效机器凭据、错误用户名/密码、空/缺失凭据；脚本将 secret 通过 stdin 交给容器内 curl，结果只保留状态码与耗时，不记请求头。不要在主负载窗口同时运行。
