# GitLab 容器发布门禁：DinD 启动阻塞与待审批方案

对应 REQ-ENG-05。核验日期：2026-10-22 UTC（北京时间 2026-10-23）。本文记录真实诊断结果并提供可审核方案。本次文档任务没有创建 VM、Runner 或认证令牌，没有修改共享 Runner 权限，也没有把诊断流水线计作交付门禁通过。用户已选择先完成本机与可迁移交付：后续先准备可逆的本机独立 ARM64 VM，待隔离环境和配置具备可审阅结果后，再确认注册项目专用 Runner 这一持久访问授权。当前 VM 内 Runner 已安装但尚未注册或启动，方案尚未接入 GitLab。

## 已发生的三轮诊断

以下 job 日志由主代理在实际 GitLab 页面读取并转交；诊断分支为 `ycy/ci-container-check`，与交付分支分离，未创建 MR、未合入。

| 轮次 | 实际流水线 / job | 诊断提交 | 结果和能够确认的事实 |
| --- | --- | --- | --- |
| 1 | [683019](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683019) / [4478784](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4478784) | `865ddb260ab5a2df7aaa45d76b614d63c7cc72d4` | Pod 正常进入 Running，checkout、APK 安装完成；`ci-release.py` 的 90 次 Docker 就绪检查均失败。该版本丢弃连接 stderr，因此当时不能据此归因。 |
| 2 | [683043](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683043) / [4478921](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4478921) | `7db49c17b695e7c0e4d6505003b1fe8342213366` | 无 APK、无应用构建。TLS 目录正确展开为 `/builds/.studyroom-dind-4478921`，client/server 的 ca、cert、key 六文件均非空；hosts 将 docker 指向 127.0.0.1。TCP 2376 不可连接，18 次 TLS Docker 查询及最终 version 都无法连接 daemon，无 x509 错误。CLI 28.5.2、Compose 2.40.3 正确。 |
| 3 | [683047](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683047) / [4478933](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4478933) | `e16b853b7dc67103ad15999d16f09d81f5b65365` | 相同镜像、TLS 和连接配置；仅将官方 entrypoint 输出写入共享目录的 0600 日志。取得明确的 mount permission denied 错误，daemon 未开始监听。日志有界输出，未打印密钥、全量环境或应用凭据。 |

第三轮关键错误：

```text
mount: permission denied (are you root?)
Could not mount /sys/kernel/security.
AppArmor detection and --privileged mode might break.
```

日志末尾再次出现 mount permission denied，官方启动流程在 daemon 监听之前退出。调用前已从固定 DinD 镜像的 linux/amd64 配置和摘要校验过的镜像层核对：默认入口为 `dockerd-entrypoint.sh`，实际路径 `/usr/local/bin/dockerd-entrypoint.sh`，文件 SHA256 为 `a63266692950dec182182893c5d03d575e88d9246dc81987c9cfab21da1f29de`。诊断 wrapper 使用 `exec` 调用该文件，并保留唯一原参数 `--registry-mirror=https://docker.m.daocloud.io`；没有附加权限、调整 daemon 启动选项或关闭 TLS。

更早的 Runner 17.2.0 legacy 服务探针将 Pending 过早判错，是另一项已定位问题。删除可选 `HEALTHCHECK_TCP_PORT` 后，第一轮已实际越过 Pod 准备阶段。该修正没有使本轮 mount 错误消失，也不构成容器发布通过的证据。

完整 R4 [pipeline 683030](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683030) 的 backend、frontend、integration 均已通过，其中 integration 用时 15 分 56 秒。主代理主动取消了其 [container-release job 4478863](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4478863)，避免在同一已确认缺少 mount 能力的 Runner #5186 上重复安装等待；这个 job 的状态是取消，不能写成实际失败，更不能将完整流水线写成成功。实际 mount 失败证据来自诊断 job 4478933；[本机保存的原始错误截图](../../output/gitlab/dind-mount-permission-failure.png)只含启动错误，不含秘密，属于本机验收产物。

## 原因边界

**当前 `amd64-build` Kubernetes Runner 的服务执行上下文缺少官方 DinD 启动所需的 mount 能力。** 共享路径、变量展开、证书生成、主机别名和 CLI 版本均有实际证据；`nslookup` 的 NXDOMAIN 不推翻 `/etc/hosts` 别名，不能用它解释 mount 失败。

这些日志不足以单独判定 Runner 配置中的某个字段值，或区分 Kubernetes admission、capability、seccomp、AppArmor 等哪一层拒绝 mount。因此不声称已读取共享 Runner 的私有配置，也不直接把 `privileged=false` 当成已证实事实。能够确认的是：当前执行上下文不能启动这一固定的标准 DinD 服务；继续重试应用脚本、换证书路径或关闭 TLS 不能提供缺失的 mount 能力。

GitLab 的 [Kubernetes DinD TLS 配置](https://docs.gitlab.com/ci/docker/docker_in_docker/#docker-in-docker-with-tls-enabled-in-kubernetes-recommended)明确列出 Runner 端特权运行和共享证书卷。其 [Docker executor DinD 文档](https://docs.gitlab.com/runner/executors/docker/#use-docker-in-docker-with-privileged-mode)同样要求适合 DinD 的执行权限，并说明特权会扩大容器对所在主机的影响范围。因此本方案把该权限限定在专用 VM 内，不修改现有共享 Kubernetes Runner。

## 现有标签不能证明可用的替代执行环境

主代理已通过 GitLab UI 核对当前公布的 52 个 Runner，标签包括 `amd64-build`、`amd64` / `k8s-amd64`、`compiler`、`sim`、`macos-docker`、`arm64` 等；没有明确标明可供本项目使用的独立 DinD 特权 Runner。

这不等于证明其余 51 个 Runner 全部不支持 DinD，但标签本身没有提供权限、隔离方式或允许用途的证据。不能随机把本项目调度到 compiler、sim 等其他业务 Runner。若维护者能够提供已批准的专用 Runner、准确标签及隔离能力说明，可以先按下文验证；当前没有这项已确认资源。

## 本机方案：专用隔离 Linux ARM64 VM 中运行项目 Runner

默认方案适配当前 `darwin/arm64` Mac：在本机准备原生 `linux/arm64` 独立 VM，使用专用标签 `studyroom-release-arm64`，不采用 x86 仿真，也不要求用户另供远端服务器。结合本机 24 GiB 内存及现有 Docker 占用，已授权的 VM 配置为 4 vCPU、6 GiB RAM、20 GiB 数据盘加 10 GiB 根盘；只读检查生成的 Colima/Lima 配置与此一致，真实启动与隔离证据由 VM 准备任务另行记录。服务端已通过登录后的 Help 页面确认为 JiHu GitLab `17.8.7-jh`。独立 VM 准备任务已安装官方 Linux ARM64 Runner `17.8.5`（revision `c9164c8c`），二进制 SHA256 为 `d607c93f6cff44cfcf78bbed1e53b3300ffa498cc8a5b660afdb592c1e31274a`，版本执行与官方摘要核验通过；不能把服务端 patch 号误当成 Runner 发行版本。官方要求 [Runner 与 GitLab major.minor 匹配](https://docs.gitlab.com/runner/#gitlab-runner-versions)。本机已有 macOS Runner 18.8.0 不是 Linux 来宾程序，也不是本方案已安装的 Runner。

| 执行范围 | 真实架构 | 标签及证据边界 |
| --- | --- | --- |
| 本机专用 VM 的 container-release | `linux/arm64`，原生虚拟化 | 新专用标签 `studyroom-release-arm64`；构建镜像与 manifest 必须记录 ARM64，不能宣称 AMD64 容器验收 |
| 现有普通 backend / frontend / integration | 保持现有 AMD64 Runner | 不因新增 ARM64 容器任务改动其标签；完整流水线需分别记录各任务平台 |
| 以后若提供已获准的专用 Linux AMD64 Runner | `linux/amd64` | 另用 `studyroom-release-amd64`；需独立验证，不能与 ARM64 方案共用架构标签 |

已只读查询 Docker 官方 registry 的固定 OCI 索引，现有两个顶层摘要均同时包含 Linux ARM64 和 AMD64。下方模板保留顶层摘要，由原生 ARM64 Docker Engine 选择 ARM64 清单；启用时还要核对实际选中的子摘要。不得使用全局 `DOCKER_DEFAULT_PLATFORM=linux/amd64` 将本机任务改成仿真。

| 镜像 | Linux ARM64/v8 子摘要 | Linux AMD64 子摘要 |
| --- | --- | --- |
| Docker CLI 28.5.2 | `sha256:3f531683b548300f6d21ac352553215c67713f31105cd23e09cd2a4aa25428a8` | `sha256:cd58b396e427d1ee8cddcc3f3b7e8d8c2ba45755c5dd5b821b6e615e1ccf4586` |
| Docker DinD 28.5.2 | `sha256:145184796e8717376e73eaf29e16ede8ede2fd75e947a3fae7c05298e5e20d28` | `sha256:9a06753d2401cd049b34cd27dbbc3e0db717d4c1db7bc7f2efad1c187e00bf5a` |

VM 只承担此项目容器验收；显式关闭 VM 工具默认目录共享，不挂载 Mac HOME、用户源码、物理宿主目录或物理宿主 Docker socket，不改当前 Mac 默认 Docker context。Runner 原生安装在 VM 来宾内，通过来宾自己的 Docker Engine 创建 job/service。该控制面访问不会作为 `/var/run/docker.sock` volume 传入 job、DinD 或浏览器；应用构建仍使用每个 job 的私有 DinD daemon。VM 内不配置生产服务器凭据。

GitLab 项目侧设置应单独审核，因为这些不是仅靠 `config.toml` 就能落实的设置：

| 设置 | 拟定值 |
| --- | --- |
| Runner 类型 / 唯一项目 | project runner / `yangchunyu/campus-study-room`，项目 ID `11753` |
| Lock to current projects | 开启，只关联该项目 |
| 专用标签 | 本机方案 `studyroom-release-arm64`；远端专用 AMD64 方案才使用 `studyroom-release-amd64` |
| Run untagged jobs | 关闭 |
| Protected / access_level | 开启 / `ref_protected`；项目锁定与标签本身不能阻止同项目不受信分支运行 |
| 可运行引用 | 保持 `main` 现有保护；临时精确保护 `ycy/campus-platform`、`ycy/ci-container-check`，仅可信维护者可写，禁止 force push，不使用 `ycy/*` 通配规则 |
| 并发 | 1 |
| 初始状态 | 暂停；完成配置和范围核验后才启用 |

项目锁定依据 [GitLab 项目 Runner 范围设置](https://docs.gitlab.com/ci/runners/runners_scope/#prevent-a-project-runner-from-being-enabled-for-other-projects)，标签与禁止 untagged 依据 [Runner 调度设置](https://docs.gitlab.com/ci/runners/configure_runners/#control-jobs-that-a-runner-can-run)。单项目和专用标签不能代替可信代码边界：`privileged = true` 同时作用于 build 容器与 service，特权 job 仍有控制来宾 VM 的能力。来宾内 0600 Runner 配置不能隔离已经获得来宾 root 的 job；VM 只接收明确授权的可信代码。无 Mac 文件挂载也不等于已经实施到 Mac 的网络隔离，应分别核验默认网关、端口转发和 SSH agent。

以下是不含认证令牌的配置片段。获准注册后，令牌仅由受控注册流程写入来宾本地受保护配置，不能提交到仓库或打印到日志。该片段故意不包含 token，不能直接作为已注册 Runner 启动。

```toml
concurrent = 1
check_interval = 3

[[runners]]
  name = "studyroom-isolated-release-arm64"
  url = "https://code.agibot.com/"
  executor = "docker"
  limit = 1
  builds_dir = "/builds"

  [runners.docker]
    image = "m.daocloud.io/docker.io/library/docker:28.5.2-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d"
    privileged = true
    disable_cache = false
    volumes = ["/builds"]
    pull_policy = ["always"]
    allowed_pull_policies = ["always"]
    allowed_images = ["m.daocloud.io/docker.io/library/docker:28.5.2-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d"]
    allowed_services = ["m.daocloud.io/docker.io/library/docker:28.5.2-dind@sha256:2a232a42256f70d78e3cc5d2b5d6b3276710a0de0596c145f627ecfae90282ac"]
```

`volumes = ["/builds"]` 是 Runner 管理的 Docker volume，不是 `<宿主路径>:<容器路径>` 绑定；未设置 `cache_dir`，也不添加宿主目录映射。它位于专用 VM 的 Docker 存储内。GitLab 说明 job 与 service 共享 [builds 目录](https://docs.gitlab.com/ci/services/#shared-builds-directory)，此处保留每 job 的 `.studyroom-dind-$CI_JOB_ID` TLS 目录以及项目 checkout，以兼容现有证书和嵌套容器挂载路径。动态卷可能跨该项目 job 留存，参见 [Docker executor 存储规则](https://docs.gitlab.com/runner/executors/docker/#persistent-storage)；应由 VM 所有者为该专用环境制定保留和退役策略，不能套用到现有业务卷或擅自删除数据。

本阶段先准备本机隔离环境及可审阅配置；注册项目 Runner、写入认证令牌以及启用持续接收 GitLab job 的操作，需用户确认持久访问授权后实施。本次文档任务没有执行上述操作。不得通过给现有共享 Runner 加特权、关闭 TLS、挂宿主 socket 或允许门禁失败来替代专用环境。

## GitLab 17.8.7 的受保护分支流水线

已核对精确版本，而非套用最新文档：[GitLab v17.8.7 Runner 文档](https://github.com/gitlabhq/gitlabhq/blob/v17.8.7/doc/ci/runners/configure_runners.md#L248)明确受保护 Runner 不能运行 MR pipeline；[同版本 MR 文档](https://github.com/gitlabhq/gitlabhq/blob/v17.8.7/doc/ci/pipelines/merge_request_pipelines.md#L25)及 [pipeline 的受保护判断](https://github.com/gitlabhq/gitlabhq/blob/v17.8.7/app/models/ci/pipeline.rb#L917)一致。当前服务端没有 18.1 的 MR 受保护资源开关。不能通过关闭 Runner Protected 让 MR 获得 VM 执行权限。

待提交的 `.gitlab-ci.yml` 保留五个 job 及全部 `needs`，仅调整 workflow 与 container-release 标签：项目 ID 必须为 `11753`，确切分支必须为 `main`、`ycy/campus-platform`、`ycy/ci-container-check` 之一，且服务端报告分支受保护，才优先允许 push/web 完整 branch pipeline。它仍执行 backend、frontend、integration、container-release、package 的完整链路；已有 MR 不再将这条可信 branch pipeline 抑制。

仅对同项目、两端受保护、目标为 `main`、来源为两条确切临时分支的 MR 去重，不再创建无法取得受保护 Runner 的重复 MR pipeline。`CI_MERGE_REQUEST_SOURCE_BRANCH_PROTECTED` 在 [17.8.7 预定义变量文档](https://github.com/gitlabhq/gitlabhq/blob/v17.8.7/doc/ci/variables/predefined_variables.md#L191)中已存在。普通 MR、fork MR、未受保护源分支保留原规则和全部五个 job；其 container-release 无权取得受保护 Runner 时保持未通过，不能仅凭 backend/integration 成功合入。标签沿用原工作流，但最终发布 tag 还必须单独精确保护才能使用该 Runner。

启用前，维护者保存现有 `main` 保护配置，只为两条临时分支新增精确规则，限制允许 push/merge 的可信维护者、禁止 force push，并核对无更宽松的重叠保护规则。完成后先等两条分支的运行任务结束、暂停 Runner 并核对队列，再只撤销此次新增的两个精确保护条目；`main` 保护不变。临时保护撤销后，MR 去重条件不再匹配，普通 MR 完整门禁恢复。GitLab 必须继续要求 pipeline 成功且 skipped 不算成功，实际验证 MR 页面引用最新提交的完整 branch pipeline，不能把诊断分支结果替代交付分支结果。

新增 `tests/ops/test_ci_workflow.py` 直接读取真实规则，覆盖同项目/外部项目、确切/相似分支、保护撤销、push/web/其他来源、MR 去重边界、tag 原语义与五 job 依赖链；它是本地配置回归，不能冒充 GitLab Runner 已注册或远端 pipeline 已通过。

## 获准后的验证顺序

1. 先准备本机可逆的独立 Linux ARM64 VM，核对架构、无 Mac HOME / 源码 / socket 映射、未改默认 Docker context，保存无令牌的配置摘要。用户确认持久访问授权后，才注册并核对服务端兼容 Runner 版本、项目锁定、禁止 untagged、可信引用和 `studyroom-release-arm64` 唯一标签。
2. 仅在精确受保护的诊断分支选择已批准的专用标签，保留相同 CLI/DinD 摘要及 TLS 设置。先验证共享目录、证书非空、2376 监听、经过 TLS 验证的 `docker info`，再确认 daemon 28.5.2 / Compose 2.40.3 以及实际 `linux/arm64` 平台和镜像子摘要。认证失败必须保持非零退出。
3. 使用该 job 的新 checkout 做最小嵌套容器路径探针：只读取 checkout 中一个已知源码文件，确认 DinD 所见绝对路径与客户端一致。探针不挂宿主目录、不输出秘密、不进入业务环境。
4. 在独立 job 私有 daemon 中执行原 `scripts/ci-release.py`，真实构建三镜像、记录实际 ARM64 镜像与 manifest、创建隔离数据库、迁移/种子、正式 deploy、健康检查及三服务版本核验；不把连接探针替代这一环节。
5. 执行原真实浏览器链路和报告隐私检查，要求非零测试数、无跳过、JUnit 与镜像身份一致。保存安全制品；失败时不把 package 或合入门禁放行。
6. 获准后才接入交付分支预置的 `studyroom-release-arm64` 标签；保持完整 backend、frontend、integration、container-release、package 依赖链，对准确交付提交运行完整受保护 branch pipeline，并核对 MR 最新提交门禁。仅该完整结果可以关闭 CI 容器发布验收项，诊断分支成功不计入。

如果提供的是现有专用 Runner，同样先取得允许使用和隔离能力说明，再从第 2 步验证；不能仅凭标签绕过第 1 步的边界核对。

## 本机只读盘点与当前状态

`command -v gitlab-runner` 返回 `/opt/homebrew/bin/gitlab-runner`，`gitlab-runner --version` 返回 18.8.0、revision `9ffb4aa0`、`darwin/arm64`。方案盘点时 `colima` 和 `limactl` 均未在当前 PATH 找到，尚无已核验可用的 Colima/Lima 安装或本机专用 Linux ARM64 VM；这不排除其他位置有未接入 PATH 的文件。本次文档任务没有安装、注册、启动这些组件，也没有读取本机 Runner 令牌配置。随后独立代理已按授权准备 Colima 0.10.3 / Lima 2.2.1 隔离环境，并验证来宾 Runner 17.8.5 二进制与版本；详细核验保存在私有 `.local/ci-runner-preparation/runner-service-verification.json`。来宾配置目录 root0700、只有 root0600 模板，没有真实 `config.toml`、`.runner_system_id` 或令牌；systemd 单元校验通过但仍 disabled/inactive，无 Runner 进程，未 register/start/enable。这是安装就绪，不是已注册 Runner 或远端执行成功。

## 本机准备及最小探针已完成，尚未注册 GitLab Runner

独立准备任务已完成上述 ARM64 VM 与 Runner 17.8.5 安装，Runner 仍 disabled/inactive、无真实配置或认证令牌、无运行进程。安装核验见本机私有 [runner-service-verification.json](../../.local/ci-runner-preparation/runner-service-verification.json)；这些文件不随公共源码制品分发。

2026-10-22 17:41:26–17:41:36 UTC 的 `probe-20261022T174126Z-9c1e0928` 在该专用 VM 内通过最小 DinD 验证：外层 Docker 29.5.2，内层 CLI/daemon 28.5.2、Compose 2.40.3、实际 Linux ARM64；固定 ARM64 子摘要与上表相符。TLS 2376 的 info/version 成功，六个证书文件非空但未打印内容；移去有效客户端证书路径后连接非零拒绝。共享 builds named volume 的无敏感 marker 经内层容器以同一绝对路径只读绑定后读回一致，没有复制实际仓库。完整范围与安全摘要见本机私有 [探针 README](../../.local/ci-dind-probe/README.md) 和 [summary.json](../../.local/ci-dind-probe/summary.json)。

首次 `probe-20261022T173959Z-a42ffa1a` 的失败完整保留：TLS 已通过，采集器误把外层 Docker 29 containerd 的 OCI 索引 ID 用作内层 Docker 28 load 后的 config ID，导致 No such image；修正为实际 load 返回 ID 后第二轮通过。初轮 CLI 的等待循环没有及时响应 SIGTERM，正常 stop 的超时处理结束了它；第二轮增加明确 TERM trap 后正常停止。没有更换镜像、放宽 TLS 或跳过路径断言。

探针仅 DinD 服务使用 privileged，CLI 与 marker 容器不使用特权；没有挂载 Mac 目录或宿主 socket、没有 published port，默认 Docker context 前后仍为 desktop-linux。收尾仅停止精确归属的探针容器，运行中探针容器为 0，4 个卷及 2 个网络保留。该结果验证本机 VM 的 DinD/TLS/嵌套路径能力，不验证 Runner 注册、GitLab 调度、真实 checkout/应用构建或 MR 最新 SHA 门禁；远端完整发布项仍待授权后运行。

本地应用与可迁移交付的既有验收继续独立成立；当前远端容器发布门禁尚未通过：诊断任务失败，完整 R4 的容器任务已取消，必须在获准且可用的基础设施上完成真实运行。本轮只准备上述 CI 配置、配置回归和运维文档，未推送、注册 Runner、修改 GitLab 设置或共享 Runner 权限；也未把 Runner 的注册与真实执行写成已完成。
