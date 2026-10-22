# 维修资源故障、审计关联与日志扫描

本轮使用独立原生 core18181、repair18182、透明代理18180、静态前端18183；数据库为本机 PostgreSQL 中的 `studyroom_fault_matrix`。运行、控制、日志和前端副本都位于忽略目录 `.local/fault-matrix`，没有重启或修改 local/compatibility/portable 的业务进程、数据库和入口。

## 实际结果（2026-10-22）

| 检查 | 实际结果 |
| --- | --- |
| 资源BLOCK路径断开 | 同一命令8次失败，工单`BLOCK_PENDING`，`needsAttention=true`，第8次后300秒退避，core无该来源claim；原命令编号、requestId与payload摘要不变 |
| BLOCK恢复 | 无额外人工重试；恢复网络后**268.411秒**在持久化计划时刻自动`IN_REPAIR`；公共API可用座位**3→0** |
| 资源RELEASE路径断开 | 真实完工与独立物业验收后`RELEASE_PENDING`，命令失败2次；core来源仍ACTIVE，原claim未释放，公共可用座位为0 |
| RELEASE恢复 | 原命令自动确认，恢复网络后**4.342秒**进入`CLOSED`；公共可用座位**0→3** |
| 双侧审计和日志 | BLOCK和RELEASE各自的原用户操作审计、repair确认审计、core资源审计、outbox和两服务结构化日志使用同一requestId；两条操作分别有独立requestId |
| 迟到BLOCK | 原commandId重放返回原ACTIVE响应，但实际来源仍RELEASED/v2；另一个commandId携带旧version1返回`STALE_IGNORED`/RELEASED/v2；唯一claim保持已释放，三个processed_command记录均存在 |
| 整个core不可达 | 正常停止独立core后调用开始维修，返回**503 AUTHORIZATION_UNAVAILABLE**；ASSIGNED、version1、resourceVersion0、historyCount2、auditCount2逐项不变；BLOCK命令数0。随后正常重启core并通过调度取消清理该夹具 |
| 日志秘密扫描 | 对5份日志/代理事件文件当时的**989935字节**扫描34个实际已知值（运行秘密、捕获的API会话Cookie、完整授权每日码与Basic编码凭证）；精确命中0。针值与原始日志均未提交 |

机器可读证据：[总结果](repair-fault-matrix/result.json)、[BLOCK等待](repair-fault-matrix/block-pending.json)/[恢复](repair-fault-matrix/block-recovered.json)、[RELEASE等待](repair-fault-matrix/release-pending.json)/[恢复](repair-fault-matrix/release-recovered.json)、[审计关联](repair-fault-matrix/audit-correlation.json)、[迟到命令](repair-fault-matrix/late-block.json)、[授权故障](repair-fault-matrix/authorization-outage.json)、[日志扫描](repair-fault-matrix/log-scan.json)、[网络事件](repair-fault-matrix/proxy-events.json)。全部独立进程已正常停止，数据库和证据保留；没有删除业务记录或数据卷。

另一名前端agent在同一真实工单执行桌面/375px操作，发现并修复60秒轮询结束后重试没有重新启动轮询，以及未展示关注标记/下次计划。修复后19秒新增10次真实详情GET；8次失败时手机展示关注提示与下次计划，解除同步中不能重复完工/验收，最终关闭无截止和同步按钮，两个会话控制台0错误/0警告。详见[独立前端复验](frontend-sync.md)、[请求计数](repair-fault-matrix/ui-polling.json)；修复预览的静态文件摘要分别保留在[轮询修复](repair-fault-matrix/ui-fixed-runtime.json)及[关注提示](repair-fault-matrix/ui-attention-runtime.json)，不冒充初始运行包或最终发布版。

## 验证边界

资源写入不可达与整个 core 不可达是两个不同场景：

- 代理只切断 `/internal/v1/resource-commands`，在请求发送给真实 core **之前**关闭连接；授权、房间、通知等请求继续真实转发。此时已获授权的开始维修进入 `BLOCK_PENDING`，独立验收进入 `RELEASE_PENDING`，由持久化命令等待恢复。
- 整个独立 core 停止时，维修服务无法确认当前账号和权限。开始维修必须返回 `503 AUTHORIZATION_UNAVAILABLE`，保持 `ASSIGNED`，不生成 BLOCK 命令。这种情形不能为了展示同步状态而跳过授权。

恢复使用生产默认的30秒扫描、真实退避和数据库租约，不改 Clock，不通过SQL改工单或命令状态。测试仅通过公共API创建夹具与执行业务；管理员SQL限于隔离库初始化和只读证据查询。此套件不验证邮件，隔离环境的邮件模式为capture；真实SMTP证据见[独立邮件矩阵](repair-acceptance.md)。

## 运行版本与限制

两服务从 `.local/local/run/{core,repair}.jar` 稳定副本复制，没有使用并行编译中的target应用包。迁移程序与已构建前端分别复制到本套件自己的目录。`runtime.json` 记录每个应用包、迁移包及全部前端静态文件的 SHA-256。记录的 checkout HEAD 只是复制时刻的仓库位置，不能推断应用包来自该提交；本轮不能替代最终 typed DTO 或最终发布组合的回归。

## 首轮夹具问题

首次独立浏览器看到短暂 `BLOCK_PENDING` 后立即进入维修。代理没有资源请求记录。原因是Python公共HTTP测试使用的 `STUDYROOM_CORE_URL=18181` 被复制进Java环境，Spring宽松环境绑定将其识别为 `studyroom.core-url`，优先于配置文件引用的 `CORE_INTERNAL_URL=18180`。请求实际仍只在隔离 core18181 中执行，没有进入local数据库。

修复仅在runner向Java构造环境时去除公共HTTP测试URL变量。正常停止并重启隔离repair，首张工单通过真实完工与独立验收关闭；保留首轮记录，然后为重测新建工单。没有修改生产授权、状态机或资源逻辑，也没有把首轮算作断网测试通过。

## 可复现入口

需要已有本机PostgreSQL、忽略目录中的随机secret、稳定原生运行包、迁移包和前端dist。新环境中执行：

```sh
python3 tests/integration/run_fault_matrix.py all
python3 tests/integration/run_fault_matrix.py stop
```

脚本只绑定loopback，拒绝占用中的端口和重复fixture；不会删库、数据卷或旧证据。使用 `.local/fault-matrix/environment.lock` 串行运行自己的阶段。已存在fixture时使用明确阶段，不再执行setup。

独立浏览器验收按 `setup → prepare → 浏览器开始维修 → recover-block → 浏览器完工/独立验收 → recover-release → verify → stop` 执行。`prepare`返回工单与账号名，密码始终从既有secret文件读取；浏览器不能记录凭证。`recover-block/recover-release`先确认失败尝试落库，再恢复网络并等待自动收敛。`verify`读取两侧公开审计、重放旧BLOCK、验证永久命令记录，并停止/恢复独立core以检查授权故障。原始日志和扫描needle文件只保留在忽略目录，不提交Cookie、完整签到码或secret。
