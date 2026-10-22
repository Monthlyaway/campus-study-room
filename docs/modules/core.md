# Core service：公共能力、座位预约与教室审批

## 职责与边界

`services/core-service` 通过 Spring Boot 4.1.1 提供 D、M1、M2 与资源裁决；只连接 `core`、`session` schema。`services/repair-service` 只能通过受服务账号保护的 `/internal/v1` 调用授权、房间引用、资源命令和通知接口。公开入口必须拒绝 `/internal` 与 `/actuator`。数据库结构由独立 `tools/migration-runner` 执行 Flyway，服务使用 Hibernate `validate`，不自动建表。

主要类：

| 类 | 责任 |
| --- | --- |
| `SecurityConfiguration` / `AuthController` | Spring Security、CSRF、JDBC 会话、登录、退出 |
| `AccessService` | 动态权限、角色绑定范围、账号和 authVersion 校验 |
| `AdminService` | 账号、角色、范围、主数据、开放规则与系统参数 |
| `ResourceService` | 教室行锁、用户预约 guard、占用唯一写入与维修来源墓碑 |
| `BookingService` | 座位预约、签到、取消、违约与到期任务 |
| `ApplicationService` | 两级审批、职责分离、最终审批资源事务 |
| `Idempotency` | 写请求规范化哈希、事务去重、成功结果重放 |
| `Events` / `Notifications` / `Jobs` | 同事务审计和通知、持久任务、SMTP 重试 |
| `Store` / `RoomRepository` | JPA 原生 DTO 投影与 `PESSIMISTIC_WRITE` 行锁 |

数据库读写使用 JPA EntityManager；房间和用户 guard 为映射实体。业务查询投影成 DTO，禁止将 ORM Entity 直接作为响应。所有资源变化经过 `ResourceService`。

## 安全和会话

Cookie 为 `STUDYROOMSESSION`，HttpOnly、SameSite=Lax；生产必须启用 Secure。SessionPrincipal 保存不可变 userId、authVersion 与登录时间。闲置30分钟、登录最长8小时。每个受保护请求重新核对账号启用和 authVersion；修改角色、范围、账号或密码使旧会话失效。共享 Principal 实现 `java.security.Principal`，principalName 是 UUID，兼容 Spring Session 的100字符列。

先 `GET /api/v1/auth/csrf`，再带 `X-CSRF-TOKEN` 发起登录；成功后刷新 CSRF。除登录/登出外业务写入必须使用 UUID 格式 `Idempotency-Key`，状态操作携带 `expectedVersion`。幂等命令与业务效果同一事务成功提交；失败回滚，同键不同内容409。锁使用 PostgreSQL transaction advisory lock 仅对同一幂等键串行化，资源仍使用实际教室行锁。

本人已存在业务的读取与收尾保留所有权例外，不要求仍持有创建权限。SYSTEM_ADMIN 没有隐式审批权限；审批人不能审批本人申请，同一申请两个步骤不能由同一个人完成。

## 时间、资源与并发

业务时区固定 Asia/Shanghai，API 时间必须有偏移量。时间区间左闭右开；开始必须严格晚于当前时间，结束在14天内，整点小时且在完整开放区间内。

M1 创建顺序：用户 `booking_guard` 行锁 → 教室行锁 → 同用户跨教室重叠检查 → 资源占用检查 → 创建预约、占用、审计和期限任务。其他资源操作先教室锁。M2 提交不预占；第二级批准与占用写入同一事务。冲突失败不会留下批准步骤或占用。

M3 BLOCK 创建本来源的整室封禁，取消未来 RESERVED、打断 CHECKED_IN、取消 APPROVED 整室申请；不创建违约，不改变 PENDING 申请。RELEASE 只释放该工单来源，不能恢复停用资源、复活历史订单或解除其他工单。

内部命令按 commandId+body hash 永久去重，来源版本1为BLOCK、2为RELEASE；释放墓碑拒绝迟到BLOCK复活。固定来源不能更换房间。命令响应的 sourceState 决定调用方能否完成同步。

## 签到与任务

签到窗口 `[start,start+15min)`，服务端持锁后判断。每日教室码8位，排除易混字符，BCrypt保存校验hash，AES-256-GCM保存展示密文；每条随机nonce，密钥从配置注入，普通学生不可读取。

持久任务每30秒扫描，任务执行再次判断当前状态。恢复时优先NO_SHOW；过期提醒不补发。任务与状态更新处在同一数据库事务，重复扫描不重复违约。通知记录与邮件发送分离，SMTP失败不回滚业务。第8次失败以后持续每300秒重试，管理员重试原通知而不新建记录。

本地可使用 SMTP/Mailpit；`MAIL_MODE=capture` 仅适用于隔离测试，不代替生产实际收信验收。`CONTROL_DIR/jobs-paused` 存在时停止领取新的期限与邮件工作。

## 启动与验证

通过仓库 `./scripts/dev-up` 启动；单独进程需要数据库迁移、reference/demo种子及以下配置：`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`INTERNAL_PASSWORD`、`CHECKIN_CODE_ENCRYPTION_KEY`（base64 32字节）。本地默认8081。`SERVER_PORT`、`SMTP_*`、`MAIL_MODE`、`MAIL_DIRECTORY`、`RELEASE_ID`、`GIT_COMMIT` 可覆盖对应设置。密钥只存受忽略目录或 secret 挂载，不写入版本库。

```sh
./mvnw -pl services/core-service -am test
python3 tests/integration/core_http.py
```

HTTP 验收要求真实服务和 PostgreSQL，使用隔离演示库，创建独立测试教室与账号，不依赖预置业务订单。测试包含20用户竞争一个座位、同用户跨教室竞争、幂等重放、本人隐私、最终审批冲突回滚及账号禁用后旧会话失效。验收结果见 `docs/evidence/core/acceptance.md`。

## 当前验证边界

接口覆盖不等于所有基线门禁通过。负载规模验收、完整SQL数量分析、生产SMTP、生产审批晋级、旧版镜像兼容演练分别由总体交付任务维护，不在本模块文档中宣称完成。接口契约的通用管理输入/响应 schema 还需随着完整契约验收细化；运行时行为以类型和真实HTTP验收共同约束。

### 持续重构状态

当前第一轮行为验收实现以 JPA 原生SQL完成业务CRUD，尚未达到技术方案要求的完整聚合实体/仓储分层。后续提交必须将预约、申请、资源、账号管理和任务基础设施的写入迁移到 Spring Data JPA Entity/Repository，并保持真实并发验收通过；此项是发版前的未完成项，不以接口功能通过替代。可用性查询的数据库分页与开放规则批量判断也列为当前性能整改项。
