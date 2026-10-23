# Ubuntu 运行环境引导

[安装导航](../operations/installation.md) · [运维手册](../operations/runbook.md) · [发布包机制](portable-release.md)

对应技术方案 §11.3、REQ-ENG-03/04/07。入口为 `./scripts/bootstrap <env> <inventory>`，用于准备 Ubuntu 的固定 Docker 运行时、部署账号、状态目录与配置；**它不安装应用**。完成后按本页交接步骤导入完整包。当前支持 local/dev/test 与 Mailpit，prod 在连接服务器、安装组件前拒绝；不依赖 GitLab registry。

真实 Ubuntu SSH/systemd/daemon/网络分支尚未验收，AMD64 只核对过包可用性。以下是现有实现的执行路径，不能把文档存在或语法校验通过写成实际服务器安装成功。

## 输入与执行

| 位置 | 执行前确认 |
| --- | --- |
| 控制端 | 仓库源码、Python 3.12+、venv、OpenSSH；可访问 PyPI 和目标 SSH |
| 受管主机 | Ubuntu 24.04 LTS、Python 3.12、systemd、至少 4 vCPU / 8 GiB；可访问 Ubuntu/Docker 官方软件源 |
| SSH 身份 | 已获授权账号和免交互 sudo、私钥、从可信渠道核对的完整主机公钥 |
| 部署输入 | 环境、独立 project、回环端口、部署账号及状态/发布/备份绝对路径 |
| 应用包 | 与目标 Docker daemon 相同架构的完整包；当前正式包只有 ARM64 |

实现接受 ARM64、AMD64。控制端首次运行在用户缓存创建隔离虚拟环境，安装 `infra/ansible/requirements.txt` 精确版本，不更改系统 Python。机器类型的完整前置条件对照见[安装指南](../operations/installation.md#先检查机器与工具)。

**第一步：准备输入。** 复制 [inventory.example.json](../../infra/ansible/inventory.example.json) 到仓库外的私有位置。填写服务器地址、SSH 用户/端口、私钥绝对路径、**已通过可信渠道核验的完整主机公钥**（例如服务器控制台提供的 `/etc/ssh/ssh_host_ed25519_key.pub` 前两列）；不能把未经核验的 ssh-keyscan 输出当作固定信任。私钥文件必须为 0600/0400，不上传或写入仓库。示例中的保留地址和占位主机密钥必须替换。

**第二步：校验输入与剧本。** 从源码根目录执行，环境须与 inventory 一致：

```sh
./scripts/bootstrap test /absolute/private/test-inventory.json --validate
./scripts/bootstrap test /absolute/private/test-inventory.json --syntax-check
```

`--validate` 仅校验输入，不下载 Ansible、不连接服务器；`--syntax-check` 校验剧本；`--check` 是 Ansible 预测模式，均不能作为安装成功证据。真实执行固定 `StrictHostKeyChecking=yes`，使用临时独立 `known_hosts`，禁用自动添加/更新主机密钥。JSON 不接受额外 Ansible 变量，不能通过 inventory 关闭校验。`project_name` 必须为 `studyroom-<env>` 或添加专用后缀，防止误用其他环境的 Compose 名称。

**第三步：实际安装运行时。** 两项校验通过、目标与身份确认后执行：

```sh
./scripts/bootstrap test /absolute/private/test-inventory.json
```

预期 Ansible 返回无 failed/unreachable，生成下文的 runtime/state 结果，Docker systemd 和实际版本/网络检查通过。保留本次输出与明确验证范围。失败时先按末尾故障表处理，不关闭 SSH/TLS 校验继续。

在 Ubuntu 本机执行时设置 `connection: "local"`、`host: "localhost"`，仍需显式 `sudo: true`。当前入口拒绝公网绑定，Web/Mailpit 保持 127.0.0.1；本地 HTTP 交付不等于已配置 HTTPS 的生产部署。

## 固定组件与目标状态

精确版本记在 `infra/ansible/runtime-lock.json`，2026-10-22 已核对 Docker 官方 Noble ARM64、AMD64 包索引：

| 组件 | 固定版本 |
| --- | --- |
| Docker Engine / CLI | `5:28.5.2-1~ubuntu.24.04~noble` |
| containerd.io | `2.1.5-1~ubuntu.24.04~noble` |
| Buildx | `0.29.1-1~ubuntu.24.04~noble` |
| Compose 插件 | `2.40.3-1~ubuntu.24.04~noble` |
| 控制端 ansible-core | `2.21.5` |

使用官方 HTTPS apt 源、固定 SHA-256 的官方签名密钥、包版本 pin 与 dpkg hold。Ubuntu 基础依赖继续接收发行版安全维护。发现冲突运行时或已装 Docker 版本不同即失败，不自动卸载、升级或降级已有运行时。依据：[Docker Ubuntu 安装文档](https://docs.docker.com/engine/install/ubuntu/)、[ARM64 包索引](https://download.docker.com/linux/ubuntu/dists/noble/stable/binary-arm64/Packages.gz)、[AMD64 包索引](https://download.docker.com/linux/ubuntu/dists/noble/stable/binary-amd64/Packages.gz)、[Ansible 安装说明](https://docs.ansible.com/projects/ansible/latest/installation_guide/intro_installation.html)。

引导创建独立锁定账号（默认 `studyroom`，禁止交互登录），加入 Docker 组以执行后续发布。Docker 组拥有等同主机 root 的容器管理能力，因此只用于专门部署账号，不复用个人登录账号。已有同名账号的 home/shell 不匹配时失败。账号、目录、网络的创建均幂等：

- 状态目录默认 `/srv/campus-study-room/test`，应用源码/发布包目录 `/srv/campus-study-room/releases`，分别由部署账号持有且模式 `0700`。
- 备份目录必须为状态目录下 `backups`，与现有发布脚本一致；这是本机备份路径，仍需运维另行复制到独立存储，不能视为异机灾备。
- 首次初始化复用 `scripts/init-env`，生成七个随机秘密文件、环境配置，再校验长度、编码、所有权及私有权限。秘密值不会出现在输出中。
- 已有配置与 inventory 不一致、秘密缺失或格式/权限不正确时失败；保留已有内容，要求恢复原秘密/匹配配置。不会悄悄生成替代数据库密码或签到密钥。
- 启用并验证 Docker systemd 服务的实际版本；创建该 Compose project 的 `private`（internal）与 `access` 网络，校验网络属性及 Compose 归属标签。不会删除或复用其他项目的网络/数据卷。

完成后状态目录中的 `bootstrap-runtime.json` 记录固定组件及验证范围，`bootstrap-state.json` 记录无秘密的配置校验结果。真实主机执行须包含 daemon/网络成功；`daemonAndNetworksVerified: false` 仅代表有限容器校验，不能继续声称真实主机环境已验收。

## 完成后导入应用

按[离线安装前两步](../operations/installation.md#从离线包安装新环境)校验完整运输包并解出新源码。源/包目录位于 inventory 指定的 release_directory，需由部署账号可读并持有其应维护的目录；因为该账号禁止交互登录，以获授权运维账号执行 `sudo -u studyroom`。不要使用 `sudo -i` 尝试给锁定账号开登录 shell。

以下在**目标 Ubuntu 主机**执行，示例路径换成已验证的真实源码/包；环境和状态路径必须沿用 inventory，不能重新生成默认状态：

```sh
sudo -u studyroom env STUDYROOM_STATE_DIR=/srv/campus-study-room/test \
  /srv/campus-study-room/releases/verified-source/scripts/import-release \
  test /srv/campus-study-room/releases/verified-bundle
```

`studyroom` 是默认部署账号；自定义账号按 inventory 替换。成功输出已部署的源码路径；后续按[巡检](../operations/runbook.md#检查健康与版本)核对三版本、容器及入口。应用迁移、维护、备份和失败处理由部署器负责。若导入失败，保留运行时和状态，不能为了重试卸载 Docker 或重建秘密。

从运维客户端通过已经核验主机身份的 SSH 连接转发端口，例如默认 test：

```sh
ssh -N -L 18082:127.0.0.1:18082 -L 18027:127.0.0.1:18027 user@verified-server
```

替换实际用户、服务器及配置过的 SSH/应用端口，复用已核验的身份和主机密钥，不关闭 StrictHostKeyChecking。保持隧道运行，浏览器访问本机 18082/18027；本地端口已占用时选择不同转发端口。登录账号见[演示账号](../development/demo-accounts.md)，不要传出密码文件。

## 常见拒绝与失败

| 现象 | 检查与处理 |
| --- | --- |
| validate 拒绝字段/路径/环境 | 对照 inventory 示例，去掉额外变量，确认环境与目录/端口约束；validate 不会连接目标 |
| 主机密钥不匹配或私钥权限错误 | 通过原可信渠道核对目标身份，修正私钥权限；不自动接受新 host key |
| sudo 或软件源不可用 | 确认授权账号的免交互 sudo、官方源网络和证书；不修改校验策略 |
| 已有 Docker 版本/运行时冲突 | 记录实际版本和占用，另行制定变更方案；脚本拒绝自动卸载、升降级 |
| 同名部署账号属性不匹配 | 核对账号归属、home/shell；不复用或改造未知账号 |
| 已有配置漂移、秘密缺失或权限不符 | 找回原配置/秘密并核对归属；不生成替代数据库密码/签到密钥 |
| 结果只标记 container_packages_paths_users_only | 只完成有限容器校验，真实 SSH/systemd/daemon/项目网络仍待执行 |

## 自动验证与边界

`python3 -m unittest discover -s tests/ops -p test_bootstrap.py -v` 覆盖拒绝 prod/未知变量、路径/环境/端口、固定 SSH 主机密钥及私钥权限、错误容器模式、秘密字节与时间戳保持、配置漂移、缺失秘密、重复配置、符号链接和秘密权限。`ansible-core 2.21.5` 的 `--syntax-check` 已通过。

隔离 Ubuntu 24.04 ARM64 容器用于真实安装软件包并检查账号、目录、配置和第二次幂等执行。该模式必须同时显式传入 `--container-validation`、使用 local 连接且目标存在 `/.dockerenv`；它跳过 systemd、Docker daemon 与网络操作，并在结果文件记录 `daemonAndNetworksVerified: false`。容器不挂载宿主 Docker socket、不使用 privileged，不影响当前应用服务。真实服务器 SSH、systemd、网络验收须在有目标主机与可信 host key 时执行，不能用容器或语法检查替代。

以下沿用重构前文档基线 `d397d05` 中的模块记录；2026-10-22 的 ARM64 Ubuntu 容器安装/幂等原始产物位于本机忽略目录 `output/bootstrap/`，普通 clone 不包含这些产物：首轮 `ok=28 changed=14 failed=0`，修正占位包状态判断后复测 `ok=28 changed=0 failed=0`；原配置与七秘密摘要/mtime 保持，CLI/Compose 精确版本符合基线。该容器仅 1 CPU / 1 GiB，验证范围明确为 packages/paths/users，未挂宿主 socket，不等同应用主机容量要求。

当时 bootstrap 单元回归 14/14、全仓 ops 51/51 是原版本快照，不是本次文档更新重新运行。真实 Ubuntu 主机 SSH、systemd/daemon、项目网络，以及 AMD64 安装仍未实测。
