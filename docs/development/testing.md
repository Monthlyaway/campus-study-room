# 选择和运行测试

先根据改动风险选择测试，再看它连接哪个环境、会写什么。除另行说明，命令都从仓库根执行。命令在 codex-lb 执行；依赖准备见[远程开发环境](local-setup.md)，秘密来源见[演示账号](demo-accounts.md)。本页给出复现入口；历史通过记录在[证据索引](../evidence/README.md)，不能当成你当前修改后的运行结果。

## 先选入口

| 目的 | 命令/入口 | 前置与目标 | 副作用、结果和边界 |
| --- | --- | --- | --- |
| 快速检查 Java 策略/格式化器 | `./mvnw -B -ntp -pl services/core-service -am test`；repair 或 `modules/security-common` 可替换模块 | Java 21、Maven wrapper；无需正在运行的应用 | 编译并写各模块 `target/surefire-reports`。条件 PG suite 默认跳过，不能据此声称数据库验证通过 |
| 提交前快速全仓检查 | `./scripts/check --unit-only` | Java、Node/pnpm、Python、Docker Compose CLI、已安装前端依赖 | Java verify、客户端生成差异、类型/前端测试/构建、Compose 与运维静态/单元检查；可能初始化缺失的 local 配置。明确跳过真实 PG/HTTP/SMTP 集成 |
| 默认完整门禁 | `./scripts/check` | 上述依赖及可用 Docker daemon；前端先 `./scripts/web install --frozen-lockfile` | 建立专用 PostgreSQL/Mailpit、网络、随机端口、秘密与服务进程，执行真实 PG/HTTP/SMTP/契约及故障恢复。使用 `.local/check/<run>` 与 `output/check/<run>`，不重启既有 local 或交付服务 |
| 定点验证数据库/事务 | 下表的 `run_*` runner | local Compose PostgreSQL、配置/秘密和已构建的迁移 JAR；最直接的准备是 `./scripts/dev-up local` | 在该 PostgreSQL 内创建/复用指定测试数据库，写种子和测试数据；不是零前置，也不是临时容器。报告在模块 `target/surefire-reports` 与终端 |
| 公共 HTTP 业务验收 | 本页“公共 HTTP” | 自己的已迁移、已 demo seed、正在运行的 core/repair；部分需 Mailpit | 在目标业务库创建账号、空间、订单和通知；业务收尾不等于清空测试数据 |
| 浏览器可用性与响应式 | [浏览器 README](../../tests/browser/README.md) | 按该页安装浏览器依赖、选择专用环境并注入密码文件 | 会实际点击、写业务并保存截图/报告；与后端集成门禁分开 |
| 规模/SQL/性能 | [性能 README](../../tests/performance/README.md) | 专用容器环境、资源限制和数据准备 | 大量造数与并发负载；不能在共享演示实例运行，普通门禁不代替它 |
| 安装、迁移兼容、回退 | [运维手册](../operations/runbook.md) | 发行 manifest、独立环境、备份和对应版本 | 会操作容器/数据库/发布状态；按运维流程执行，不属于普通 API 单测 |

`./scripts/check` 的隔离 harness 会拒绝缺失、零测试或跳过的必需集成 suite。结束时清理自己启动的进程、容器与网络，**保留命名数据卷**，不会自动删除数据卷。结果看 `output/check/<run>/result.json` 和对应 suite 的 `.txt`/`.xml`；失败摘要只显示白名单字段，敏感原始报告可能移入私有状态目录。不要将 `.local`、原始测试响应或秘密粘贴到公开报告。

## 真实 PostgreSQL 定点测试

以下 runner 自动设置所需条件开关、数据库 URL 和秘密，不要只设置一个 `STUDYROOM_*_DB_TESTS=true` 就指向现用数据库。它们使用本工作区 local Compose PostgreSQL；测试库默认保留，方便排错。运行期间会获取本地环境/工作区锁，等待锁不等于程序失去响应。

| 从根执行的命令 | 测试类 | 数据库与覆盖 |
| --- | --- | --- |
| `python3 tests/integration/run_core_persistence.py` | CorePersistenceTest | `studyroom_core_integration`；资源互斥、事务回滚、期限/并发 |
| `python3 tests/integration/run_admin_persistence.py` | AdminPersistenceTest | `studyroom_admin_integration`；范围、版本、管理员保护、配置原子性、查询数量 |
| `python3 tests/integration/run_shared_persistence.py` | SharedPersistenceTest | `studyroom_shared_integration`；幂等、通知租约/去重、审计权限、限流 |
| `python3 tests/integration/run_core_acceptance.py` | CoreAcceptanceMatrixTest | `studyroom_core_matrix`；实际 HTTP、可控时钟、权限/时间/统计矩阵 |
| `python3 tests/integration/run_core_contract.py` | CoreContractTest | `studyroom_core_contract`；随机端口实际 HTTP 与 OpenAPI 响应结构 |
| `python3 tests/integration/run_core_inputs.py` | CoreInputBoundaryTest | `studyroom_core_inputs`；类型、时间边界、原始 JSON 幂等语义 |
| `python3 tests/integration/run_smtp_acceptance.py` | CoreSmtpAcceptanceTest | `studyroom_core_smtp`；真实 SMTP/Mailpit、拒收与重试；另需 local Mailpit，会往该邮件服务投递 |
| `python3 tests/integration/run_repair_persistence.py` | RepairPersistenceTest | `studyroom_repair_integration`；期限去重、并发扫描、失败不泄漏 outbox |
| `python3 tests/integration/run_repair_deadlines.py` | RepairDeadlineHttpTest | `studyroom_repair_deadlines` 加真实 core HTTP；**也会向正在运行的 local core 创建人员/房间/通知**，需双服务与演示账号；结果另存 `output/repair-acceptance/deadlines` |

有 schema 或种子修改时，先构建最新迁移 JAR，不能依赖旧 `target`：

```sh
./mvnw -B -ntp -pl tools/migration-runner -am -DskipTests package
python3 tests/integration/run_core_persistence.py
```

上面只选择 core persistence；其他改动换用对应 runner。实际执行哪些断言，以本次报告中的类名、用例数、失败/错误/跳过为准。完整门禁也会执行这九个 suite，但连接其自己建立的隔离环境。

## 公共 HTTP 与日志验收

自己的原生 local 双服务已启动、演示密码文件存在时，core HTTP 入口可直接执行：

```sh
python3 tests/integration/core_http.py
python3 tests/integration/run_repair_acceptance.py
```

第一条默认连接 `http://localhost:8081`，从 `.local/local/secrets/demo_password` 回退读取密码，创建独立前缀的测试资源/账号并做并发预约等操作。第二条 wrapper 从 local 状态加载秘密与 Mailpit 地址，运行 M3 公共负面矩阵；它连接现有原生服务，不是自建临时应用。密码已被人工更改时，种子文件不一定仍适用于这些演示账号。

需要单独跑 repair 全链路、管理或日志测试时，以下例子把 local 秘密仅通过子进程环境传递，不在命令行展开值。它仍会写本机业务库；完整隔离检查通常更省心。

```sh
python3 - <<'PY'
import os
from pathlib import Path
import subprocess
env = os.environ.copy()
env['STUDYROOM_TEST_PASSWORD'] = Path('.local/local/secrets/demo_password').read_text().strip()
env['STUDYROOM_CORE_URL'] = 'http://localhost:8081'
env['STUDYROOM_REPAIR_URL'] = 'http://localhost:8082'
env['STUDYROOM_CORE_LOG'] = str(Path('.local/local/core.log').resolve())
env['STUDYROOM_REPAIR_LOG'] = str(Path('.local/local/repair.log').resolve())
for test in ('repair_http.py', 'admin_http.py', 'logging_http.py'):
    subprocess.run(['python3', 'tests/integration/' + test], env=env, check=True)
PY
```

`logging_http.py` 要求真实两服务日志，缺少日志文件会失败。它核对请求关联、身份、安全拒绝与秘密排除，不接受虚构日志。命令的控制台结果与各 runner 报告才是本次证据，不会自动替换历史 `docs/evidence`。

各脚本环境变量并不统一：

| 脚本 | 地址/秘密来源 |
| --- | --- |
| `core_http.py` | `CORE_URL`；密码顺序 `DEMO_PASSWORD`、`STUDYROOM_TEST_PASSWORD`、默认 local 文件 |
| `repair_http.py` 及复用其 Client 的脚本 | `STUDYROOM_CORE_URL`、`STUDYROOM_REPAIR_URL`，或共同 `STUDYROOM_BASE_URL`；要求 `STUDYROOM_TEST_PASSWORD` |
| `logging_http.py` | 继承 repair 地址/密码变量，另要求 `STUDYROOM_CORE_LOG`、`STUDYROOM_REPAIR_LOG` |
| 浏览器脚本 | 使用其 README 指定的 `STUDYROOM_TEST_PASSWORD_FILE` 等变量，不把它误套到所有 HTTP 脚本 |

修改目标地址前先读具体脚本，确认目标完全属于本次测试。已有发行实例的入口与密码位置见[接手卡](../operations/current-instance.md)，该页不是授权测试目标的依据。

## 网络故障与后台恢复

`./scripts/check` 已在自建环境验证 core 提交资源命令后丢失响应、repair 保留原命令重试的关键路径。需要调试本地原生进程时才执行：

```sh
python3 tests/integration/run_repair_faults.py
```

它要求 local 双服务正常、代理端口 18081 空闲，并核实 repair PID 使用本 checkout 的 `.local/local/run/repair.jar`。脚本会停止并重启该 repair，使其经过故障代理；最后恢复正常 core 地址。执行时不要并行进行本地浏览器演示或其他写入测试，不要把它用于容器发行实例。

另一套 `run_fault_matrix.py` 使用独立 18180–18183 端口、`studyroom_fault_matrix` 数据库和稳定 JAR 副本。它需要已有 local PostgreSQL/配置、`.local/local/run/core.jar`、`.local/local/run/repair.jar`、已构建的迁移 JAR，以及 `apps/web/dist`；脚本本身不构建应用。确认这些产物对应预期源码，端口未占用，且没有旧 fixture 需要恢复后，才从仓库根运行完整序列：

```sh
python3 tests/integration/run_fault_matrix.py all
```

action 是必填参数，不能只执行脚本名。`all` 成功后仍保留进程，失败也可能留下运行状态；检查结果后显式停止它自己的 web/repair/proxy/core：

```sh
python3 tests/integration/run_fault_matrix.py stop
```

`stop` 保留数据库、`.local/fault-matrix` 和 `output/fault-matrix` 证据，不会清空 fixture。已有 fixture 时不要重复 `all`、`setup` 或 `prepare`；先核对已有工单和进程，再选择与当前状态相符的恢复/核验阶段。各阶段和精确断言见[故障矩阵记录](../evidence/repair-fault-matrix.md)；停机补偿的固定时钟/进程恢复证据见[恢复记录](../evidence/core/restart-recovery.md)。复现历史演练前核对原版本与当前产物，历史通过不等于本次执行结果。

## 手机弹窗与布局检查

桌面打开的弹窗可能保留点击位置作为动画原点。缩至 375px 后，手机样式将原点固定在中心，避免退出动画沿旧桌面位置越出视口；修改响应式样式时应覆盖这一切换过程。

[浏览器 helper](../../tests/browser/helpers.mjs) 保留 `.ant-modal:visible, .ant-drawer-open .ant-drawer-content-wrapper` 选择器，在一次 `evaluateAll` 页面回调内读取选中元素、视口/页面宽度及各元素 `getBoundingClientRect()`，避免先枚举可见元素、再跨调用读取尺寸的竞争。页面和可见弹层仍按 **1px 容差、5 秒轮询上限**判断稳定布局，没有扩大容差或移除弹层检查。

[管理员用例](../../tests/browser/studyroom.spec.mjs) 额外在桌面打开创建账号弹窗，等待打开动画结束，缩至 375px 后关闭，并用 `requestAnimationFrame` 逐帧检查退出过程：每个采样帧均不得超出同一 1px 边界，实际采样帧数须大于零，观察循环以 2 秒为上限并断言弹窗完成关闭。该步骤不提交账号创建，之后继续原管理员空结果与学生操作权限检查。逐帧断言补充稳定布局检查，不能用最终关闭后的空元素集合代替动画过程。这里说明实现与通过标准；本轮修复是否通过，以实际浏览器报告和[证据索引](../evidence/README.md)为准。

## 如何判断改动已验证

纯策略或日志格式改动先跑相应模块单元；SQL、迁移、锁序、幂等、期限改动要跑对应真实 PG；公开协议改动增加契约和 HTTP 行为；跨服务变化增加实际 HTTP 与故障重试；交互变化增加浏览器试用。按[贡献指南](../contributing.md)完成最终门禁，并记录本次命令、目标、版本、结果与未覆盖项。

不要将静态阅读源码、生成成功、测试被跳过、旧版本历史报告或 GitHub skipped 检查写成当前验收通过。正式外部 SMTP 是交付边界之外的独立验收；本机 Mailpit 证明本地邮件链路，不证明外部收信。
