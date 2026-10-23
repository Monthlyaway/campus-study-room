# GitHub v0.2.0 发布验证与中断记录

[证据索引](README.md) · [交付决策](../adr/0004-github-codex-lb-production.md) · [运行接手卡](../operations/current-instance.md)

本记录对应 2026-10-23 的 GitHub / Linux AMD64 新交付，不替换原 GitLab / ARM64 验收。v0.2.0 完整验证通过，发布在首个镜像上传后中断；生产部署没有执行。本记录保留失败事实，后续修复及正式上线另附新版本证据。

## 代码与 PR

- 私有仓库：Monthlyaway/campus-study-room；所有编辑、commit、push 与测试在 codex-lb 执行，Mac 仅作 SSH/浏览器控制。
- [PR #1](https://github.com/Monthlyaway/campus-study-room/pull/1) 于 2026-10-23T10:22:11Z rebase merge，保留 9 个逻辑提交，没有 squash 或 merge commit。
- PR head：`e7ace1523f51ebfb6c06781972bc499364bb00dc`；合入 main：`62d2462a6f5eb83b2061cb254f6502936488c3ed`。
- 合入后逐条比较 author name/email/date/subject 与源码树，均一致；原 main `9c1580a1f9b43af78dc9471668649f0a1f609c38` 仍为祖先，未改写原有历史。
- 独立 agent 静态复核了生产边界、发布/推广绑定和课程账号恢复逻辑；这不是独立人工审批。

## 已完成的 PR 验证

[Actions 39935385008](https://github.com/Monthlyaway/campus-study-room/actions/runs/39935385008) 的 verify 成功，runner 为 codex-lb-campus-study-room。

| 检查 | 实际结果 |
| --- | --- |
| 完整 ./scripts/check | 成功，包含编译、契约生成差异、前端类型/测试/构建和运维检查 |
| 运维单元测试 | 158 项通过 |
| 真实 HTTP | 7 套、18 项通过 |
| PostgreSQL | 9 套、66 项通过，失败/错误/跳过均为 0 |
| SMTP / 故障恢复 | 使用 Mailpit；27 项期限收件人断言与响应丢失重放通过 |
| 三镜像隔离部署 | core、repair、web 构建、健康与版本一致性通过 |
| 真实浏览器 | 6 项通过，0 失败、0 跳过，123.552 秒 |
| 报告隐私与制品 | 独立隐私门禁及 artifact 上传成功 |

原生集成报告的运行标签为 `working-tree`，工作流 preflight 已核对真实 checkout SHA；容器验收结果明确绑定上方 PR head、run/attempt 与 manifest SHA，不把原生标签冒称为镜像身份。

## 正式发布

版本标签 `v0.2.0` 指向合入后的 main。正式 [Actions 39938692194](https://github.com/Monthlyaway/campus-study-room/actions/runs/39938692194) 在同一 SHA 上重新完成 verify：全仓检查、三镜像隔离部署、真实浏览器、版本身份及报告隐私门禁均成功。publish 失败，deploy 因上游失败而跳过；不能把该版本报告为生产上线。

main 的重复 [Actions 39938605411](https://github.com/Monthlyaway/campus-study-room/actions/runs/39938605411) 被取消，由同一 SHA 的版本流水线承担完整发布验证；该取消结果不计为通过。只停止了取消运行专属的测试容器/网络，保留所有命名数据卷。

### 发布失败与定位

- core 镜像已上传并按 digest 回拉，镜像 ID 与测试产物一致；已存在的 `v0.2.0` 标签不覆盖、不删除。
- 镜像地址：`ghcr.io/monthlyaway/campus-study-room-core@sha256:711f23edf74e22cee27c903f97bacb8e8c55ad5dd92f8bbdc7156cfb16e7b0c8`。
- 发布脚本随后要求 REST package 响应必须有 `repository.full_name`，实际 GHCR 响应没有 `repository` 字段，触发身份检查失败。repair/web 未进入上传，GitHub Release 未创建，发布清单未生成；artifact 上传也因此失败。
- 只读 [Actions 39942360429](https://github.com/Monthlyaway/campus-study-room/actions/runs/39942360429) 使用临时 `GITHUB_TOKEN` 的 `contents: read` / `packages: read` 核实：包名正确、visibility 为 private，响应 keys 确实没有 repository。仓库 API 返回 `Monthlyaway/campus-study-room`、ID `1430762043`、private=true。[补充只读运行 39942764021](https://github.com/Monthlyaway/campus-study-room/actions/runs/39942764021) 进一步核实 owner.login=Monthlyaway、owner.id=102352706、package_type=container。
- 已登录 GitHub 页面显示 core 包为 Private，来源关联 campus-study-room；镜像 OCI source/revision/version 标签对应本仓库、本提交和 v0.2.0。没有证据表明镜像变成公开或关联其他仓库。
- 诊断在单独的 `ycy/package-diagnostics` 分支执行，仅查询元数据，不合入 main，不发布或部署，也没有替代正式验证门禁。服务器个人 OAuth 权限没有扩大。

修复需要使用 GHCR 实际提供的账号、包名、类型、可见性以及已测试镜像的 OCI 来源/提交身份，而不能把缺失的 REST 关联字段当成关联错误。修复经 PR 验证后以新版本发布；本次部分上传不冒充完整 Release。

生产上线、课程账号实际登录、备份恢复及生产桌面/375px 实际试用均**尚未完成**。课程登录凭据只写入[演示账号与样例数据](../development/demo-accounts.md)，本报告不重复密码。

## 已知边界

服务器约 2 核 / 2 GiB RAM / 4 GiB swap，测试串行且应用有资源限制，不将旧 4 核 / 8 GiB 的性能报告当作本机实测。邮件仍使用已授权 Mailpit；未声称向外部邮箱投递。当前私有仓库套餐不强制 branch protection，合入前按维护流程核对最新提交的完整结果。
