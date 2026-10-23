# GitLab 验收、合并与发布（历史归档）

> 本页保留 2026-10-23 迁移前的 GitLab 配置与验收流程。新开发与发布使用 [GitHub 工作流](github.md)；下文“当前”均指归档时刻，不代表现在应在 GitLab 执行或扩大旧 Runner 授权。

[运维导航](README.md) · [贡献流程](../contributing.md) · [发布包参考](../modules/portable-release.md)

项目为 [yangchunyu / campus-study-room](https://code.agibot.com/yangchunyu/campus-study-room)，ID `11753`。CI 定义在 [.gitlab-ci.yml](../../.gitlab-ci.yml)。当前实例未启用容器镜像仓库；CI 完整通过、源代码制品归档和五镜像运输包是不同交付步骤。

## 五个必须通过的门禁

| job | 前置 | 检查与输出 |
| --- | --- | --- |
| backend | 无 | Maven verify；后端 JAR、JUnit，保留 14 天 |
| frontend | 无 | 客户端生成漂移、类型、单测、构建；dist，保留 14 天 |
| integration | backend + frontend | 真实 PostgreSQL、两应用 HTTP、Mailpit/SMTP、数据库/契约与响应丢失重放、ops 回归；输出保留 14 天 |
| container-release | 前三项 | 独立 DinD 构建三镜像，正式 deploy、健康/版本/内部路径及真实浏览器；清单/日志/浏览器制品保留 90 天 |
| package | 前四项 | 当前完整提交的源码 tar、SHA256SUMS、build.json；保留 90 天 |

integration 的真实数据库套件必须有非零且无 skip 的 JUnit 结果。package 不只等待 integration，还必须等容器发布与浏览器通过。skipped 或仅前三项成功不满足合入条件。

CI 环境名使用 `CI_PIPELINE_IID`，运行时身份使用完整 pipeline/job 标识。integration 与 container-release 的 `resource_group` 分别串行化同类任务；它们的 test 环境只在 job 寿命内存在，不是常驻服务器。容器任务结束时正常停止应用，不把临时 DinD 构建宣称为远程 registry 已发布。

## Runner 与可信引用

以下为已授权并归档的配置边界，不是授予新增引用或其他项目权限的操作说明：

- 普通 backend/frontend/integration 使用 `amd64` 标签；container-release 使用项目专用 Runner `5666` 和 `studyroom-release-arm64` 标签。
- 专用 Runner 锁定项目、Protected 开启、拒绝 untagged、并发 1；标签本身不是授权边界。
- 确切受保护分支为 `main`、`ycy/campus-platform`、`ycy/ci-container-check`；正式 `v0.1.0` 标签已精确保护。没有 `ycy/*` 或任意标签的广泛授权。
- 独立 ARM64 Linux VM 不共享 Mac HOME、源码、宿主 Docker socket 或 SSH agent。DinD 使用验证 TLS 的 2376，固定 Docker 28.5.2 / Compose 2.40.3；证书在每 job 的 checkout 之外，避免被清理进程移除。
- privileged build 可控制来宾系统，Runner 配置的 0600 不是针对特权 job 的隔离。可信代码审查和引用保护仍是必要边界，不修改共享 Runner 权限以解除失败。

该 GitLab 版本的 protected Runner 路径使用完整 branch pipeline：项目内上述确切受保护分支的 push/web 事件会创建五 job；只有同项目、两端受保护、目标 main 且源为上述两工作分支时，抑制重复 MR pipeline。普通 MR/fork 保留规则，但未获受保护 Runner 的引用可能等待，不能跳过容器门禁或设置 allow_failure。新分支/新标签所需保护与执行授权必须先按项目规则解决，现有文档不扩大授权范围。

注册、实际保护和 VM 边界见[Runner 记录](../evidence/gitlab-runner-registration.md)与[隔离方案](../evidence/gitlab-dind-startup.md)；不要在仓库、文档或 job 日志保存令牌、VM 私密配置、会话或 TLS 私钥。

## 合入 main 的步骤

1. 在工作分支完成实现和[本地检查](../development/testing.md)，按[贡献流程](../contributing.md)保留逻辑提交。
2. 确认目标 main 没有新的分叉；需要时先将特性分支 rebase 到最新 main，再对新提交重新运行完整门禁。不要重写 main 已有历史。受保护工作分支禁止 force push 时，不能临时解除保护，应通过维护者允许的分支策略继续。
3. 在 MR 核对 pipeline 对应的是源分支**最新提交**，五项全部成功，而不是旧提交成功或部分任务通过。
4. 以 Fast-forward merge 合入。项目 squash 策略为 Allow、默认不勾选；保留每个逻辑 commit，不生成额外 merge commit。Pipelines must succeed 开启，Skipped pipelines are considered successful 关闭。
5. 核对 main 指向预期提交及对应完整 pipeline，记录合入结果。不能通过直接推 main 或修改门禁绕开失败。

仓库中的历史 MR/流水线记录是已发生事实，不是后续提交的通行证。基线 H=`d397d05124ae490d6ada762296909b910e82a7e0` 的 main [684208](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/684208)已通过五项；当前新改动仍需自己的完整验证。

## 准备发行版

正式发布将以下身份串在一起：已通过门禁的完整提交 → 三应用固定镜像/manifest → 同平台五镜像包 → 受保护标签及其完整 pipeline → Release 的制品链接与摘要。构建/导出步骤见[发布包参考](../modules/portable-release.md#构建与导出)，目标安装和回退见[运维手册](runbook.md)。

`package` job 的源码 tar 不含运行镜像，不能直接当离线安装包。container-release 的 90 天制品用于审计该次验收，也不自动成为长期发行仓库。正式运输包须包含源码、五镜像、清单、安装说明和内外校验和，并由部署方保留；Release 应关联同一提交的有效制品及可信摘要。后续标签需事先精确保护，不能将当前 v0.1.0 的保护视作所有未来标签已授权配置。

v0.1.0 运行源码 S=`10799003cd38a28629dc5ea39835c8313b1e9d79` 的 feature `683506`、main `683689`、tag `683743` 已各自完整五 job 通过，正式 [Release](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)与[发行记录](../evidence/gitlab-release-v0.1.0.md)保留其准确范围。后续纯文档 HEAD 不改变运行镜像的 S，也不重标这些旧结果。

## 流水线失败或等待

| 状态 | 排查顺序 | 继续条件 |
| --- | --- | --- |
| pending，无可用 Runner | 确切引用是否受保护、标签是否匹配、Runner 是否在线/忙、project 是否匹配 | 在既有授权边界内恢复执行能力；不放宽 protected/untagged |
| runner system failure，项目脚本未运行 | 查看 job 启动日志、DinD/服务阶段；区别于业务测试失败 | 修复运行条件后重试同一候选，保留原失败记录 |
| 镜像/包下载失败 | 固定镜像引用、DNS/TLS/官方或既定镜像源、网络错误 | 保持摘要、TLS 和包签名验证；不换 latest 或关闭校验 |
| integration 失败 | JUnit 是否实际产生、哪组数据库/HTTP/SMTP断言失败 | 修复代码或真实环境原因，保留完整测试强度 |
| container-release 失败 | TLS Docker 就绪、构建、deploy、三版本、浏览器的具体阶段 | 同一候选通过真实部署与浏览器，不用 mock 或只测构建替代 |
| package skipped | 上游四项是否全部成功、最新 pipeline 是否关联正确 | 修复上游；skipped 不能算通过 |

当前 CI 的镜像前缀缓存只影响 job，`ci-apt`/Alpine 镜像源调整也只作用于临时容器，不是部署主机的全局配置。历史共享 Runner/DinD 和 V5 下载失败保持原结论，入口索引见[发布证据](../evidence/release/README.md)、[DinD 诊断](../evidence/gitlab-dind-startup.md)、[Alpine 镜像记录](../evidence/gitlab-alpine-mirror.md)。后续已通过的流水线不回填为旧流水线成功。
