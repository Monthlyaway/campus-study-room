# GitHub v0.2.1 浏览器验证失败记录

[证据索引](README.md) · [运行接手卡](../operations/current-instance.md) · [演示账号与样例数据](../development/demo-accounts.md)

本记录对应 Linux AMD64 / codex-lb 的新交付，旧 GitLab / ARM64 报告范围保留。v0.2.1 正式流水线的全仓检查通过，但真实浏览器 6 项中 1 项失败，verify 未通过，publish 与 deploy 均跳过。本版本没有创建 GitHub Release 或上传新 package；生产应用仍未部署。

## 修复与合入

[v0.2.0 发布中断](github-production-v0.2.0.md) 的根因为 GHCR REST 未返回 repository 字段。修复改为核对实际提供的私有性、账号、包名、容器类型，并在发布前和部署时检查测试镜像的 OCI source/revision/version；已有版本禁止覆盖及测试证据、digest、资产校验规则保留。

- [PR #2](https://github.com/Monthlyaway/campus-study-room/pull/2) 于 2026-10-23T11:26:01Z rebase merge。
- PR head：dc6f1c43f0631b2e2d0603538cddc3ea4defff79；main/tag：655cc322b96118827efa9c5572aeab323a055f60。
- 2 个逻辑提交均保留，author name/email/date/subject 和最终源码树逐项一致，原 main 仍为祖先；无 squash、merge commit 或 main 历史改写。
- 用户已明确授权本轮自主完成合入与部署。独立 agent 复评不冒充人工审批。

## PR 实际验证

[Actions 39943418489](https://github.com/Monthlyaway/campus-study-room/actions/runs/39943418489) 在准确 PR head 上通过 verify：

| 范围 | 实际结果 |
| --- | --- |
| 全仓检查 | 编译、契约生成差异、前端类型/测试/构建全部通过 |
| 运维单元测试 | 164 项通过，其中发布器 20 项 |
| 真实 HTTP / PostgreSQL | 7 套 HTTP、18 项；9 套 PostgreSQL、66 项，0 失败/错误/跳过 |
| 邮件与故障恢复 | Mailpit，27 项期限收件人断言，响应丢失重放通过 |
| 镜像与浏览器 | 三镜像隔离部署；6 项浏览器通过，0 失败/跳过，126.708 秒 |
| 一致性与隐私 | 运行版本、manifest 与测试证据绑定；报告隐私门禁及上传通过 |
| 文档与静态复核 | 233 处相对链接/锚点通过，diff 与限定凭据检查通过 |

容器测试 releaseId 为 ci-39943418489-1-dc6f1c43f063，manifest SHA-256 为 37d6563bbd658201b81158696adbc28711cf67763b95292e0c50f77fb3313671。原生集成报告标签为 working-tree，不能用该标签代替 preflight 和容器清单核对的真实 commit。

## 正式流水线

[Actions 39946065589](https://github.com/Monthlyaway/campus-study-room/actions/runs/39946065589) 对 `v0.2.1` / `655cc322b96118827efa9c5572aeab323a055f60` 重新执行验证。PR 的成功不替代此次正式版本结果。

| 范围 | 实际结果 |
| --- | --- |
| 完整 `./scripts/check` | 通过；运维单测 164 项、7 套 HTTP / 18 项、9 套 PostgreSQL / 66 项 |
| 邮件与故障恢复 | 27 项期限收件人断言、响应丢失重放通过 |
| 真实浏览器 | 共 6 项，5 项通过、1 项失败；总耗时 125.841992 秒 |
| verify / publish / deploy | verify 失败；publish、deploy 因上游失败跳过 |
| 发布结果 | 没有本版本的新 GitHub Release 或 package 上传；未执行生产部署 |

### 浏览器失败位置与诊断

失败用例为 `M1 availability, real stale 409 and mobile history reuse create a new reservation`。实际执行到桌面真实 409 弹窗关闭之后，将视口从桌面缩至 375px，并检查页面与可见弹层是否越界；此时**尚未打开历史再次预约弹窗**，不能将这次失败记录为历史再约弹窗本身越界或已完成手机历史再约。

现有诊断证据指出两个问题：

- 关闭中的弹窗仍处于退出动画，缩窄视口时继续沿旧桌面的 transform origin 变换，出现真实的瞬时水平越界。这是需要修复的界面行为，不能只归因为测试等待时间。
- 布局 helper 先枚举 `:visible` 元素，再逐个读取 `boundingBox()`，两次读取不是同一页面快照。退出动画可在中间改变可见元素集合，造成定位与尺寸读取竞争；检查器也需要修复这一读取方式。

目前正在修复弹窗动画与布局采样，修复后验证尚未完成。须保留真实 409、375px 可用性和边界断言，在后续独立运行记录实际结果；本次失败及其 PR/tag 身份保持不变，不能提前记成通过。

同 SHA 的重复 [main 运行 39946039714](https://github.com/Monthlyaway/campus-study-room/actions/runs/39946039714) 在正式版本入队后取消，取消不计为通过。取消后只见原有 codex-lb 与 BuildKit 容器，未留下该次临时测试或生产容器；由版本流水线承担该 SHA 的完整发布验证。

## 生产与课程账号

正式 deploy 本次未执行；HTTPS 业务核验、课程账号初始化和独立生产桌面/375px 实际试用仍待完成。凭据唯一维护位置为[演示账号与样例数据](../development/demo-accounts.md)，本记录不重复密码。

## 备份与恢复

尚待定时任务安装、实际生产备份、隔离数据库恢复与全部数据快照对比；不把旧 ARM64 恢复报告当作本机结果。

## 资源与边界

服务器约 2 核 / 2 GiB RAM / 4 GiB swap；已通过串行 CI 不等于已完成同机生产容量或原 4 核 / 8 GiB 性能验收。后续记录实际 CI/生产并存观察。邮件仍为 Mailpit，不声称外部邮箱收信。私有仓库当前套餐不强制分支保护，维护流程核对结果不冒充平台强制审批。
