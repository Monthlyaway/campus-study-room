# 校园资源服务平台技术方案

> **文档性质：原始规格基线。** 本文保留初始要求和计划。当前使用、开发和运维从[文档首页](docs/README.md)开始；计划路径与实际实现的对应见[规格对照](docs/reference/README.md)，完成状态见[进度](docs/progress.md)。附带文档中的执行约定不替代用户当前指示。

<details>
<summary>章节导航</summary>

- [1. 执行规则与固定决策](#1-执行规则与固定决策)
- [2. 系统边界和目录](#2-系统边界和目录)
- [3. 身份、权限和会话](#3-身份权限和会话)
- [4. 数据模型](#4-数据模型)
- [5. 事务、锁与状态机](#5-事务锁与状态机)
- [6. HTTP契约](#6-http契约)
- [7. 幂等、跨服务一致性与通知](#7-幂等跨服务一致性与通知)
- [8. 前端实施约定](#8-前端实施约定)
- [9. 初始化、种子数据与配置](#9-初始化种子数据与配置)
- [10. 自动测试与质量门禁](#10-自动测试与质量门禁)
- [11. CI/CD与回滚](#11-cicd与回滚)
- [12. 可观测性和运维验收](#12-可观测性和运维验收)
- [13. 需求到实现的映射](#13-需求到实现的映射)
- [14. 可选业务Agent的边界](#14-可选业务agent的边界)
- [15. 官方资料](#15-官方资料)

</details>

> 实施说明（2026-10-22）：用户已明确指定 GitLab、自主提交与本地可迁移交付，邮件先使用本地测试服务。当前交付边界和原文流程的适用调整见 [ADR 0002](docs/adr/0002-delivery-and-gitlab.md)。业务规则与未调整的验收标准继续保留。

版本：1.0。基线日期：2026-10-22。状态：交付给下游 AI Agent 的实施基线，尚未实施。

本文件定义架构、数据、接口、并发、后台任务、安全、测试和发布契约。业务规则以 [01_PRD.md](01_PRD.md) 为准；执行顺序以 [03_Development_Milestones.md](03_Development_Milestones.md) 为准。三份文件必须一起交给开发 Agent。

## 1. 执行规则与固定决策

### 1.1 约束

1. 业务功能从空业务模块开发。禁止以若依、芋道、商城、工单系统等完整业务项目为基础修改。
2. 复用知名组织维护的基础技术：Spring、React、蚂蚁 Umi/Ant Design、PostgreSQL、Docker、GitHub Actions、Redgate Flyway。不得自行替换主技术栈。
3. Spring Data JPA 负责日常数据库访问；Spring 管理事务与锁；Spring Security 管理认证过滤链与密码编码；Ant Design 提供通用 UI 组件。
4. 允许编写 Flyway SQL 迁移和确有必要的数据库索引。禁止把“不手写底层操作”理解为“不设计约束、不写迁移”。业务 CRUD 不使用散落的 JDBC/字符串拼接 SQL。
5. P0 不引入 Redis、Kafka、Kubernetes、工作流引擎、独立认证服务器或微前端。固定顺序审批由业务状态与审批步骤表实现。
6. 必须实现两个独立后端进程。M3 到 M1 的资源写回必须经过真实 HTTP，不能用同进程方法调用或跨 schema SQL 替代。
7. 补充产品规则已经在 PRD 固定。Agent 不得自行放宽权限、跳过失败恢复或以 mock 替代验收。

### 1.2 依赖基线

| 项目 | 固定版本范围/规则 | 管理方式 |
|---|---|---|
| Java | 21 LTS | 固定基础镜像 digest、Maven toolchain |
| Spring Boot | 4.1 系列稳定版 | 用同一个 parent/BOM 管理两个服务及迁移程序；MS0 锁定精确 patch |
| Spring Data JPA、Security、Session、Hibernate | 跟随所选 Boot BOM | 不分别追逐新版本 |
| PostgreSQL | 18 稳定系列 | MS0 固定精确镜像 digest |
| Flyway | 使用 Boot BOM 兼容版本，包含 PostgreSQL 数据库支持模块 | 只在迁移程序中启用 |
| Node.js | 24 LTS | 固定基础镜像 digest |
| React / TypeScript / Umi / Ant Design | React 19、TypeScript 5、Umi 4、Ant Design 6 | 使用官方基础工程和兼容依赖组合；固定精确版本与 pnpm-lock.yaml |
| pnpm / Maven | 由 packageManager / Maven Wrapper 固定 | CI 禁止临时升级 |
| Nginx、Docker Compose、Ansible、Playwright | MS0 选择稳定版本并锁定 | Docker 固定 digest，CI action 固定 commit SHA |

MS0 的唯一版本确定动作：在上述系列中选择当日可取得、受维护的稳定 patch；完成后端构建、ORM/Session/Flyway 启动和前端组件构建测试；把精确版本、官方来源和镜像 digest 写入 `docs/dependency-baseline.json`。若 peer dependency 冲突或系列已不可用，停止升级猜测，提出带测试结果的 ADR 变更，由人评审。后续安装必须使用 lockfile；禁止 `latest`、浮动镜像标签和自动跨 major 升级。这是明确的首次建仓任务，不是让各模块自行选版本。

## 2. 系统边界和目录

### 2.1 部署单元

| 单元 | 职责 | 可直接访问的数据 |
|---|---|---|
| core-service | D、M1、M2、资源可用性裁决、邮件发送 | `core`、`session` |
| repair-service | M3、维修命令发送、工单任务 | `repair`、`session` |
| web | React 单页应用，学生与管理布局 | 只能调用公开 HTTP API |
| nginx | 同域 HTTPS、静态文件、API 反向代理、维护页 | 不访问业务库 |
| postgres | 一个实例、三个 schema | 独立持久卷 |
| migration-runner | 发布时的一次性迁移程序 | 通过迁移账号操作三个 schema |

`web` 构建产物封装在 Nginx 镜像中，因此发布清单中共有三个应用镜像：web、core、repair。数据库不是每次发布重新初始化的应用镜像。

浏览器统一访问 `https://<environment-domain>`：

- `/`：静态前端，SPA 路由 fallback 到 index.html。
- `/api/v1/repairs` 及其下级路径：repair-service。
- 其他 `/api/v1/*`：core-service。
- `/internal/*`、`/actuator/*`：公共 Nginx 一律拒绝。
- readiness、内部 API 只在 Compose 私有网络访问。数据库端口不映射到公网。

M2 通过 core 内的 `ResourceService` 调用 M1 资源能力。M3 通过 `POST /internal/v1/resource-commands` 调用 core。只有 `ResourceService` 能写 `resource_claim` 和资源来源状态。

### 2.2 单仓库目录

```text
apps/web/
services/core-service/
services/repair-service/
modules/api-contracts/
modules/security-common/
modules/test-support/
tools/migration-runner/
db/migration/session/
db/migration/core/
db/migration/repair/
db/seed/reference/
db/seed/demo/
contracts/openapi/
infra/compose/
infra/ansible/
scripts/
tests/e2e/
tests/load/
docs/adr/
docs/evidence/
.github/workflows/
AGENTS.md
```

`api-contracts` 只放 DTO、错误码和枚举，不能放 JPA Entity、Repository 或业务 Service。`security-common` 只放稳定身份结构、认证/会话配置和授权客户端。两个服务独立启动、独立数据库账号；共用安全代码不等于共用业务表。

### 2.3 代码分层

- Controller：协议、参数基础验证、DTO 转换；不能修改 Repository。
- Application Service：业务授权、状态机、事务、幂等命令。
- Domain Policy：时间、范围、冲突规则；必须有独立行为测试。
- Repository：Spring Data JPA 查询、分页、锁。
- Entity：数据库映射；不直接序列化为 API 响应。
- Job：查到期数据、领取任务、调用同一 Application Service；不得绕过用户接口使用的业务规则。

公共接口、schema、安全组件和锁协议的改动必须由相关模块负责人评审。业务模块所有权不因部署在同一进程而合并。

## 3. 身份、权限和会话

### 3.1 认证链路

使用 Spring Security 表单式用户名/密码认证，API 返回 JSON。只开放管理员创建账号；P0 不做公众注册、外部 SSO、找回密码邮件。密码由 Spring `PasswordEncoder` 编码，使用带算法前缀的 BCrypt，cost=12。密码至少12个字符、最多64个字符且 UTF-8 不超过72字节。禁止明文、可逆加密或自制 hash。

会话使用 Spring Session JDBC。两个服务采用相同的 session 表、cookie 名和安全上下文格式。cookie 名 `PJSESSION`，Path=/，HttpOnly，生产 Secure，SameSite=Lax；本地 localhost HTTP 才可关闭 Secure。闲置30分钟过期，登录后最长8小时；登出主动失效。登录成功更换 session id。

会话只保存稳定的用户标识、登录时间与 `auth_version`，不把 JPA 实体、懒加载对象、业务集合或长期有效的授权结果放入会话。可使用共同安全模块中的稳定 principal；序列化格式变更必须通过旧应用+新会话兼容测试。前端不能读取会话 cookie。

`GET /api/v1/auth/csrf` 建立/读取 CSRF token；前端写请求传 `X-CSRF-TOKEN`。保留 Spring Security CSRF 防护。登录成功后重新取得 CSRF token。禁止为了方便前后端分离而全局关闭 CSRF。

### 3.2 动态授权

所有公开请求先验证会话。core 每次请求检查账号启用状态、auth_version和当前权限/范围。repair 从共享会话取得用户身份，再调用 core 的内部授权接口；P0 不缓存授权结论。core 不可达时，repair 返回503 `AUTHORIZATION_UNAVAILABLE`，不执行需要授权的读写。

角色由 `role`、`permission`、`user_role`、`role_permission` 及范围表管理。新增角色可以组合已有 permission key。新增后端操作必须随代码登记 permission key。默认角色包括 PRD 中的 STUDENT、ROOM_APPLICANT、DEPT_APPROVER、SPACE_APPROVER、PROPERTY_DISPATCHER、REPAIRER、PROPERTY_ADMIN、ROOM_ADMIN、OPERATOR、SYSTEM_ADMIN。普通 STUDENT 不默认拥有整教室申请权限。

范围类型固定为 SELF、DEPARTMENT、ROOM、ALL，授权范围保存到 `user_role_scope`。同一角色绑定可具有多条范围；权限与范围必须来自同一用户角色绑定，不得把A角色的权限与B角色的ALL范围拼接放大。拒绝优先于未找到授权；多个明确允许绑定取并集。审批禁止本人审批，且两个审批步骤不得由同一个人通过。

SYSTEM_ADMIN 可以配置角色、权限、账号与系统参数，但不自动取得两个业务审批步骤的资格。账号停用、密码重置、用户角色/范围变化，以及某角色的权限集合变化时，增加所有受影响用户的auth_version并失效其全部会话。重新登录后使用当前授权。角色删除仅软停用，有用户关联时409拒绝；不能删除最后一个有效SYSTEM_ADMIN绑定。

账号启用是所有操作的前提。本人既有对象的查询、合法取消、签到、报修验收和评价采用PRD的收尾权限例外，不依赖当前创建资格；创建新业务和管理操作仍按当前角色校验。详情DTO返回allowedActions，前端据此展示对象动作。core授权判断必须接受action对应的稳定对象类型和ownerId；M3的ownerId/assignedTo必须取自本地数据库，不取自浏览器body。

### 3.3 服务身份

服务间调用使用 Spring Security HTTP Basic 专用服务账号，密码为每环境独立生成的至少32字节随机值，放在 Docker secret 文件。内部 HTTP 仅在同一主机的私有 Compose 网络运行，不发布主机端口；此部署假设不能扩展到跨主机明文传输。迁移到多主机前必须通过 ADR 改为 TLS/mTLS。

服务账号的权限固定：repair只可调用资源命令、授权判断、房间/维修人员引用查询、通知提交；不可调用用户管理和任意SQL。服务身份不代替业务用户：用户动作仍需传递已验证的 actorId，并先执行用户授权；后台续投已经持久化的命令使用服务身份。

内部授权接口只能供受信任服务使用。前端提供的userId、角色、departmentId、已授权标记均不能直接用作当前身份。

## 4. 数据模型

### 4.1 共通约定

- ID：服务端生成 UUID，API 以字符串传递。禁止前端决定资源主键。
- 时间：数据库 `timestamptz`；内部 `Instant`；API RFC3339带偏移；展示Asia/Shanghai。开放时间和日期规则按Asia/Shanghai解释。
- 时段：左闭右开 `[start_at,end_at)`；相邻时段不冲突。M1、M2起止必须为上海时间整点。cancel_reason统一枚举USER_CANCEL、ADMIN_CANCEL、MAINTENANCE；无取消原因时为空，INTERRUPTED另在审计/历史记录MAINTENANCE。
- 可变聚合有 `version bigint not null default 0`，映射 `@Version`。审计、历史、已处理命令是追加记录，不随业务撤销删除。
- 通用实体包含 `created_at`、`updated_at`。不可变历史只有created_at。逻辑停用使用enabled；核心业务和审计禁止物理删除。
- 外键仅在本schema内建立。跨服务引用使用不可重用的UUID并通过API校验。用户和房间不物理删除，保留历史可解释性。
- 枚举存 varchar+CHECK，禁止 PostgreSQL enum 以减少迁移耦合；所有CHECK变更仍需迁移。未知状态不能映射为“成功”。
- 查询默认分页，最大100。对外不得输出密码hash、签到码密文、服务凭证、完整session属性。

### 4.2 core schema

以下列出必需业务字段；通用id/时间/version遵循4.1。`?`表示可空，其余必填。字段变更只能通过迁移。

| 表 | 字段与约束 |
|---|---|
| app_user | username varchar64 UNIQUE小写；school_no varchar32 UNIQUE可空；display_name varchar64；password_hash varchar255；email varchar254；department_id FK必填；enabled bool；auth_version bigint；学生角色绑定要求school_no存在 |
| department | code varchar32 UNIQUE；name varchar100；enabled bool |
| role | code varchar64 UNIQUE；name varchar100；enabled bool |
| permission | code varchar100 UNIQUE；description varchar255；随代码注册，不提供任意permission key新增接口 |
| user_role | user_id FK；role_id FK；UNIQUE(user_id,role_id) |
| role_permission | role_id FK；permission_id FK；组合唯一 |
| user_role_scope | user_role_id FK；scope_type；department_id?；room_id?；CHECK使类型与字段匹配；相同绑定和范围不可重复 |
| campus | code varchar32 UNIQUE；name varchar100；enabled bool |
| building | campus_id FK；code varchar32 UNIQUE；name varchar100；enabled bool |
| room | building_id FK；code varchar32 UNIQUE；name varchar100；department_id FK?（空=全校）；enabled bool |
| seat | room_id FK；code varchar32；has_power bool；enabled bool；UNIQUE(room_id,code) |
| opening_rule | room_id FK；weekday smallint 1..7；start_minute int 0..1439；end_minute int 1..1440；start<end且整小时；同室同weekday规则不能重叠 |
| system_config | key varchar100 UNIQUE；value_json jsonb；updated_by FK；version；仅允许PRD注册键及取值范围 |
| config_revision | id固定1；version；配置批量更新锁定此行，更新成功递增；PATCH接口expectedVersion针对该行 |
| rate_limit_bucket | bucket_key varchar200 PRIMARY KEY；recent_attempts jsonb（最多5个UTC时间戳）；blocked_until?；version；锁定该行后原子判断/更新，规则见9.3 |
| booking_guard | user_id UUID PRIMARY KEY；FK app_user；注册账号时创建；只用于同一学生跨房间预约串行化 |
| reservation | user_id FK；room_id FK；seat_id FK；start_at；end_at；status；checked_in_at?；cancel_reason?；ended_at?；config_version；max_duration_hours；version；CHECK start<end；应用强制seat.room_id=room_id |
| violation | reservation_id FK UNIQUE；user_id FK；reason固定NO_SHOW；occurred_at；不可由维修创建 |
| room_application | applicant_id FK；applicant_department_id FK（提交时快照）；room_id FK；start_at；end_at；purpose_title varchar100；purpose_description varchar2000；status；current_step smallint 1或2；expires_at；cancel_reason?；config_version；max_duration_hours；version |
| approval_step | application_id FK；step_no 1或2；required_permission；status WAITING/PENDING/APPROVED/REJECTED；UNIQUE(application_id,step_no) |
| approval_action | application_id FK；step_no；actor_id FK；decision APPROVE/REJECT；comment varchar500?；occurred_at；UNIQUE(application_id,step_no)，仅记录成功提交的决定 |
| resource_claim | room_id FK；seat_id FK?；start_at；end_at?；source_type M1/M2/M3；source_id UUID；source_version bigint；released_at?；created_by UUID；created_request_id UUID；release_request_id UUID?；UNIQUE(source_type,source_id)；M1必须seat_id非空/M2和M3必须为空；仅M3可end_at为空 |
| resource_source_state | source_type；source_id；max_version bigint；state ACTIVE/RELEASED；last_command_id UUID；PRIMARY KEY(source_type,source_id)；保留释放墓碑 |
| room_checkin_code | room_id FK；local_date date；code_hash；code_ciphertext；expires_at；UNIQUE(room_id,local_date) |
| notification | recipient_id FK；room_id FK?；event_type；source_type；source_id；dedup_key UNIQUE；payload_json；channel EMAIL；due_at；status PENDING/LEASED/RETRYING/SENT；attempts；next_attempt_at；lease_owner?；lease_until?；sent_at?；read_at?；last_error_code?；version |
| audit_event | actor_type USER/SERVICE/JOB；actor_id；action；object_type；object_id；room_id?；source_type?；source_id?；before_json?；after_json?；reason?；request_id；created_at；只存允许字段 |

`room.department_id`和`seat.room_id`创建后不可直接更改。需要迁移时停用旧资源再新建。停用资源或修改开放规则，如会影响有效预约、APPROVED申请或维修封禁，返回409并列出冲突数量；不静默取消。停用不是删除。停用房间仍可接受既存工单的维修封禁，解除维修不会重新启用房间。

### 4.3 repair schema

| 表 | 字段与约束 |
|---|---|
| repair_order | reporter_id UUID；room_id UUID；title varchar100；description varchar2000；status；assigned_to UUID?；assigned_by UUID?；submitted_at；first_assigned_at?；assigned_at?（最近派单）；work_completed_at?；accepted_at?；closed_at?；assign_due_at；work_due_at?；accept_due_at?；acceptance_round int default0；last_work_note varchar2000?；resource_version bigint default0；version |
| repair_history | order_id FK；from_status?；to_status；actor_id；reason varchar2000?；old_assignee UUID?；new_assignee UUID?；request_id；created_at；追加；保留历次完工说明和派单目标 |
| repair_acceptance | order_id FK；round_no int；actor_id；decision ACCEPT/REJECT；comment varchar500?；UNIQUE(order_id,round_no)；实际维修人不能验收 |
| repair_rating | order_id FK UNIQUE；reporter_id UUID；score int CHECK1..5；comment varchar500?；仅CLOSED且报修人可创建 |
| outbox_command | command_id UUID PRIMARY KEY；order_id FK；command_type BLOCK/RELEASE/NOTIFY；source_version；payload_json；payload_hash；status PENDING/LEASED/DONE；attempts；next_attempt_at；lease_owner?；lease_until?；last_error_code?；业务事务内创建 |
| audit_event | 与core审计字段相同，记录M3操作；只追加 |

两schema还分别持有 `idempotency_record`、`scheduled_task`。core另有 `processed_command`，详见第7节。session schema使用Spring Session官方PostgreSQL结构，经Flyway建表；不自行设计session引擎。

### 4.4 索引和查询

必须建立：

- reservation `(user_id,start_at DESC)`、`(room_id,status,start_at,end_at)`；未签到扫描 `(status,start_at)`。
- resource_claim 活跃记录的 `(room_id,start_at)` 部分索引（released_at IS NULL），辅以seat_id过滤；时段判断 `claim.start < requested.end AND (claim.end IS NULL OR claim.end > requested.start)`。
- room_application `(room_id,status,start_at,end_at)`、`(status,expires_at)`、`(applicant_id,created_at DESC)`。
- repair_order `(room_id,status)`、`(assigned_to,status)`、`(status,assign_due_at)`、`(status,work_due_at)`、`(status,accept_due_at)`。
- notification/scheduled_task/outbox的 `(status,next_attempt_at)` 或 `(status,due_at)`；audit `(created_at DESC)`及`(room_id,created_at DESC)`。
- processed_command.command_id、resource_source_state来源键、violation.reservation_id、通知dedup_key唯一。

首版无需搜索引擎。可用座位查询采用NOT EXISTS形式排除有效整室及单座占用。ORM查询中不能把几千个预约先全部加载到Java再过滤。详情关联采用DTO投影/受控fetch，禁止列表N+1；性能测试必须检查SQL数量与执行计划。

## 5. 事务、锁与状态机

### 5.1 统一锁顺序

事务隔离级别固定READ_COMMITTED。新建M1预约按下列顺序：

1. 锁定该用户的 `booking_guard` 行。
2. 锁定目标 `room` 行。
3. 检查用户已有重叠预约、空间开放/准入和资源占用。
4. 创建reservation和resource_claim，写审计与通知任务。
5. 提交事务。

其他资源操作先room，再该room内的reservation/application/claim；批量业务行按UUID固定排序。严禁先拿room锁再拿booking_guard。booking_guard必须独立于app_user，避免通知/外键检查与用户锁形成反向等待。查询房间ID可以先非锁定读，因为room关联不可变；得到room锁后必须重读业务状态和version。

M1取消、签到、超时、完成；M2审批、撤销、过期、完成；M3封禁与释放全部使用相同room锁协议。任何分支不得绕过ResourceService直接改claim。锁通过JPA `PESSIMISTIC_WRITE` 实现；version检查和唯一约束作补充。锁等待/死锁返回可重试冲突，不隐藏为成功。事务内部禁止HTTP、SMTP和长计算。

### 5.2 M1

状态只能按PRD转换：RESERVED→CHECKED_IN/CANCELLED/NO_SHOW；CHECKED_IN→COMPLETED/INTERRUPTED。终态不能复活。每次预约的时长限制、开放规则和权限在创建时验证；用户院系后来变化不会把已有预约变成违约。

签到后继续保留整个预约时段的claim，不提前释放。签到判断使用获得room及reservation锁之后的服务端时间：`start <= now < start+15min`。在恰好+15min时拒绝签到；超时任务也在同一锁下检查，最多生成一条violation。NO_SHOW事务同时更改预约、释放claim、插入violation和通知。

预约取消与维修导致的终止释放claim时保留原行并设置released_at。历史复用只把旧room/seat选择填入新表单；提交走完整的新建流程。

### 5.3 M2

提交时先锁room并检查创建资格、准入、开放时间以及当时存在的M1/M2/M3冲突；冲突则409，不创建申请。检查通过后创建PENDING申请、两条审批步骤，但不占用资源。保存applicant_department_id快照供第一级审批范围判断；后续用户院系变更不改派。步骤1为PENDING，步骤2为WAITING。步骤1通过后变APPROVED，步骤2变PENDING；任何步骤拒绝则申请REJECTED。成功决定写入approval_action，失败尝试只记错误审计。

最后一级通过的事务：锁room→锁application→检查version、PENDING、当前步骤、角色范围、禁止自批/同人两步、`now<expires_at`→检查资源冲突→创建整室claim→写action→申请APPROVED→审计/通知→提交。任何检查失败都不推进步骤、不产生claim。不得使用after-commit回调创建占用。

`expires_at=min(created_at+24h,start_at)`。定时过期与人工审批遵守同一room锁；恰好到截止时间时不再允许批准。PENDING没有claim，拒绝/撤销/过期时释放操作是安全空操作。APPROVED取消与完成需要释放自己的claim。

### 5.4 M3及维修影响

M3开始维修只把ASSIGNED变为BLOCK_PENDING，并创建BLOCK命令。收到core确认后才变IN_REPAIR。完工变AWAITING_ACCEPTANCE，封禁继续存在；验收拒绝回IN_REPAIR，仍用原封禁。验收通过变RELEASE_PENDING并创建RELEASE命令，确认释放后CLOSED。

core处理BLOCK时，以接收后获得room锁的时间作为封禁start，end为空：

1. 检查命令去重、来源版本及房间存在。房间enabled=false仍允许维修。
2. 创建该工单的整室维修claim。
3. 该室end>now的RESERVED预约改CANCELLED，reason=MAINTENANCE；CHECKED_IN改INTERRUPTED。
4. 该室end>now的APPROVED整室申请改CANCELLED，reason=MAINTENANCE。
5. 释放受影响M1/M2自己的claim，写审计及通知；不创建学生违约。
6. 原子提交。PENDING申请保持原状态，之后的最终审批会检查封禁。

RELEASE只释放当前source_id对应的M3 claim，不更新seat.enabled/room.enabled，不恢复被取消的历史预约，不释放其他工单。两工单重叠维修、已停用教室、未来整室预约均必须独立保留其效果。

维修与未签到任务竞争采用PRD的已提交状态优先规则：NO_SHOW已提交则不撤销其历史；维修先取得锁时，仍RESERVED且end>now的预约按MAINTENANCE取消，不补记违约，即使未签到截止已过。不得在维修路径先补执行NO_SHOW再取消。测试用并发屏障覆盖两个先后顺序。

## 6. HTTP契约

### 6.1 通用协议

所有公开API路径以 `/api/v1` 开头，Content-Type为application/json。成功结构为 `{ "data": ..., "requestId": "uuid" }`。分页data为 `{ "items": [], "page": 1, "size": 20, "total": 0 }`，page从1开始，size范围1..100。错误结构：

```json
{"error":{"code":"RESERVATION_CONFLICT","message":"该时段资源已被占用","details":{"roomId":"uuid"}},"requestId":"uuid"}
```

错误码固定语义：400参数/时间格式错误；401无有效会话；403权限不足；404对象不存在或不可见（普通用户不可枚举）；409资源/版本/状态/幂等冲突；422合法JSON但违反明确业务限制；429限流；503依赖服务不可用。禁止用HTTP200包装失败。

所有业务写请求含 `Idempotency-Key: UUID`；登录、登出、读取CSRF除外。状态操作body必须包含 `expectedVersion`，创建操作不包含。列表/详情响应返回version。前端接到VERSION_CONFLICT必须刷新并让用户重新决定，不能静默覆盖。

请求身份从会话读取，body中的申请人/报修人不能替换当前用户。列表默认当前用户可见范围。操作人员传入assignedTo等业务目标时，后端再次校验该目标的角色与范围。

公开错误明细不泄露其他人的姓名/邮箱/申请目的；只给出资源、时段和通用冲突原因。使用requestId串联两服务日志和审计，服务调用传播该值。

### 6.2 公开接口清单

以下相对路径均在 `/api/v1` 下。C表示core，R表示repair。路径匹配先静态后`{id}`，避免`audit-events`被当成ID。

| 服务 | 方法与路径 | 请求必需字段/查询 | 响应与规则 |
|---|---|---|---|
| C | GET /auth/csrf | 无 | csrfToken；可匿名 |
| C | POST /auth/login | username,password；CSRF头 | 当前用户摘要；建立会话；失败统一401 |
| C | POST /auth/logout | CSRF头 | 204；失效会话 |
| C | GET /auth/me | 无 | userId,displayName,departmentId,permissions,scopes；用于UI展示 |
| C | GET/POST /users | 查询q,page,size；创建username,password,email,displayName,departmentId,schoolNo? | 管理账号；创建201；不回传passwordHash |
| C | PATCH /users/{id} | expectedVersion；可更新enabled,email,displayName,departmentId | 密码重置另用子接口 |
| C | POST /users/{id}/reset-password | newPassword,expectedVersion | 失效目标全部会话；204 |
| C | GET/POST /roles | 创建code,name | 新建空角色，不自动获得任何权限 |
| C | DELETE /roles/{id} | expectedVersion | 软停用；有用户关联则409；默认系统关键角色不可停用 |
| C | PUT /roles/{id}/permissions | permissionCodes[],expectedVersion | 全量替换已有key集合；原子生效 |
| C | PUT /users/{id}/role-bindings | bindings[{roleId,scopes[]}],expectedVersion | 全量替换该用户角色范围；检查不能删除最后一个有效SYSTEM_ADMIN |
| C | GET /permissions | 无 | 系统已有permission key目录 |
| C | GET/POST /departments | 创建code,name | 管理基础院系 |
| C | PATCH /departments/{id} | enabled?,name?,expectedVersion | 有启用资源/账号引用时禁止停用 |
| C | GET/POST /campuses | 创建code,name | 校区主数据 |
| C | PATCH /campuses/{id} | name?,enabled?,expectedVersion | 有启用楼宇时不得停用 |
| C | GET/POST /buildings | 创建campusId,code,name；查询campusId? | 校区楼宇信息 |
| C | PATCH /buildings/{id} | name?,enabled?,expectedVersion | 存在启用教室时禁止停用 |
| C | GET/POST /rooms | 查询buildingId,departmentId,page,size；创建buildingId,code,name,departmentId? | 创建教室201；列表按准入/管理范围过滤 |
| C | GET/PATCH /rooms/{id} | 更新name?,enabled?,expectedVersion | 不允许直接修改院系/楼宇归属 |
| C | PUT /rooms/{id}/opening-rules | rules[{weekday,startMinute,endMinute}],expectedVersion | 替换完整一周规则；冲突检查后提交 |
| C | GET/POST /rooms/{id}/seats | 创建code,hasPower | 管理座位；201 |
| C | PATCH /seats/{id} | hasPower?,enabled?,expectedVersion | 不能移房间；停用有占用座位返回409 |
| C | GET /availability/seats | startAt,endAt,campusId?,buildingId?,roomId?,hasPower?,page,size | 可预约座位列表；每项seatId,roomId,code,hasPower |
| C | GET /availability/rooms | startAt,endAt,buildingId?,page,size | 可整室预约列表；要求M2申请权限 |
| C | GET /rooms/{id}/availability | startAt,endAt | 该室座位available及不可用原因类型；不含他人信息 |
| C | GET /rooms/{id}/resource-claims | startAt,endAt,sourceType?,page,size | 仅room:manage或audit:read且有该室范围；返回来源编号、有效/释放时间与requestId |
| C | POST /reservations | seatId,startAt,endAt | 201，reservation详情；检查用户跨房间重叠 |
| C | GET /reservations | status?,startFrom?,startTo?,page,size | 普通用户仅本人；运营/管理另查受权范围 |
| C | GET /reservations/{id} | 无 | 本人或受权管理范围详情 |
| C | POST /reservations/{id}/cancel | expectedVersion | 200；仅开始前RESERVED，reason=USER_CANCEL |
| C | POST /reservations/{id}/check-in | roomCode,expectedVersion | 200；时间/本人/每日码检查 |
| C | GET /violations | page,size | 本人；管理需对应范围权限 |
| C | GET /rooms/{id}/display-code | 无 | localDate,code,expiresAt；仅受权教室屏幕账号/管理者 |
| C | POST /room-applications | roomId,startAt,endAt,purposeTitle(1..100字),purposeDescription(1..2000字) | 201；创建两级流程；普通学生无ROOM_APPLICANT时403 |
| C | GET /room-applications | view=mine/pending-review/reviewed,status?,page,size | mine按本人；pending-review按当前步骤范围；reviewed按本人已有approval_action且仍有相应读取范围 |
| C | GET /room-applications/{id} | 无 | application,steps,actions,version |
| C | POST /room-applications/{id}/decisions | decision=APPROVE或REJECT,comment?,expectedVersion | 200；拒绝必须comment非空；当前步骤由服务端确定 |
| C | POST /room-applications/{id}/cancel | reason?,expectedVersion | 200；执行申请人或管理员规则 |
| R | POST /repairs | roomId,title,description | 201；reporterId从会话取；P0无照片上传 |
| R | GET /repairs | view=mine/assigned/manage,status?,roomId?,page,size | 受权列表，不支持任意跨范围查询 |
| R | GET /repairs/{id} | 无 | order,history,acceptances,rating?,version |
| R | GET /repairs/assignable-workers | roomId,page,size | 仅该室repair:assign授权者；返回userId,displayName；通过core内部查询，不读core表 |
| R | POST /repairs/{id}/assign | assignedTo,expectedVersion | 200；SUBMITTED派单；ASSIGNED允许管理员重新派单且不延长首次workDueAt |
| R | POST /repairs/{id}/start | expectedVersion | 202；BLOCK_PENDING，资源确认异步进行 |
| R | POST /repairs/{id}/complete | workNote(1..2000字),expectedVersion | 200；AWAITING_ACCEPTANCE，保留封禁；增加acceptanceRound |
| R | POST /repairs/{id}/acceptance | decision=ACCEPT或REJECT,comment?(最多500字),expectedVersion | ACCEPT=202/RELEASE_PENDING；REJECT=200/IN_REPAIR；拒绝comment须1..500字 |
| R | POST /repairs/{id}/cancel | reason,expectedVersion | 200；仅允许PRD定义状态/角色 |
| R | POST /repairs/{id}/rating | score,comment?,expectedVersion | 201；只允许报修人一次评价 |
| R | POST /repairs/{id}/retry-sync | expectedVersion | 202；仅PROPERTY_ADMIN；重置现有命令nextAttempt，不创建新命令/版本 |
| C | GET/PATCH /system-config | PATCH changes[{key,value}],expectedVersion | GET返回version与items；version取config_revision；全部校验成功才整体提交并递增聚合version |
| C | GET /notifications | page,size | 当前用户自己的通知送达记录；不能替代邮件推送 |
| C | POST /notifications/{id}/read | expectedVersion | 200；仅接收人；设置readAt，重复读取不改变首次时间 |
| C | GET /notification-deliveries | status?,sourceType?,roomId?,page,size | 仅notification:manage；按所授权房间/来源范围查询，系统通知限ALL |
| C | POST /notification-deliveries/{id}/retry | expectedVersion | 202；仅notification:manage；未SENT时重置原记录nextAttemptAt，禁止新建记录 |
| C | GET /audit-events | action?,objectId?,roomId?,from?,to?,page,size | core审计，需范围权限 |
| R | GET /repairs/audit-events | 同上 | M3审计；前端审计页两个明确来源标签 |
| C | GET /statistics/usage | fromDate,toDate,roomId?,departmentId? | 聚合统计，最长31天，按管理范围过滤 |
| C/R | GET /version | 无 | releaseId,commit；nginx的/version由发布清单生成 |

用户所见座位网格布局为自定义组合组件，用Ant Design Button/Card/Grid组合；P0不需要三维图或地图服务。完整OpenAPI必须在开发第一条业务接口前建入contracts目录；表中字段/状态必须进入schema，不能只写自然语言说明。

### 6.3 内部接口

| 方法与路径 | 作用 | 校验 |
|---|---|---|
| POST /internal/v1/authorization/check | body:{userId,authVersion,permission,roomId?,ownerId?,assignedTo?}；返回allowed与通用reason | core使用真实用户角色与范围，不采信caller指定角色；repair提供本地持久化对象属性 |
| GET /internal/v1/rooms/{id}/reference | 返回id,enabled,departmentId,name | 为M3校验引用；不返回其他业务人员信息 |
| POST /internal/v1/resource-commands | 资源封禁/释放 | 仅repair服务账号；命令格式见7.2 |
| GET /internal/v1/repair-workers | actorId,authVersion,roomId,page,size | 校验actor有该室repair:assign；只返回启用且持有REPAIRER角色人员的userId,displayName；教室范围约束作用于派单者；提交派单时再次检查接收者启用状态与REPAIRER角色 |
| POST /internal/v1/notifications | 可靠提交M3业务邮件事件 | commandId,eventType,eventCycle,orderId,roomId,reporterId,assignedTo?,acceptanceRound?,payload；core按下述固定规则解析收件人，禁止调用方任意指定邮箱 |

权限目录至少包含：user:manage、role:manage、config:manage、room:manage、room:display_code、seat:search、reservation:create/read_own/cancel_own/check_in、reservation:read_scope、violation:read_own/read_scope、room_application:create/read_own/cancel_own/review_dept/review_space/cancel_scope、repair:create/read_own/read_assigned/read_scope/assign/work/accept_own/rate_own/cancel_own/accept_scope/cancel_scope/retry_sync、notification:read_own/mark_read_own/manage、audit:read、statistics:read。斜线串表示同一前缀下多个独立key，例如repair:accept_own。创建命令和自身访问还需身份/所有权检查，不因权限key存在就允许访问任何对象。

本人历史与收尾动作的key表示被审计的动作；按3.2所有权例外授权，不要求仍持有原创建角色。所有启用账号可以读/标记本人通知。默认PROPERTY_ADMIN持有其范围的notification:manage，SYSTEM_ADMIN持有ALL范围的notification:manage；其他角色默认不能重试。repair:accept_own/rate_own/cancel_own必须ownerId=当前用户，并执行PRD状态规则；accept_own与accept_scope都额外拒绝assignedTo=当前用户。派单、开修、完工和管理取消仍校验当前权限、范围或被指派关系。

NOTIFY命令的source_version固定0，不参与资源来源版本比较；eventCycle在派单事件取对应repair_history.id，验收事件取acceptanceRound，其他事件取0。创建outbox时固定eventCycle和payload，重试不得修改。通知接口使用与资源命令相同的commandId+payload_hash永久去重机制。

M3通知事件白名单和收件人固定：ASSIGNED→本次assignedTo；AWAITING_ACCEPTANCE→reporterId；ASSIGN_OVERDUE→该室PROPERTY_DISPATCHER与PROPERTY_ADMIN；WORK_OVERDUE→assignedTo与该室PROPERTY_ADMIN；ACCEPT_OVERDUE→reporterId与该室PROPERTY_ADMIN；CLOSED/CANCELLED→reporterId。core使用当前启用角色/范围解析群组，人员重合时按userId合并。reporterId/assignedTo来自repair已持久化工单，由受信服务提交。没有必须的直接收件人或群组事件无法解析任何收件人时返回422，不消费commandId，待配置修复后重试；成功时在同一事务固定收件人快照、插入通知并永久记录commandId，重放不再次解析或增补收件人。payload只允许事件模板所需的编号、标题、时限与状态，不接受HTML模板或任意邮箱。notification.room_id用于管理范围校验，系统级通知才允许为空。

### 6.4 统计响应与固定口径

`GET /statistics/usage`返回fromDate、toDate、timezone、reservationCount、cancelledCount、noShowCount、checkinNumerator、checkinDenominator、checkinRate、reservedSeatHours。日期上界包含toDate当天，折算为上海时间次日00:00的排他上界。预约总数、取消数与未签到数均按start_at落入区间计数，后两项分别过滤CANCELLED、NO_SHOW。签到分母再要求start+15分钟≤now且状态非CANCELLED，分子为同一集合checked_in_at非空；分母为0时checkinRate=null，否则返回0..1的小数。小时数为RESERVED/CHECKED_IN/COMPLETED区间交集时长之和，INTERRUPTED仅计[start_at,ended_at)交集，CANCELLED/NO_SHOW为0；秒数合计后除3600，响应保留两位小数。结果先按调用者管理范围过滤，禁止先聚合全校再过滤。

## 7. 幂等、跨服务一致性与通知

### 7.1 用户写请求幂等

每服务本地 `idempotency_record` 字段：actor_type、actor_id、method、canonical_path、key、request_hash、http_status、response_data_json、created_at、expires_at。唯一键为actor+method+path+key。request_hash为规范化JSON与影响业务的查询参数的SHA-256；不含requestId、cookie等传输字段。

处理顺序：认证/基础格式检查→事务内尝试建立该key记录→获取业务锁→执行业务→同事务保存2xx结果。并发相同key由唯一约束和事务协调，只有一个事务执行副作用；竞争事务回滚后开启新只读事务读取已提交结果。禁止在被约束异常标记rollback-only的事务里继续读写。

已提交成功记录保留至少24小时：相同body返回原http_status和业务data，响应requestId可以是本次值；不同body返回409 IDEMPOTENCY_KEY_REUSED。业务失败时事务全部回滚，不占用成功key，允许用同key重试。超过24小时客户端不得自动重发旧创建请求，必须先查询已知业务对象或让用户确认新操作。内部命令使用独立永久去重机制。

### 7.2 M3资源命令

```json
{
  "commandId": "uuid",
  "sourceType": "M3",
  "sourceId": "repair-order-uuid",
  "sourceVersion": 1,
  "operation": "BLOCK",
  "roomId": "room-uuid",
  "actorId": "user-uuid",
  "occurredAt": "2026-10-22T10:00:00Z"
}
```

BLOCK固定sourceVersion=1，RELEASE固定sourceVersion=2。它们与repair_order的JPA version无关。重试必须保持commandId、payload和sourceVersion不变。封禁生效时间采用core当前时间，不采用远端occurredAt倒写。

core在同一业务事务维护 `processed_command(command_id PK,payload_hash,http_status,result_json,processed_at)` 与 `resource_source_state`：

- 已处理commandId且body一致：返回原业务结果。
- 已处理commandId但body不同：409 COMMAND_PAYLOAD_MISMATCH。
- sourceVersion小于max_version：不执行，返回200 STALE_IGNORED和当前来源状态。
- sourceVersion等于max_version：只有相同目标操作才视作ALREADY_APPLIED；不同操作409 SOURCE_VERSION_CONFLICT。
- 更高有效版本：执行变更并保存新版本。即使先收到RELEASE也保存RELEASED墓碑；后到BLOCK不得复活。

成功响应data含`result=APPLIED/ALREADY_APPLIED/STALE_IGNORED`、sourceVersion、sourceState=ACTIVE/RELEASED、claimId?。repair只有在返回状态与其待完成操作一致时推进本地状态；状态不一致记录SYNC_STATE_CONFLICT并告警，不把“HTTP200”直接解释为业务成功。

接收资源命令与变更预约必须原子提交：掉电后的重复调用不能再取消一次或再发送一份业务通知。processed_command和墓碑在项目生命周期内不清理。

### 7.3 投递任务

每个服务的后台扫描周期固定30秒。任务领取与业务操作分开：用短事务和@Version领取，设置lease_until=now+120秒；HTTP连接超时3秒、响应超时10秒。领取事务提交后再执行HTTP。崩溃导致的过期lease可被重新领取。

失败重试间隔为 `min(2^attempts,300)` 秒；30秒扫描使实际重试不早于该时间。失败达到8次写ERROR日志和管理端异常标记，之后每300秒继续重试，不能删除命令或伪造DONE。401/403、payload冲突等永久性配置错误也保留记录、告警，每300秒重查；需管理员修复配置或经ADR做有审计的修复，不自动改payload。

手动重试只重置原命令next_attempt_at。禁止“补发一个新commandId”来逃过去重。部署恢复后，从PENDING及lease已过期任务继续执行。

### 7.4 通知与期限任务

`scheduled_task`字段：id、task_type、source_type、source_id、dedup_key UNIQUE、due_at、status=PENDING/LEASED/DONE、attempts、next_attempt_at、lease_owner?、lease_until?、last_error_code?。创建/撤销业务时在相同事务记录或失效相关任务；扫描执行前重新检查业务状态。

任务类型至少包括M1_PRE_START、M1_LATE、M1_NO_SHOW、M1_COMPLETE、M2_EXPIRE、M2_COMPLETE、M3_ASSIGN_OVERDUE、M3_WORK_OVERDUE、M3_ACCEPT_OVERDUE。不得创建“一次性内存timer”作为唯一任务来源。

- M1的-15/+10/+15分钟遵循PRD。短提前量预约的PRE_START due_at=max(created_at,start_at-15min)。
- 从停机恢复时，若已到+15分钟先处理NO_SHOW；过时PRE_START/LATE标DONE且不发送。若已签到，不补发未签到提醒。
- M3首次派单设work_due_at=首次assigned_at+48h；重新派单不延期。验收拒绝后继续沿用该截止时间；同一工单每种升级事件只通知一次。
- 完工重新提交验收时accept_due_at=本次work_completed_at+48h；验收超时升级以orderId+验收轮次去重，每轮最多一次，不自动验收。
- 期限任务dedup_key=sourceType+sourceId+taskType+周期/验收轮次（无轮次用0）。通知dedup_key=sourceType+sourceId+eventType+周期/验收轮次+recipientId+channel；派单事件的周期使用派单历史ID。任务重复执行不能重复增加违约或变更终态。

邮件通过Spring邮件支持连接SMTP。通知先入库，发送在事务外，成功后标SENT。发送器领取到期PENDING/RETRYING记录及lease已过期的LEASED记录；领取/租约遵循7.3。失败标RETRYING并记录attempts与last_error_code；前7次按min(2^attempts,300)秒退避，第8次起显示“发送异常，持续重试”并每300秒继续，管理员仅可重置原记录next_attempt_at。read_at与邮件发送状态独立，已读不能取消待发邮件。外部SMTP在“已接收邮件但响应丢失”时可能造成重复，不能承诺严格exactly-once；数据库业务效果必须幂等。界面显示发送状态与最后通用错误，日志不打印完整收件内容。

dev/test通过框架的测试mail adapter把渲染后的邮件写入受限测试目录/测试结果，禁止对任意真实地址发送；这只是替换外部传输边界，不mock业务期限规则。prod必须配置真实SMTP并向专用验收邮箱完成一次实际收信验收。P0站内记录不替代邮件推送。

## 8. 前端实施约定

### 8.1 页面组织

```text
src/pages/auth/
src/pages/reservations/
src/pages/room-applications/
src/pages/repairs/
src/pages/admin/
src/pages/display/
src/components/business/
src/services/generated/
src/access.ts
```

使用Umi基础工程，不导入Ant Design Pro整套业务演示。通用UI使用Ant Design；业务组合组件自行实现。一个Web应用包含student与admin两种布局，手机宽度375px可完成预约、签到和报修。管理表格在窄屏允许横向滚动，关键动作不得被永久隐藏。

页面必须覆盖：登录；本人通知/已读与授权管理员送达/重试页；座位搜索与网格；我的预约/详情/签到/违约；教室申请/审批待办/详情；我的工单/维修待办/派单/验收/评价；用户角色范围管理；房间座位与开放时间；系统配置；运营统计；审计；受权教室码展示。

Umi access逻辑只用于UI。页面取得 `/auth/me` 后控制路由/按钮，接口仍再次授权。权限变化返回403时刷新me并提示，不循环重试。401跳登录并保留安全的站内返回路径，禁止任意外部redirect。

### 8.2 接口客户端

仓库内OpenAPI是API契约真值。由Umi官方OpenAPI工具链生成TypeScript类型和调用代码，并接入一个统一请求层，处理同源cookie、CSRF、requestId、分页和错误。不得由三个模块各写一套axios/fetch封装。

CI检查OpenAPI语法、生成结果是否一致以及后端契约测试。前后端必须使用同名状态枚举。生成目录禁止手改；自定义业务转换放在generated外。

提交动作开始时生成Idempotency-Key；同一未确定结果的网络重试沿用该key。用户修改字段或明确发起新动作才生成新key。按钮loading避免连点，但后端幂等仍必须实现。202显示“资源同步中”，轮询详情每2秒、最多60秒；超过60秒停止密集轮询，显示可手动刷新与管理员可见异常，不显示“维修已开始/已关闭”。

### 8.3 页面规则

- DatePicker仅限制UI选择；服务端重复验证整点、时长、期限与开放时间。
- 历史复用复制seat选择，不复制旧状态/时间/权限结论。
- 预约冲突409后刷新余位并显示失败，不保留成功提示。
- 签到码页面必须登录并检查room:display_code范围。普通学生API不得获取有效码。代码显示页每分钟刷新日期信息，跨午夜更新。
- 无图片上传需求时不引入对象存储。P0报修仅文本；后续图片属于独立需求变更。
- 无SSR/SEO要求，前端以静态产物运行；生产不运行开发服务器。

## 9. 初始化、种子数据与配置

### 9.1 数据迁移

migration-runner使用与后端一致的Java/Boot/Flyway依赖，只运行迁移后退出，退出码非0阻断发布。迁移次序固定session→core→repair；每schema独立Flyway历史表。迁移账号有DDL权限，运行账号无DDL权限。两个应用均 `spring.flyway.enabled=false`、`spring.jpa.hibernate.ddl-auto=validate`。

数据库初始化和Spring Session官方建表脚本纳入最初版本迁移。迁移文件用 `VyyyyMMddHHmmss__description.sql`，合并前检查版本唯一；已合入且部署过的脚本不编辑。紧急修复新增迁移。禁止在生产管理面板手动改表、执行repair掩盖checksum冲突、使用create/update自动建表。

CI必须测试：空库全量迁移；上一已发布schema升级；再次migrate无结构变化；reference seed重复执行无重复数据；旧应用连接新schema仍可运行。schema检查之外还测试新增枚举、outbox消息、会话格式的兼容性。

### 9.2 种子数据

reference seed包括permission目录、默认role及其权限关系、参数默认值，以及code=SYSTEM的校级工作人员组织；使用稳定code和upsert；不能覆盖管理员已修改的角色范围或每次重设密码。修改系统默认映射必须用显式数据迁移，不靠seed暗改用户配置。

demo seed仅允许local/dev/test，通过固定namespace+业务code生成确定性UUID。在SYSTEM组织之外包含2个院系、1个校区、1栋楼、1间共享教室和每院系1间专属教室，每室10座，部分带插座；包含两个不同院系学生、一个ROOM_APPLICANT、两名不同审批人、调度员、维修员、物业管理员、教室管理员、运营员和系统管理员。每个测试自行创建业务订单，不能依赖seed已有成功订单。

prod首次账号使用一次性bootstrap子命令创建SYSTEM_ADMIN，归属SYSTEM组织并授予ALL管理范围：用户名、邮箱、密码从secret注入；数据库已有管理员时不修改其密码。命令必须拒绝空密码和仓库示例密码。prod禁止执行demo seed。

### 9.3 环境配置契约

部署前必须提供：ENV_NAME(local/dev/test/prod)、PUBLIC_BASE_URL、各schema连接账号及密码secret、内部服务账号secret、CHECKIN_CODE_ENCRYPTION_KEY、SMTP_HOST/PORT/USERNAME/PASSWORD/FROM（prod）、镜像仓库拉取凭证、备份目标、部署主机SSH凭证及host key。缺失prod必需项时bootstrap/deploy在开放入口前失败。

签到码每日按room和上海日期生成8位大写随机字符，字符集排除易混淆的0/O/1/I；用标准安全随机数。使用Spring BCrypt PasswordEncoder保存hash；输入先trim并转大写再校验。展示值用标准JCE AES-256-GCM加密，32字节密钥从secret取得，每条记录使用随机12字节nonce与128位tag；密文保存nonce、tag和keyId。不得把nonce固定或复用，不手写加密算法。跨日旧码失效。

限流固定为：登录按规范化用户名+可信来源IP，滚动5分钟内第5次失败后锁定该组合5分钟；成功登录清空该组合失败记录。签到按userId，滚动60秒内最多5次请求尝试，无论成功、失败或重放，第6次返回429及Retry-After。使用Spring过滤器与core.rate_limit_bucket；获取行锁，清除窗口外时间戳后判断并追加，独立短事务提交，业务回滚不能清除次数。最多保存最近5次事件；被限流的拒绝不追加时间戳、不延长锁定。部署重启不能清除窗口。来源IP只信任本机Nginx覆盖后的转发头；外部不能直连应用。登录错误响应不暴露账号是否存在。

业务参数默认值和允许范围以PRD参数表为唯一来源。修改参数只影响后续创建的订单；已创建订单保存自身deadline，不能批量回写既有截止时间。关闭房间/更改开放时间按冲突保护执行。

## 10. 自动测试与质量门禁

### 10.1 分层

| 测试层 | 工具/运行方式 | 必须验证 |
|---|---|---|
| 业务单元 | Spring测试、JUnit；注入Clock | 状态转换、边界时间、范围权限、参数规则 |
| 数据库集成 | Spring Boot Test + 真实PostgreSQL容器 | ORM映射、约束、锁、事务回滚、Flyway |
| 服务集成 | 启动core+repair+PG；真实HTTP | M3 outbox、幂等、断网/超时/重试 |
| 前端 | TypeScript检查、框架测试 | 表单、错误状态、权限视图与请求契约 |
| 端到端 | Microsoft Playwright | 学生/审批人/物业真实操作链路 |
| 发布兼容 | 上一版本镜像连接新schema | 核心操作、session、旧枚举与命令格式 |

禁止以H2替代PostgreSQL完成锁和迁移验收。只允许mock SMTP传输、时间与确实不属于被测逻辑的外部依赖；并发裁决、状态转换和M3→core不能mock。

### 10.2 必测风险

1. 20名不同用户并发预订同一座位同一时段，恰好1次201，其余409；不存在重复claim。
2. 同一学生并发预约两个房间的重叠时段，恰好1次成功。
3. M1创建与M2最终审批并发，只有一种占用可提交。
4. core提交BLOCK后丢弃HTTP响应，repair重试；只存在一个封禁、一次取消和一组通知。
5. 先收到RELEASE再收到旧BLOCK，不能重新封禁；重复命令body不一致被拒绝。
6. 两张维修单只验收一张，房间仍不可预约；停用教室解除维修仍停用。
7. 签到在+15分钟前后及与超时并发：最多一个最终结果、最多一条违约。
8. 最终审批与到期任务并发：截止时间之后不能通过；没有已批准但无claim的状态。
9. 直接HTTP越权访问另一院系/他人订单；不能依赖前端隐藏按钮。
10. 任务重复、应用重启、lease超时后恢复，业务结果不重复。
11. 普通STUDENT不能申请整室；申请人不能自批；同一个审批人不能通过两个步骤。
12. 数据库迁移后回滚上一套应用，旧会话和主要操作仍工作，业务数据保留。

每条PRD验收测试必须可映射到测试文件/用例ID。故意移除一次资源冲突检查、一次数据范围检查和一次“仅解除指定来源封禁”检查，观察对应测试失败；还原破坏后全绿。反证提交只在临时测试分支/工作区执行，不合入main。

### 10.3 非功能验收条件

容量基线：1000账号、20教室、合计1000座、10万历史预约。单环境应用运行主机4vCPU/8GB，CI构建在独立runner进行。20并发用户，读写比例80:20，预热60秒、持续5分钟；查询p95≤1秒，正常写入p95≤2秒，5xx率<1%。预期资源冲突409不计5xx；若达不到，应记录真实指标并优化，不减少数据规模冒充通过。

健康运行时期限任务完成延迟≤60秒；重启后≤60秒开始处理积压，积压全部处理时长按实际条数记录。SMTP实际送达受外部服务影响，期限验收以业务状态提交及发送任务建立为准，另单独验收真实邮件收取。

## 11. CI/CD与回滚

### 11.1 环境与分支

每位开发者local独立数据库。项目组有独占dev/test/prod逻辑环境，数据库、session、账号、密钥、卷、域名和通知配置完全分开。同一物理主机可承载dev/test，但prod建议独立VM。仅共用同一主机时必须在风险记录中注明故障域共享。

分支：feature/<requirement>-<description>→PR→main。main禁止直接业务推送；测试通过且人工验收后才能合并。任务书要求AI生成变更先人工验收再commit；Agent可以准备工作区和diff，不可自行跳过验收提交。代码冲突由人处理，不交给Agent自动解决。

CI在push和PR触发：编译/类型检查→单元和PG集成测试→契约检查→构建检查。任何失败停止后续阶段。main合并后，构建一次正式镜像，推送仓库，生成release manifest，然后自动部署test并运行完整E2E。test失败的manifest不得晋级prod。

### 11.2 不可变发布清单

每个候选release_id格式为`r-<UTC日期时间>-<commit前12位>`；prod发布另打`v0.x.y` Git tag指向同一commit。清单JSON至少包含：releaseId、commit、gitTag?、web/core/repair镜像digest、compose配置文件hash、schema目标版本、referenceSeed版本、dependencyBaseline hash、test结果链接、回滚兼容范围、createdAt。

清单连同对应Compose配置归档到镜像仓库OCI工件或受版本控制的release目录。test认证结果单独记录，绑定manifest SHA-256；不能靠可编辑文字“tested=true”放行。prod不重建镜像，不使用latest，前端不通过不同build模式烘焙环境API地址。

### 11.3 脚本接口

| 命令 | 合同 |
|---|---|
| `./scripts/dev-up` | 启动local依赖，执行空库迁移与demo seed；可重复运行 |
| `./scripts/check` | 执行类型、编译、单元、PG集成和契约检查；任一失败非0退出 |
| `./scripts/build-release <release-id>` | 为当前commit构建并推送三个digest，生成不可变manifest |
| `./scripts/bootstrap <env> <inventory>` | Ansible安装固定运行组件，创建目录/网络/账号，校验secret；不手工逐条操作 |
| `./scripts/deploy <env> <manifest-path>` | 验证清单、权限、环境锁；迁移与整套部署；保留previous记录 |
| `./scripts/smoke <env>` | 健康、版本、登录、查询、创建并清理专用验收数据；不得改真实用户订单 |
| `./scripts/rollback <env> <release-id>` | 仅回退应用与兼容配置；不自动执行数据库down迁移 |
| `./scripts/backup <env>` | 一致性PG备份、校验hash、记录schema/release；不含明文secret |
| `./scripts/restore <env> <backup-id>` | 仅明确授权的恢复任务调用；先还原隔离库演练，再按事故流程处理prod |

目标运行主机基线为Ubuntu 24.04 LTS、4vCPU/8GB或更高；初始化输入必须含环境名、域名、可访问的镜像仓库、SSH用户与固定host key、经授权的sudo能力、TLS证书/续期方式、外置secret路径和备份目标。DNS与主机网络须允许HTTPS入口以及镜像/软件源出站；同环境不得复用其他组的数据库或卷。bootstrap负责安装运行时并校验这些前提，缺失时明确失败，不能改用内存库或跳过TLS。

这些脚本由下游Agent实现，不是本文声称已存在的可运行程序。命令实现后写入README，CI与人工操作都调用同一入口。

### 11.4 自动test与人工prod

共享环境锁覆盖迁移、部署、冒烟和E2E。锁名按环境而不是模块。GitHub concurrency设置不取消进行中的发布；服务器额外文件锁覆盖所有发布入口。等待队列是否合并不改变规则：同环境不能并发迁移/部署，旧commit不得无审批覆盖较新成功版本。

prod使用GitHub Environment审批，禁止申请人自批，并限制允许的分支/tag。账号套餐不支持原生审批时，使用一个仅release-manager具有写权限的私有发布仓库：开发者提交发布申请，独立reviewer留下批准记录，release-manager手动运行workflow_dispatch。脚本核验当前actor、申请commit、manifest hash、test通过记录、批准人非提交人。此替代是明确的人工作业制度，不声称具备原生不可绕过的双人门禁。生产secret只在发布仓库/环境中，普通PR runner不可取得。

所有人工批准针对具体manifest，不批准“当前main”或浮动latest。批准后manifest有变化则重新批准。

### 11.5 发布步骤

1. 验证审批、manifest、镜像存在及目标环境；取得环境锁。
2. 记录current/previous release。拉取全部镜像到本地，拉取失败不影响在线服务。
3. 开启Nginx维护模式，拒绝业务写请求；设置任务暂停开关，并等待已领取任务/业务请求排空，最长60秒。未排空则终止发布并恢复原服务，不能强制杀事务后继续假定成功。
4. 停止两个旧后端，保持PG运行。备份PG，校验备份可读取；部署窗口内禁止外部直接写库。
5. 使用单独migration-runner执行三个schema迁移与reference seed；失败不启动新应用。兼容迁移部分已提交时保留schema，记录迁移状态。
6. 按manifest启动两个新后端及web；等待readiness，最长120秒。
7. 在公开入口仍维护、后台任务仍暂停时，通过私有测试入口执行冒烟；校验三个版本一致与业务关键路径。
8. 成功后开放入口、恢复后台任务，记录current=新release；保留至少前三个成功版本镜像和部署配置。
9. 新应用验证失败时，只在该发布声明并测试过的兼容范围内恢复previous应用/配置；复验成功后开放。若schema/数据已不兼容，保持维护状态并进入有人工审批的修复/恢复，不盲目换镜像。

Nginx维护开关不阻断用于部署的私有健康/冒烟路径；这些路径不暴露公网。暂停状态必须同时阻止Spring定时领取、outbox发送和通知发送；只有停web入口不算排空。

### 11.6 回滚边界

P0迁移采用expand-first：新增可空列、新表、兼容索引；旧字段保留整个课程演示期。枚举/消息新增也需兼容评估，不能因为只加列就跳过业务兼容测试。运行时应用不自行执行Flyway，避免回退旧镜像时因迁移历史校验失败而启动不了。

回滚演示固定用例：V1可预约→V2增加nullable备注字段并发布→写入V2数据→回滚V1三个应用镜像→旧功能、原有预约和V2写入数据保留。演示前在test完成相同流程，记录版本、schema与订单ID。不要删除新列来证明“回滚”。

备份策略：每日一次且发布前一次，保留7天；每次MS6验收必须在隔离数据库完成恢复演练。若恢复旧备份会丢失备份后的业务数据，必须明确列出影响窗口并由人批准。应用回滚与数据恢复是不同操作。

## 12. 可观测性和运维验收

使用Spring Boot Actuator readiness/liveness，日志统一JSON：timestamp、level、service、releaseId、requestId、actorId（仅内部UUID，不记录姓名、学号、邮箱）、event、errorCode、durationMs。严禁日志输出password、session cookie、CSRF token、内部凭证和签到明码。

核心告警条件：服务readiness失败；outbox连续失败≥8次；最旧PENDING命令超过5分钟；到期任务延迟>60秒；SMTP失败；发布/回滚失败。P0用结构化ERROR日志和受权管理页面异常列表满足可见性，不强制另建监控平台。

审计与普通日志分开。业务写入成功必须有对应审计；业务事务失败不能留下伪成功审计。被拒绝的越权/审批尝试可在独立安全日志记录最小信息。普通运行账号对audit只允许INSERT和SELECT，不允许UPDATE/DELETE。

## 13. 需求到实现的映射

| PRD需求 | 实现边界 | 必需验证 |
|---|---|---|
| REQ-D-01/02/03 | security-common、core身份权限、repair授权客户端 | Session失效、动态权限、院系/资源越权 |
| REQ-D-04/05 | core空间与配置 | 资源停用冲突、开放时间、参数范围 |
| REQ-D-06/07 | 两服务任务/审计、core通知 | 重启补偿、邮件传输边界、日志脱敏 |
| REQ-M1-01..07 | core预约域、前端reservations | 搜索、并发、签到边界、历史重验、统计口径 |
| REQ-M2-01..05 | core申请域与ResourceService | 权限门槛、双步骤、截止竞争、原子占用 |
| REQ-M3-01..08 | repair域、outbox、前端repairs | 状态机、分工、验收、重试、评价 |
| REQ-X-01..04 | ResourceService、HTTP命令、来源状态 | 多来源叠加、掉包/重复/乱序、维修影响 |
| REQ-ENG-01..08 | 仓库规范、CI、迁移、发布脚本、数据保护 | 人审记录、反证测试、空环境、发布回滚 |
| REQ-AGENT-01（P1） | 独立DSH插件，公开API客户端 | 歧义追问、真实HTTP、无直连DB |

## 14. 可选业务Agent的边界

只有P0全部验收通过后执行。插件只调用公开API，以真实用户权限操作；禁止共享数据库连接、调用Repository或复制业务规则。查询与写入分开；缺少房间/时段/操作对象时追问；写请求复用相同幂等合同；403不能改成管理员身份绕过。插件安装、配置、版本和冒烟加入同一release manifest与流水线，第三方模型密钥放secret。

不预先编造DSH插件接口。进入P1的第一项任务必须核对课程指定DSH版本及官方插件契约，固化对应版本和接口测试后才实现。DSH能力不影响P0交付。

## 15. 官方资料

以下用于核对框架行为；业务规则由PRD规定，不能用框架默认值覆盖。

- Spring项目：https://spring.io/projects
- Spring Data JPA事务：https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html
- Spring Data JPA锁：https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring Session JDBC：https://docs.spring.io/spring-session/reference/configuration/jdbc.html
- PostgreSQL锁：https://www.postgresql.org/docs/current/explicit-locking.html
- Umi：https://umijs.org/en-US/docs/introduce/introduce/
- Ant Design组件：https://ant.design/components/overview/
- Docker Compose生产部署：https://docs.docker.com/compose/how-tos/production/
- Flyway版本化迁移：https://documentation.red-gate.com/fd/versioned-migrations-273973333.html
- GitHub部署保护及套餐限制：https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments
- 来源任务书：Pj-任务书(2)(3).pdf，2026-10-08，重点第1–6页。
