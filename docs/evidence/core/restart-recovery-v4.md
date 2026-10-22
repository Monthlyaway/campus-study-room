# R4 Core真实重启后的期限补偿验收

2026-10-23（Asia/Shanghai），T50-03、REQ-M1-05、REQ-M2-03、REQ-D-06。**完整执行通过**：同一core容器正常停止/启动，首次业务提交可见12.860秒、全部10条原任务完成14.057秒；随后规定65秒稳定观察通过，原业务数据保持不变。

## 发布身份与范围

R4为`r-20261022T170200-3012051`，完整提交`3012051f06c601e6c22a03e50d9d307a3054d1d9`。core实际镜像`sha256:6fe4c49586b83adb33fbc5a456c71e21e66d6ce5bb6d1cea462e907de782488d`。三镜像ID以及重启后web/core/repair三个版本API都与R4 manifest一致；运行脚本SHA-256与发布源码相同，见[机器证据](restart-recovery-v4.json)与[发布绑定](../performance/20261022T170955Z-release-binding.json)。

只操作独立`studyroom-performance`及其数据库/状态目录，未改local/test/portable环境。五个常驻容器共同限制在CPU 0–3，总内存上限8GiB；Docker宿主共享，不能等同独占物理服务器。全程持有performance与deployment锁。在R4完整60秒预热+300秒负载、repair真实恢复及SQL检查全部完成后，才执行本项。

```sh
python3 tests/integration/core_restart_recovery.py run \
  --release-manifest .local/releases/r-20261022T170200-3012051/manifest.json
```

## 方法与实际结果

执行`stop -t 60 core`，JVM正常SIGTERM退出143、OOMKilled=false；停机后仅INSERT新UUID的合成历史订单和10条原始PENDING任务，标注`SYNTHETIC CORE RESTART`。初始审计、通知、违约均0，没有预写成功结果；再执行`start core`，容器ID不变，由真实生产scheduler自行处理，没有修改时钟或调用任务处理器。

运行ID：`d7d55f26-583a-4a0c-8a59-0f8d76f5a903`，重启请求时间`2026-10-22T17:17:00.915138+00:00`。readiness首次观测10.422秒，业务首次12.860秒、全部14.057秒，均从启动请求开始计时，比健康后60秒更严格。

| 合成初态 | 实际终态 | 任务及副作用 |
| --- | --- | --- |
| M1 RESERVED，version0 | NO_SHOW，version1 | 4条任务DONE；单违约、单NO_SHOW通知，释放自身claim |
| M1 CHECKED_IN，version1 | COMPLETED，version2 | 4条任务DONE；endedAt为原预约endAt，无违约/未签到提醒，释放自身claim |
| M2 PENDING，version0 | EXPIRED，version1 | 2条任务DONE；未创建claim，无审批通过通知 |

10条任务attempts均0，无残留lease/error；过时PRE_START/LATE通知为0，实际为3条JOB终态转换及2条资源释放审计。两次快照间隔65.394秒，含最终摘要/健康核对的观察共66.800秒，订单版本、任务、claim、通知/违约身份与业务内容、审计未重复或变化。SMTP投递状态可以独立推进，该断言不把邮件lease/status变化误当新业务通知，也不替代收信验收。

重启前原有130862条预约、2条申请、130862条claim的完整行摘要，在排除本轮新增UUID后逐项不变。保留此前两轮core夹具；本轮仅新增2预约/1申请/2claim和10任务，COMPLETED历史100002→100003，未删减数据以凑整数。账号/教室/座位仍1000/20/1000；原core保持运行且readiness正常。

## 归档与边界

原始8文件保留于`output/performance/core-recovery-20261022T171658Z-d7d55f26/`；包括原始PENDING快照、真实终态与65秒后快照、两份原业务摘要、夹具JSON/SQL及运行结果。安全执行日志为`output/core-recovery-v4-run.log`。每个文件字节数和SHA-256见[机器证据](restart-recovery-v4.json)。连同性能与执行日志的24文件已按实际私有值及编码变体扫描，0命中；证据不含密码、Cookie或签到码。

本项覆盖这10条明确积压，不外推任意积压规模，也不冒称自然UI时间流逝。R3首次采集失败与第二次通过均保留原记录，未覆盖或改标R4。R4的浏览器、发布回退和远端GitLab结论分别由对应证据证明。
