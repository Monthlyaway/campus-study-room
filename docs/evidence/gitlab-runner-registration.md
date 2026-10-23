# 专用 GitLab Runner 注册与启用记录

2026-10-23，用户明确授权在本机 `studyroom-ci` 专用 VM 注册并启用项目 Runner。主代理已完成实际注册、配置纠正与启用；GitLab Runner 编号为 **5666**。**注册、启用及真实 Runner 诊断已完成；完整五 job CI、main 合入及最终 tag 仍未完成。**

记录依据为主代理的实际 GitLab UI、注册及来宾现场核对；本次文档整理没有读取令牌或操作 VM。原共享 Runner 启动失败、本地无认证探针与本次注册是不同证据，见[诊断历史](gitlab-dind-startup.md)。

## 实际范围与状态

| 项目 | 启用时实际值 |
| --- | --- |
| 唯一项目 | `yangchunyu/campus-study-room`，ID `11753` |
| Runner | `5666`，`studyroom-isolated-release-arm64` |
| 程序 | `17.8.5`，revision `c9164c8c`，`linux/arm64` |
| 专用标签 | `studyroom-release-arm64` |
| GitLab Paused / Protected | No / Yes |
| Run untagged / Locked to current projects | No / Yes |
| 最大 job 时间 | 1 小时 |
| GitLab 最近联系 | 核验时显示 `just now` |
| 来宾 systemd | enabled / active / running，核验时 PID `5557` |
| 正式配置 | 仅来宾 `/etc/gitlab-runner/config.toml`，root 所有、0600 |

GitLab UI 截图：[Runner 已启用](../../output/gitlab/isolated-runner-enabled.png)、[精确保护分支](../../output/gitlab/protected-runner-branches.png)。截图为本机 `output/` 证据，不随源码归档自动分发；PID 和最近联系是该次观察值，不代表永久状态。

原 `main` 的保护配置保持：Maintainers 可 push/merge，禁止 force push。此次仅新增两条精确保护规则 `ycy/campus-platform` 与 `ycy/ci-container-check`，同为 Maintainers 可 push/merge、禁止 force push；核验时无其他保护分支规则。受保护 tag 规则当时为 0，尚未创建最终发布 tag。用户授权的正式发布 tag 需要先按确切名称配置保护，不能把授权写成已存在的保护规则。

## 来宾配置与凭据处理

启用前核对 `concurrent=1`、单 Runner `limit=1`、`check_interval=3`，Docker executor 使用动态卷 `volumes=["/builds"]`。默认 image、allowed_images 与 allowed_services 均与已审模板一致：固定 Docker CLI / DinD `28.5.2` 的原摘要，未扩大镜像白名单、增加宿主目录或宿主 socket 挂载。

注册程序曾默认增加 `/cache` 并把 `check_interval` 写为 0；主代理在启用服务前已纠正为仅 `/builds` 和 3 秒，再核对最终配置。没有把注册默认值当成已审模板值。

注册令牌由浏览器自动化内存直接经 `colima` 标准输入传至来宾进程环境，没有落入 Mac 文件；含令牌的最终配置只在来宾受保护路径中。本文、截图说明和公共源码不包含令牌值。后续检查使用状态、权限和非敏感配置字段，不输出完整认证配置。

## 真实 Runner 诊断已通过

精确受保护诊断分支提交 `2b3c32f39d7f3071973b9353e273565f563d79f6` 的 [pipeline 683461](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683461) / [job 4481126](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4481126) 在 Runner 5666 上实际通过，用时43秒、排队1秒。该 job 没有构建、部署或验收业务应用。

- 官方 `arm64-v17.8.5` helper 镜像实际拉取，checkout 路径为 `/builds/yangchunyu/campus-study-room`。
- 2026-10-23T00:10:42Z，第1次 TLS readiness 查询即成功；客户端与 daemon 均为28.5.2，Compose为2.40.3，双端均为 `linux/arm64`。
- 六个证书文件非空且未输出内容；缺少客户端证书目录的请求被拒绝。
- 在同一内层 daemon 拉取并核对固定镜像后，真实 checkout 下的只读 marker 通过嵌套容器绝对路径读回一致，写入被拒绝。实际镜像 config ID 为 `sha256:2db12c6d83ddf578823aa8c204f9a8e4fd29c940a4d3bb1457109dd0f0ce9b52`，没有混用外层 OCI 索引 ID。

Runner 可选 service helper 仍输出 `FATAL` / `No HOST or PORT found` 警告；随后真实 TLS 认证 Docker 查询及嵌套目录断言通过。保留该警告，不声称日志零警告，也不把可选 helper 的失败当成实际 daemon 连接失败。

本机证据：[原始可见日志](../../output/gitlab/isolated-runner-diagnostic.txt)、[诊断通过截图](../../output/gitlab/isolated-runner-diagnostic-passed.png)。此前无 GitLab 认证的本地探针只证明 VM 机制；本次结果新增实际 Runner 拉取、checkout、TLS 和嵌套路径证据，两者分别保留。

## 完整发布仍待执行

接下来须对交付提交运行 backend、frontend、integration、container-release、package 完整阻断链路。诊断成功不能代替应用构建、部署和浏览器验收，也不能关闭 MR 最新 SHA、main 自动更新或最终 tag 门禁。

本次注册与诊断没有改变 R4 运行应用、原共享 Runner 权限或既有业务数据。当前 R4 本机验收仍按原版本证据成立，完整 GitLab 发布项等待真实结果。
