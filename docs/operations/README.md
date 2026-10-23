# 安装与运维

[文档导航](../README.md) · [当前本机实例](current-instance.md) · [日常运维与排障](runbook.md)

先确定要维护哪一套环境，再选择下面的入口。当前交付是本机和可迁移运行包，邮件使用 Mailpit；正式服务器、域名、TLS 和外部 SMTP 按 [ADR 0002](../adr/0002-delivery-and-gitlab.md) 延后，安装/发布入口拒绝 `prod`。

| 现在要做什么 | 阅读入口 | 操作对象 |
| --- | --- | --- |
| 打开这台机器已经安装的系统 | [当前实例](current-instance.md) | 19282 的独立 test 实例，保留既有数据 |
| 在新机器或新环境从源码安装 | [安装指南](installation.md#从源码安装新环境) | 自己的 checkout；容器内构建应用 |
| 用现成发行包安装，不下载编译依赖 | [离线新装](installation.md#从离线包安装新环境) | 与包架构相同的 Docker daemon、新状态目录 |
| 为 Ubuntu 24.04 准备 Docker 与部署账号 | [Ubuntu 引导](../modules/bootstrap.md) | 已获授权、具备可信 SSH 身份的目标主机 |
| 查版本、看日志、升级、回退或验证备份 | [运维手册](runbook.md) | 明确环境名、状态目录、源码目录与 Docker context 的既有实例 |
| 维护 GitLab 验收和线性合并 | [GitLab 工作流](gitlab.md) | 项目 11753 的完整五门禁和授权引用 |
| 修改代码并调试 | [本机开发](../development/local-setup.md) | 开发进程；不以交付实例作为测试数据库 |

## 环境如何区分

以下是**新建环境的默认值**，配置修改后以相应状态目录的 `compose.env` 为准。

| 环境 | 容器应用 / Mailpit | 默认状态目录 | 默认 Compose project | 原生开发 PG / SMTP |
| --- | --- | --- | --- | --- |
| local | 18080 / 18025 | `<源码>/.local/local` | studyroom-local | 15432 / 11025 |
| dev | 18081 / 18026 | `<源码>/.local/dev` | studyroom-dev | 15433 / 11026 |
| test | 18082 / 18027 | `<源码>/.local/test` | studyroom-test | 15434 / 11027 |
| 当前本机独立 test | 19282 / 19227 | 见[接手卡](current-instance.md) | studyroom-portable-install | 不作为原生开发入口 |

正常容器安装只发布 Web 和 Mailpit 到回环地址，不发布数据库、core/repair 或内部控制接口。原生开发额外开放回环 PG/SMTP，前端为 8000、两个后端为 8081/8082；一次只运行一套原生后端。原生 local 与容器 local 默认共享状态，不能当作两套隔离环境。

`STUDYROOM_STATE_DIR` 选择绝对状态目录，但不会自动改变 Compose project 和端口。新建第二套同名环境必须同时使用独立状态、project、端口和 `PUBLIC_BASE_URL`；升级则必须保留原状态和 project。详见[安装时选择配置](installation.md#选择状态目录与端口)。

## 可迁移交付的范围

| 场景 | 当前能力与边界 |
| --- | --- |
| 在同架构机器安装一套空环境 | 五镜像、指定提交源码和校验和可直接导入；目标生成自己的秘密和初始数据 |
| 升级既有实例 | 保留原状态目录、Compose project、秘密与卷，经维护、备份、迁移、版本/健康检查后开放入口 |
| 迁走已有业务数据 | 运输包不含数据库、原秘密或邮件；需单独的数据迁移与切换方案。现有 restore 只验证隔离库恢复，未提供已验收的线上切换流程 |
| 跨架构安装 | 当前正式包是 linux/arm64；AMD64 必须另行构建对应五镜像并验收，不能直接使用 ARM64 包 |

已归档验收包括 ARM64 同机独立安装、升级、浏览器、回退、隔离恢复及 GitLab 五门禁；没有把同机未 pull/build 写成真实断网或异机验收。真实 Ubuntu SSH/systemd/daemon/网络、AMD64 安装及生产服务尚未验收。状态见[交付进度](../progress.md)，版本事实见[发布证据](../evidence/release/README.md)。

## 技术参考

- [状态、配置与锁](../modules/operations.md)：状态目录地图、配置归属、保留规则。
- [可迁移发布包](../modules/portable-release.md)：清单、导出、导入校验和架构限制。
- [发布与恢复机制](../modules/release-operations.md)：schema、失败阶段、兼容证明和高级演练。
- [日志与请求追踪](../modules/observability.md)：字段含义、服务归属和诊断依据。
- [演示账号](../development/demo-accounts.md)：账号角色与密码文件位置。
