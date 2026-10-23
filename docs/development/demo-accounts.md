# 演示账号与样例数据

[开发导航](README.md) · [用户手册](../user-guide/README.md) · [当前本机实例](../operations/current-instance.md)

此页用于自己的 local/dev/test 演示或开发环境。学校正式账号由管理员发放；不能把这些演示账号和初始化方式用作正式生产账号方案。

## 先确认登录的是哪套环境

| 环境 | 应用入口 | 初始演示密码文件 |
| --- | --- | --- |
| 这台机器的当前 v0.1.0 实例 | `http://localhost:19282` | 项目根下 `.local/portable-install/secrets/demo_password` |
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
