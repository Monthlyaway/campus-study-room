# ADR 0005：公开仓库与 General 分支保护

日期：2026-10-23。状态：用户直接要求；设置已生效，本次工作流修改的验证以对应 PR Checks 为准。

## 问题与依据

用户将 `Monthlyaway/campus-study-room` 的可见性改为 public，并要求启用分支保护。原私有阶段无法启用规则的套餐限制不再作为现状；[ADR 0004](0004-github-codex-lb-production.md)及既有证据保留其原时间和边界。

## 决策

1. 为 main 启用 [General ruleset](https://github.com/Monthlyaway/campus-study-room/rules/25729390)：必须经 PR、解决评审会话、保持线性历史，仅允许 rebase merge，禁止强推和删除。无 bypass，当前 owner 亦受规则约束；人工批准数为 0，不虚构第二位审批人。
2. 必需检查为 GitHub Actions App 15368 的 `verify` 与 `ci-gate`，要求候选包含最新 main。GitHub 原生检查可接受 skipped/neutral，故 `ci-gate` 使用 `always()` 等待 verify，只在结果为 success 时成功；取消、失败、跳过或未知结果均不能通过。
3. 开发、完整检查、集成、镜像、浏览器测试与发布仍在 codex-lb。唯一例外是轻量 `ci-gate` 使用 GitHub-hosted `ubuntu-latest`，权限 `{}`，不检出代码、不使用秘密，只判断上游结果；不能为处理外部 PR 而把不可信代码放入专用 runner。
4. 外部 fork 工作流审批设置为 `all_external_contributors`。工作流条件与 runner 标签不是安全隔离，维护者仍须审查外部改动；可信贡献纳入同仓库 `ycy/` 分支后走完整 PR 验证。
5. 公开仓库不自动扩大私有阶段对课程应用凭据的授权，不新增或复制真实凭据，既有账号另按用户确认处理。基础设施秘密、生产数据、Cookie 和普通 CI 制品的隐私边界保持不变。Git 仓库及其 Release/源码资产随可见性公开，但 GHCR 镜像仍必须 Private；发布器应核对精确仓库身份与 public 可见性，继续拒绝公开镜像包。

## 验证与影响

GitHub ruleset 读取及 main 生效规则返回 Active、上述 required checks 和空 bypass；fork 审批 API 返回 `all_external_contributors`。设置生效不代表本次工作流已经运行成功；合入仍核对最新 SHA 的实际完整 CI。操作步骤、成功信号和失败处理集中在 [GitHub 手册](../operations/github.md)，实施状态见[进度](../progress.md)。

Mac 仍只作 SSH/浏览器控制。开发目录仅做必要的定向检查，完整集成与 E2E 交给 PR CI，不因启用保护重复执行整套验收。发布器的公开仓库适配由对应 PR 验证，本次不发布新 tag，也不声称新的 tag 部署已通过。现有生产版本、数据和不可变发行资产不因仓库可见性或分支规则变更而重建。
