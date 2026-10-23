# GitLab 失败门禁实测

执行于 2026-10-23（Asia/Shanghai），对应 milestone T00-05。主交付分支为 `ycy/campus-platform`，本演练另建 `ycy/ci-negative-gate`，不合入或发布破坏代码。

在独立分支提交 `d6a4d298`，增加一个无条件失败的 JUnit 测试 `DeliberateCiGateFailureTest.failedVerificationMustBlockMergeAndDeployment`。创建非 Draft 的 [MR !2](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/2)，实际触发 [pipeline 682968](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/682968)。

[backend job 4478518](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4478518) 实际运行该测试并因预期的 AssertionFailedError 失败；该用例为 1 test、1 failure、0 error、0 skipped。frontend 通过，integration、container-release、package 均被跳过，未部署或生成发布包。此处默认 backend 阶段跳过需要真实数据库的其他用例是既定分层；正常流水线还必须执行 integration，不能把默认单元阶段当完整数据库验收。

失败后的非 Draft MR 页面明确显示 `Merge blocked: 1 check failed`、`Pipeline must succeed`，唯一阻断项为流水线；squash 未勾选。页面还显示两项 deployment skipped。本机截图保存于 `output/gitlab/negative-mr-blocked.png` 与 `output/gitlab/negative-pipeline-stages.png`。本证据只证明负向门禁；交付 MR 的最终成功流水线、Fast-forward 合入 main 和 main 自动部署另行记录。

破坏提交只属于独立验收分支；已用 Git 祖先关系检查确认它不在 `ycy/campus-platform` 的历史中。演练后关闭 MR，保留分支、流水线和失败证据供复查。
