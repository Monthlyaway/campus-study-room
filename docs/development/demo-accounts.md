# 演示账号与样例数据

[开发导航](README.md) · [用户手册](../user-guide/README.md) · [当前实例](../operations/current-instance.md)

课程演示使用下面的公网入口和课程账号。开发测试账号在本页后半部分，两套环境不能混用。

## 公网课程验收账号

入口：[https://49.51.193.169](https://49.51.193.169)。以下 11 个账号均已通过实际 HTTPS 登录验证，可直接用于本人和老师的课程演示。

| 账号 | 初始密码 | 用途与授权范围 |
| --- | --- | --- |
| `course-student` | `xUA5r-jSe2TRjsRz616iiB-32RddFwmh` | 学生：计算机学院、共享教室；本人预约/报修 |
| `course-student2` | `owK_nOcjpizKT2fuyH-niKzPXhNlGPUZ` | 学生：人文学院、共享教室；本人预约/报修 |
| `course-applicant` | `rdIUkE4lPtHqMwH5_xn5zlx0qP8wbRM6` | 学生 + 整教室申请人：计算机学院、共享教室 |
| `course-dept-approver` | `ef_hCQSPMvzDXnamo3vYxjhZ5s_ReSBB` | 院系审批员：课程计算机学院和人文学院，第一级审批 |
| `course-space-approver` | `hRrVE3a18pINdSW_SVBc07F2jXOw5p0I` | 空间审批员：三间课程教室，第二级审批 |
| `course-dispatcher` | `L_u8UKxOhvFlWJKF9CbGNbWHSxkDDkSR` | 物业派单员：三间课程教室，分配维修工单 |
| `course-repairer` | `UVDWn9JNqg3GXuTSjJvSOvwA4uPVkaQs` | 维修人员：本人被派工单，开始/完工 |
| `course-property-admin` | `N1r1QZP8QzWl8434Me_P-wlg7AET5ndp` | 物业管理员：三间课程教室，监督和验收 |
| `course-room-admin` | `jj10KhvVM-hmyCkFWVG27PfIXDFBDTAd` | 空间管理员：三间课程教室，座位/开放时间/签到屏 |
| `course-operator` | `1C9x0BU5jjbFxE03U2QqnntzA9079Pjs` | 运营人员：三间课程教室，统计与业务查询 |
| `course-admin` | `EvAwBpd5GNS4YVP68gfQWQiT_n0gNnaT` | 系统管理员：账号/角色/授权/参数/审计；不自动具有业务审批权限 |

登录验证时间：2026-10-23T13:12:10+00:00。这里记录初始密码；管理员改密后请同步本页。切换角色前先退出，或使用彼此独立的浏览器会话。

系统管理员管理账号与权限；整室申请、两级审批和维修分别使用表中的业务角色。老师查看本页需要私有仓库读取权限，访问应用只需要课程账号。

### 公网样例数据

课程主校区 / 课程图书楼中有以下教室，每间 10 个座位（01～10，偶数编号带插座），每天 08:00～22:00 开放，时区 Asia/Shanghai。

| 教室编号 | 名称 | 准入 |
| --- | --- | --- |
| `COURSE-SHARED` | 课程演示·共享自习室 | 学校共享 |
| `COURSE-CS` | 课程演示·计算机自习室 | 课程计算机学院 |
| `COURSE-ART` | 课程演示·人文自习室 | 课程人文学院 |

初始化不预造预约、审批或维修成功记录，按下面顺序实际操作即可。邮件先进入 Mailpit 测试收件箱，不向真实外部邮箱发送。

### 验收顺序

1. 使用学生账号，在已开放的课程教室选择**未来的整点时段**预约座位，核对“我的预约”新增记录；取消只能在开始前，签到必须在真实开始后十五分钟窗口内。操作和时间边界见[预约与签到](../user-guide/workflows.md#1-预约座位签到与再次预约)。
2. 使用申请人提交未来时段整室申请，再分别用院系审批员和空间审批员依次批准。两级使用不同人，申请人不能自批；第二级完成后核对教室占用。见[两级审批](../user-guide/workflows.md#2-申请整间教室由两人依次审批)。
3. 使用报修人上报课程教室故障，派单员派给维修人员，维修人开始/完工后由原报修人或另一位合格物业管理员验收。等同步完成后核对工单关闭与资源恢复，维修人不能自验。见[维修闭环](../user-guide/workflows.md#3-报修派单维修验收与评价)。
4. 用空间管理员检查教室与签到屏，用运营或系统管理员查看其权限内的统计/审计；账号/授权的改动由系统管理员完成，见[逐页说明](../user-guide/pages.md)及[管理操作](../user-guide/administration.md)。

切换角色时先退出，或使用彼此独立的浏览器配置/无痕窗口，避免多个标签页共享 Cookie。演示会产生真实业务记录，不通过清库、修改时间或删除历史制造通过。出现占用冲突就选择真实可用时段；维护和故障按[运维手册](../operations/runbook.md)处理。

## 隔离开发测试账号

下文仅用于自己的 local/dev/test 演示或开发环境；不是公网课程账号，也不能把这些种子账号和密码用于生产。

## 先确认登录的是哪套环境

| 环境 | 应用入口 | 初始演示密码文件 |
| --- | --- | --- |
| 历史 Mac v0.1.0 实例（非当前开发入口） | `http://localhost:19282` | 项目根下 `.local/portable-install/secrets/demo_password` |
| 新源码安装的默认 local | `http://localhost:18080` | 该 checkout 的 `.local/local/secrets/demo_password` |
| 默认原生 local 开发 | `http://localhost:8000` | 同一开发配置的 `.local/local/secrets/demo_password` |
| 自定义状态目录的离线安装 | 该状态目录 `compose.env` 的公开地址 | `<STUDYROOM_STATE_DIR>/secrets/demo_password` |

文件只保存在对应机器，权限为 0600，不进入 Git。由环境维护者在本机私下查看或提供给获准使用者，不要把内容放进聊天、截图、提交、命令实参或 CI 日志。忘记正式账号密码时联系管理员重置，本系统没有自助注册或邮件找回入口。

同一演示环境首次创建的 11 个账号使用该环境随机生成的初始密码；不同环境不能假设相同。管理员改过密码后以新密码为准；重复 seed 不会覆盖已存在账号的密码和角色设置。

## 选对角色

| 用户名 | 初始组织 | 角色与典型用途 |
| --- | --- | --- |
| `student` | CS，计算机学院 | 学生：选座、预约、签到、本人报修与验收 |
| `student2` | ART，人文学院 | 另一院系学生：检查院系准入和本人数据隔离 |
| `applicant` | CS，计算机学院 | 学生 + 教室申请人：整教室申请，也可使用学生流程 |
| `dept-approver` | SYSTEM，系统管理 | 院系审批员：处理第一级审批 |
| `space-approver` | SYSTEM，系统管理 | 空间审批员：处理第二级审批 |
| `dispatcher` | SYSTEM，系统管理 | 物业派单员：派单、重新派单和未开修工单管理 |
| `repairer` | SYSTEM，系统管理 | 维修人员：处理分配给自己的工单、开始维修、提交完工 |
| `property-admin` | SYSTEM，系统管理 | 物业管理员：范围内工单验收、同步关注与通知管理 |
| `room-admin` | SYSTEM，系统管理 | 空间管理员：教室、座位、开放时间和签到屏幕 |
| `operator` | SYSTEM，系统管理 | 运营人员：授权范围内使用统计和业务明细 |
| `admin` | SYSTEM，系统管理 | 系统管理员：账号、角色、权限、参数与审计；不自动拥有学生预约或业务审批资格 |

这是**首次 demo seed 的映射**，已有环境可能经过授权调整。实际菜单由当前权限决定，具体对象的按钮还取决于数据范围和状态。为了演示，管理类初始绑定使用 ALL 范围；这不表示真实部署应给所有工作人员全校范围。正式授权示例见[用户管理工作流](../user-guide/workflows.md)。

两级审批必须使用不同人员，申请人不能自批；实际维修人不能验收自己的维修。切换角色时退出当前账号，或使用彼此隔离的浏览器配置/无痕窗口，避免不同标签页共享同一个登录 Cookie。

## 首次样例数据

校区为 `MAIN`（主校区），楼宇为 `LIBRARY`（知行楼）：

| 教室 | 名称 | 初始归属 |
| --- | --- | --- |
| `R101` | 静思自习室 | 学校共享 |
| `R201` | 计算机学院研习室 | CS |
| `R301` | 人文学院阅览室 | ART |

每室有 `01`～`10` 共 10 个座位，偶数编号带插座；每周七天初始开放时间为 08:00～22:00（Asia/Shanghai）。默认个人预约最长 4 小时、教室申请最长 8 小时，可由管理员配置。seed 不创建成功预约、申请或维修订单；后续操作与测试会增加记录，所以已有环境不能保证仍是空白。

用 `student`、`student2` 对比学校共享与院系资源，用 `applicant → dept-approver → space-approver` 学习审批，用 `student → dispatcher → repairer → student` 学习维修验收。具体步骤和成功状态见[常见工作流](../user-guide/workflows.md)。

## 开发测试使用方式

完整 `./scripts/check` 会创建自己的隔离数据和凭据，不需要把现有实例密码写进配置。浏览器套件需要一个明确授权的独立发行测试环境，并通过 `STUDYROOM_TEST_PASSWORD_FILE` 读取密码文件；不同 HTTP 脚本的地址和凭据变量并不完全相同，按[测试指南](testing.md)选择，不能通用复制一组变量。

数据来源：[MigrationRunner.reference/demo/user](../../tools/migration-runner/src/main/java/com/campus/studyroom/migration/MigrationRunner.java)。权限和状态的最终判断由服务端执行，账号名本身不是授权依据。
