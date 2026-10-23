# GitHub v0.2.2 发布与生产交付记录

[证据索引](README.md) · [运行接手卡](../operations/current-instance.md) · [交付决策](../adr/0004-github-codex-lb-production.md)

本记录对应 2026-10-23 的 GitHub / Linux AMD64 新交付。PR #3 已通过完整 Actions 验证并合入；v0.2.2 正式运行的 verify、publish、deploy 均成功，公网 HTTPS 和三应用版本核对通过，11 个课程账号已实际验证登录。实际 systemd 备份、隔离恢复与 42 表内容核对、独立生产桌面/375px 只读试用均已通过。资源观察记录提交前已完成的 12 次采样窗口，不声称覆盖后续 PR CI 负载。备份调度首次安装的 unit 错误已在宿主修正，源码生成器修复已完成针对性验证，完整结论按 PR 流程核对，失败与恢复均单独保留。旧 [v0.2.0 发布中断](github-production-v0.2.0.md) 与 [v0.2.1 浏览器失败](github-production-v0.2.1.md)报告保持原结果。

## 修复与合入

- [PR #3](https://github.com/Monthlyaway/campus-study-room/pull/3) 包含手机弹窗中心动画原点、同一页面快照的布局读取及逐帧退出回归，以及失败证据和避免开发目录重复完整门禁的协作规则。
- PR head：`4d000a175a25cfbddb37e9005e4d9282591cf059`；rebase merge 后 main：`524134c7ad9b730538f535aa2245c7f8f8d611c0`。
- 3 个逻辑提交保留，作者与作者日期、提交内容/源码树核对一致；没有 squash、merge commit 或改写 main 已有历史。
- 所有开发、commit、push 在 codex-lb 执行。开发只做针对性复现和验证，完整集成与 E2E 由 PR Actions 执行；之前按用户指示取消的开发目录完整检查不计为通过。

## PR 实际验证

[Actions 39952736484](https://github.com/Monthlyaway/campus-study-room/actions/runs/39952736484) 在上述 PR head 完成 verify：

| 检查 | 实际结果 |
| --- | --- |
| 完整 `./scripts/check` | 通过，含编译、契约生成差异、前端类型/测试/构建 |
| 运维单测 | 164 项通过 |
| 真实 HTTP | 7 套 / 18 项通过 |
| PostgreSQL | 9 套 / 66 项通过 |
| Mailpit 与故障恢复 | 27 项期限收件人断言、响应丢失重放通过 |
| 真实浏览器 | 6 项通过，127.121 秒 |

这是 PR 候选的实际通过记录。运行镜像、源码 SHA 与测试结果必须按该 run 的产物对应，不能用后续纯文档 HEAD 代替已测试或部署的源码。

## 正式版本流水线

`v0.2.2` 指向合入后的 `524134c7ad9b730538f535aa2245c7f8f8d611c0`。[Actions 39955632806](https://github.com/Monthlyaway/campus-study-room/actions/runs/39955632806) 的 attempt 1 已完成：

| job | 最终结论 | UTC 完成时间 |
| --- | --- | --- |
| verify | success | 2026-10-23T13:07:58Z |
| publish | success | 2026-10-23T13:09:24Z |
| deploy | success | 2026-10-23T13:10:47Z |

正式 verify 的完整 `./scripts/check` 通过：164 项 ops、7 套 HTTP / 18 项、9 套 PostgreSQL / 66 项、27 项期限收件人断言与响应丢失重放。三镜像真实浏览器 E2E 完成 6/6，127.263 秒，skipped / unexpected / flaky 均为 0；container result 为 passed，运行版本与源码 SHA 一致，报告隐私门禁及 artifact 上传通过。

### 不可变发布核对

私有仓库的 [Release v0.2.2](https://github.com/Monthlyaway/campus-study-room/releases/tag/v0.2.2) 已正式发布，draft/prerelease 均为 false。6 个 Release 资产的服务端 digest 与下载的本地 bundle 逐一一致；3 个发布 image ID 与本次 test 的实际镜像一致。deploy 从同一校验通过的源码归档推广，没有重新构建应用。

| 绑定项 | SHA-256 |
| --- | --- |
| source.tar.gz | `b1410b6770d212423f8c367b456a6c02e1aff2df6b9571d1fa57b60bdd8ef514` |
| 原测试清单 | `af4418bdf5562400fbfe47f49f020ca7797c42e0fe5815cbbabd3844f3d053f2` |
| 发布清单 | `e3b75d3c3b4bd326e27df6cd6d84d538925349591a101eeea6d0918b68b97a33` |

| 应用 | GHCR digest 引用 |
| --- | --- |
| core | `ghcr.io/monthlyaway/campus-study-room-core@sha256:e9f0f6babf2502aa69a05529d573b4dcf5df9bdd45a08072b397f39ed144448b` |
| repair | `ghcr.io/monthlyaway/campus-study-room-repair@sha256:49b613d135e6b211f0da2cdc9d7a3aa4b76c9efcec2b5ad7303465a0aa690bd7` |
| web | `ghcr.io/monthlyaway/campus-study-room-web@sha256:6efeca6291b6af71ae876a4da6c163e31c883396270a9faf813819717024c08d` |

上述核对的服务器原始摘要位于 `/home/ubuntu/.local/campus-v022-evidence/release-verification-summary.json`，下载清单位于该目录的 `github-release/manifest.json`。这些路径是本次受控留存位置，不包含在普通 clone 中；可复核的发行资产与运行链接见上方 GitHub Release / Actions。

同 SHA 的重复 [main 运行 39955626968](https://github.com/Monthlyaway/campus-study-room/actions/runs/39955626968) 在 check 阶段被取消，后续上传因没有产物而失败。取消与上传失败不计为通过，也不替代正式 tag 验证。取消后的资源核对仅见原有 codex-lb 和 BuildKit 容器，没有把该 main 运行记为完成生产部署。

## 生产与课程账号

应用已经从 `/home/ubuntu/Deployments/campus-study-room/releases/v0.2.2/source` 部署，生产状态固定为 `/home/ubuntu/.local/state/campus-study-room/prod`。公网 `https://49.51.193.169/` 返回 200；`/version`、`/api/v1/version`、`/api/v1/repairs/version` 均返回 200，三者 `releaseId=v0.2.2`、`commit=524134c7ad9b730538f535aa2245c7f8f8d611c0`。TLS 校验未使用跳过验证选项。

课程初始化通过真实 HTTPS 管理 API 完成，11 个课程账号均已实际验证登录，3 个课程房间、30 个座位已存在。没有预造预约、审批或维修工单。课程账号和初始应用密码唯一维护位置为私有仓库的[演示账号](../development/demo-accounts.md)，本记录不复制凭据；数据库、内部服务、GitHub/runner 和 TLS 等基础设施秘密不进入文档。

### 独立生产 UI 试用

另一名 agent 在 `2026-10-23T13:15:19Z` 至 `13:15:36Z`，使用 codex-lb 上的 Chromium / Playwright 1.64.1 访问受信任公网 HTTPS。`course-student` / `course-admin` × 1440px / 375px 四个独立浏览器上下文全部通过登录、刷新会话、GET 查询、导航及双向路由权限检查；学生可读取共享房间与本院房间的 20 个可用座位，符合课程资源范围。四组 STUDYROOMSESSION 的 Secure、HttpOnly、SameSite=Lax 属性均通过，web/core/repair 版本精确一致。

没有创建或修改业务数据，没有 JavaScript / 5xx 错误；未开启 trace/HAR，未输出秘密值。追加的手机管理员表格检查确认可在表格内部横向滚动到右侧管理列：容器可视宽 309px、内容宽 900px、scrollLeft=591；未点击写操作。浏览器已全部关闭。这是独立只读生产试用，完整业务写入 E2E 的证据来自正式版本 CI，二者不混作同一次验证。

私有报告与截图留存在 `/home/ubuntu/.local/campus-prod-review/`：`production-ui-v0.2.2-2026-10-23T13-15-19-235Z.json`、12 张截图，以及 `production-ui-scroll-v0.2.2-2026-10-23T13-19-25-377Z.json`；未把截图或登录状态复制进源码。

## 备份调度失败与恢复验收

v0.2.2 的 `schedule-backup install` 生成 `WorkingDirectory="绝对路径"`，Ubuntu systemd 将引号作为路径内容，service 报 bad-setting，timer 未启动。因此首次安装不能计为备份调度成功。

维护者只修正了已生成宿主 service 的 `WorkingDirectory` 一行，未改写不可变 v0.2.2 源码；`systemd-analyze --user verify` 随后通过。`com.campus-study-room.backup.8e4b5303d478.timer` 已 enabled / active waiting，用户 `Linger=yes`。开发分支的生成器修复另行提交：真实 systemd 解析器先在旧实现上产生 6 个失败子例，修复后 11 项定向测试两轮通过，独立代码复核也已通过；未在开发目录运行整套完整检查。本次生成器修复和交付文档按[贡献指南](../contributing.md)的 PR 流程验证，完整结论以对应 PR 的 Checks 为准。

实际 systemd service 于 `2026-10-23T13:13:58Z` 成功产生生产备份，随后的正式 restore 与内容比较如下：

| 检查 | 实际结果 |
| --- | --- |
| dump | `/home/ubuntu/.local/state/campus-study-room/prod/backups/20261023T131356842634Z.dump` |
| SHA-256 | `df2d25b7537e2b9697de5209313065c6f51ed430c4fc0f9b2b58af36976fb13c` |
| 归档完整性 | TOC readable 与 fullArchiveDecoded 均为 true |
| 正式隔离恢复 | `studyroom_restore_20261023131409`；在线数据库未修改，验证库保留 |
| 恢复内容 | 12 个用户（维护管理员与 11 个课程账号）；预约 / 申请 / 维修均为 0，符合未预造业务订单的初始状态 |
| 全量内容比较 | 原 dump 与隔离库的 42 张表全部 COPY 行多重集一致，序列 0 个，differences 为空 |

内容比较的只读结果留存于 `/home/ubuntu/.local/campus-prod-review/restore-comparison.json`，原 SQL 和密码哈希未输出到报告。该结果证明本次恢复点的全部数据一致，不代表后续新数据已经备份，也不是生产数据库切换。Linux 查询使用 `journalctl --user -u com.campus-study-room.backup.8e4b5303d478.service`；通用操作见[备份调度指南](../operations/runbook.md#安装或检查每日备份调度)。

## 资源与边界

宿主 IP HTTPS 与续期 dry-run 的已知事实见[接手卡](../operations/current-instance.md)。每 30 秒一次的只读资源观察留存在 `/home/ubuntu/.local/campus-prod-review/coexistence-20261023-v022.jsonl`。本次文档冻结核对的窗口为 `2026-10-23T13:14:20.899763Z` 至 `13:19:50.924237Z`，共 12 次样本：

- 首页与三个公开版本端点始终 200，TLS 校验码为 0；三版本始终精确匹配 v0.2.2 / 524134c7。
- 5 个生产容器始终 running / healthy，restartCount=0、OOMKilled=false。
- 主机 MemAvailable 最低 399020 KiB，SwapFree 最低 3068432 KiB。

这段窗口没有 PR CI 负载，只证明所述时段的部署后运行状态，不代表生产与完整 CI 同时运行的全过程或压力测试。后续检查按[贡献指南](../contributing.md)的 PR 流程执行，完整结论以对应 PR 的 Checks 为准；观察补充可以随 PR 正文记录，不改写本次已冻结窗口。2 核 / 2 GiB RAM / 4 GiB swap 的预算及串行 CI 不能代替原 4 核 / 8 GiB 性能验收。邮件仍为 Mailpit，不声称外部邮箱收信；生产回退未在本次运行执行。私有仓库套餐不强制 branch protection，实际维护检查不冒充平台强制审批。后续纯文档 main SHA 与正在运行的 `524134c7...` 分开记录，不为对齐文档 HEAD 重新构建生产镜像。
