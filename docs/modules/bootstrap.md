# Ubuntu 运行环境引导

对应技术方案 §11.3、M0 可迁移交付。入口为 `./scripts/bootstrap <env> <inventory>`。本轮按 ADR 0002 支持 `local/dev/test` 与本机 Mailpit，`prod` 在连接服务器、安装组件前明确失败。引导完成后，再通过 [可迁移发布包](portable-release.md) 安装应用；此入口不依赖 GitLab 镜像仓库。

## 输入与执行

控制端需要 Python 3.12+、`venv`、OpenSSH 客户端及到 PyPI、Ubuntu/Docker 官方软件源的网络；目标主机需要 Ubuntu 24.04 LTS、Python 3.12、systemd、至少 4 vCPU / 8 GiB，以及当前 SSH 账号已获授权的免交互 sudo。目标架构支持 ARM64、AMD64；应用离线包必须与目标架构一致。控制端首次运行会在用户缓存目录创建隔离虚拟环境，安装 `infra/ansible/requirements.txt` 的精确版本，不更改系统 Python。

复制 `infra/ansible/inventory.example.json` 到仓库外的私有位置。填写服务器地址、SSH 用户/端口、私钥绝对路径、**已通过可信渠道核验的完整主机公钥**（例如服务器控制台提供的 `/etc/ssh/ssh_host_ed25519_key.pub` 前两列）；不能把未经核验的 `ssh-keyscan` 输出当作固定信任。私钥文件必须为 `0600/0400`，不会上传或写入仓库。

```sh
./scripts/bootstrap test /absolute/private/test-inventory.json --validate
./scripts/bootstrap test /absolute/private/test-inventory.json --syntax-check
./scripts/bootstrap test /absolute/private/test-inventory.json
```

`--validate` 仅校验输入，不下载 Ansible、不连接服务器；`--syntax-check` 校验剧本；`--check` 是 Ansible 预测模式，均不能作为安装成功证据。真实执行固定 `StrictHostKeyChecking=yes`，使用临时独立 `known_hosts`，禁用自动添加/更新主机密钥。JSON 不接受额外 Ansible 变量，不能通过 inventory 关闭校验。`project_name` 必须为 `studyroom-<env>` 或添加专用后缀，防止误用其他环境的 Compose 名称。

在 Ubuntu 本机执行时设置 `connection: "local"`、`host: "localhost"`，仍需显式 `sudo: true`。远程服务器的 Web/Mailpit 绑定 `127.0.0.1`，通过 SSH 转发访问，例如默认 test 环境使用 `ssh -L 18082:127.0.0.1:18082 -L 18027:127.0.0.1:18027 <已核验的服务器>`。当前入口拒绝公网绑定，不将 HTTP 本地交付当作已配置 HTTPS 的生产部署。

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

完成后状态目录中的 `bootstrap-runtime.json` 记录固定组件及验证范围，`bootstrap-state.json` 记录无秘密的配置校验结果。执行应用导入时，在部署账号下使用同一个 `STUDYROOM_STATE_DIR`，例如 `sudo -u studyroom env STUDYROOM_STATE_DIR=/srv/campus-study-room/test /srv/campus-study-room/releases/<源码>/scripts/import-release test <发布包>`。应用版本、迁移、维护门禁与回退由原有发布入口负责。

## 自动验证与边界

`python3 -m unittest discover -s tests/ops -p test_bootstrap.py -v` 覆盖拒绝 prod/未知变量、路径/环境/端口、固定 SSH 主机密钥及私钥权限、错误容器模式、秘密字节与时间戳保持、配置漂移、缺失秘密、重复配置、符号链接和秘密权限。`ansible-core 2.21.5` 的 `--syntax-check` 已通过。

隔离 Ubuntu 24.04 ARM64 容器用于真实安装软件包并检查账号、目录、配置和第二次幂等执行。该模式必须同时显式传入 `--container-validation`、使用 local 连接且目标存在 `/.dockerenv`；它跳过 systemd、Docker daemon 与网络操作，并在结果文件记录 `daemonAndNetworksVerified: false`。容器不挂载宿主 Docker socket、不使用 privileged，不影响当前应用服务。真实服务器 SSH、systemd、网络验收须在有目标主机与可信 host key 时执行，不能用容器或语法检查替代。

2026-10-22 实测使用 `ubuntu@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac` 的 ARM64 镜像，独立容器限制为 1 vCPU / 1 GiB，仅安装验证；未挂载 Docker socket。结果：

- 首轮真实安装：`ok=28 changed=14 unreachable=0 failed=0`，上述五个组件逐一通过 `dpkg-query` 精确版本比对；Docker CLI `28.5.2`、Compose `2.40.3`。
- 复测发现 dpkg 会为 Docker 提供的 `runc/containerd` 等虚拟包保留 `not-installed` 条目，已修正为仅检查 `installed` 状态，不把占位条目误判为冲突。
- 修正后重复执行：`ok=28 changed=0 unreachable=0 failed=0`；`compose.env` 与全部七个秘密的字节摘要和 mtime 均完全保持。
- `studyroom` shell 为 `/usr/sbin/nologin`，状态/秘密/备份/发布目录均为 `0700`。结果文件明确标记 `container_packages_paths_users_only` 与 `daemonAndNetworksVerified: false`。
- 单元回归 14/14；当时全仓 ops 测试 51/51。详细运行日志在本机忽略目录 `output/bootstrap/first-run.log`、`second-run-fixed.log`、`verification.json`，不包含秘密值。

真实 Ubuntu 主机的 SSH、systemd/daemon 和项目网络分支尚未执行，本记录不构成该分支验收。AMD64 官方包版本已核对，但安装演练仅在 ARM64 完成。
