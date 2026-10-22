# Core 真实重启后的期限补偿验收

2026-10-23（Asia/Shanghai），T50-03、REQ-M1-05、REQ-M2-03、REQ-D-06。**第二次完整执行通过**：重启请求后 11.183 秒观察到全部 10 条积压任务已提交完成，随后至少 65 秒、两个扫描周期内没有重复业务结果。第一次因测试采集器解析错误而失败，记录保留，未改写为成功。

## 最终版本与隔离范围

运行最终 release `r-20261022T161900-3c7a93c`，生产提交 `3c7a93cae56e68d2a96adb1e022f4fffc8112c5e`。core 镜像为 `sha256:78dd0fff8c833b2bb2b154f9689314f715e8409e7c551eb14a5e949e65ec5807`；完整三镜像身份、manifest 摘要与实际容器限额见[机器证据](restart-recovery.json)。

只操作 `studyroom-performance` 项目及其独立数据库、`.local/performance` 状态目录。五个服务共用 CPU 0–3，内存上限合计 8 GiB，swap 上限等于各自内存上限；Docker LinuxKit/aarch64 与宿主仍共享，不能等同独占服务器。运行全程持有 performance/deployment 两把锁。最终 60 秒预热＋300 秒性能测量 `20261022T162929Z` 及查询检查已完成后才进行停启，没有将恢复夹具加入原性能窗口。

## 方法与首次失败

脚本正常 `stop -t 60 core`，JVM 收到 SIGTERM 后退出码 143、未发生 OOM；随后在 core 停止时，仅 INSERT 新 UUID 的合成历史记录及原始 PENDING 任务。夹具明确标注 `SYNTHETIC CORE RESTART`，没有预写成功审计、违约或通知。用 `start core` 启动同一个真实容器，由生产 scheduler 自行扫描；未调用任务处理器、修改时钟或经 UI 自然等待这些历史时点。

第一次运行 `0b832ad5-00c6-4a1e-8cfa-4cc5645b7341` 已提交 3 条夹具后，采集器误取 PostgreSQL 多行 `json_agg` 输出的最后一行，抛出 `JSONDecodeError`。`finally` 正常启动原 core；只读检查确认原 10 任务已收敛，但该次缺少完整计时和 65 秒观察，原 `result.json` 的 `passed:false` 保持不变。

修复仅把采集器改为解析完整 JSON 输出，新增真实格式的多行聚合单元回归，9/9 安全测试通过。经协调批准后执行第二次完整命令；第一次的所有记录和卷保留，并纳入第二次“原业务数据不变”的摘要比较。生产代码和镜像均未变化。

```sh
python3 tests/integration/core_restart_recovery.py run \
  --release-manifest .local/releases/r-20261022T161900-3c7a93c/manifest.json
```

## 第二次完整运行结果

运行 ID：`22ec28db-00fd-4db1-a2cb-bd262aa4371d`。重启请求时间 `2026-10-22T16:38:28.440721Z`；readiness 首次观察 7.511 秒，业务首次及全部提交可见分别为 11.183014 秒和 11.183093 秒，均在 60 秒门槛内。该计时从发出启动请求开始，比“恢复健康后 60 秒”更严格。

| 合成初始状态 | 实际终态 | 任务与副作用 |
| --- | --- | --- |
| M1 RESERVED，version 0 | NO_SHOW，version 1 | 4 条原任务 DONE；仅 1 条违约、1 条 NO_SHOW 通知；释放自身 claim |
| M1 CHECKED_IN，version 1 | COMPLETED，version 2 | 4 条原任务 DONE；`endedAt` 等于预约结束时间；无违约、无未签到提醒；释放自身 claim |
| M2 PENDING，version 0 | EXPIRED，version 1 | 2 条原任务 DONE；没有创建 claim，也没有产生审批通过通知 |

10 条任务全部 `DONE`、attempts 为 0、无残留 lease/error。过时 `M1_PRE_START` / `M1_LATE` 通知为 0。实际审计恰好为 3 条 JOB 业务转换和 2 条资源释放，无额外转换或版本增长。

两次数据库快照相隔至少 65 秒，订单、原任务、claim、违约、通知身份与业务内容、审计完全一致；包含最终摘要与健康检查的稳定观察共 66.681 秒。邮件发送状态可独立推进，去重检查不把 SMTP lease/status 变化误判为新业务通知；本项不代替真实收信验收。

原有 123059 条预约、1 条申请、123059 条 claim 的整行摘要在排除本次新增 UUID 后完全一致，包含第一次失败采集留下的记录。账号/教室/座位仍为 1000/20/1000。两次合成验收各增加 1 条 COMPLETED，因此最终历史完成数是 100002；原完整性能测量仍使用 100000 条历史，未删数据或重新缩小规模。原 core 容器保持运行且 readiness 正常，所有夹具及数据卷保留。

## 证据版本与复核

第二次运行时工作区 HEAD 为 `9b940a1fe1a79cb095cd1f566a871fb013d1952c`，采集器解析修复当时尚未提交。**该 HEAD 只是运行上下文，不代表修改后脚本的精确提交**；实际执行文件由 SHA-256 `1feaae99f566d5fc895e2801772570568dc17ae0fc3c0c99f899cc2b1bb2b23e` 绑定。生产镜像则严格绑定上述 `3c7a93c` release。

安全摘要、任务类型与审计身份、前后业务摘要、原始文件字节数及 SHA-256 见 [restart-recovery.json](restart-recovery.json)。全部原始 JSON 与夹具 SQL 分别保留在忽略目录 `output/performance/core-recovery-20261022T163632Z-0b832ad5/` 和 `output/performance/core-recovery-20261022T163826Z-22ec28db/`。本次结果覆盖这 10 条明确积压任务，不外推任意规模积压，也不宣称 UI 自然时间流逝验收。
