# 专用 Runner 配置的 GitLab 服务端模拟

对应 REQ-ENG-05。2026-10-22 17:49 UTC（北京时间 2026-10-23），实际 JiHu GitLab 17.8.7-jh 的 Pipeline editor 返回 **Simulation completed successfully**。这是服务端配置模拟，不是已运行的发布流水线。

## 输入及执行边界

- 本地提交：`098d8a23449daae4390a089b61e83a5566aaab93`，核验起止工作区干净。
- 输入：该提交的完整 `.gitlab-ci.yml`，SHA256 `29082295f44a0b325dc411318154208ffd4750f5554b6e5730d2e88b936c6296`。
- 在项目 11753 的 [Pipeline editor](https://code.agibot.com/yangchunyu/campus-study-room/-/ci/editor?branch_name=ycy%2Fcampus-platform&tab=2) 中，将本地文件粘贴到未提交的 Edit 内容，使用 Validate 页的 **Git push event to the default branch** 条件。
- 页面确认使用当前 Edit 内容，实际评估 rules、only、except、needs。未点击 Commit changes，没有推送、触发真实 pipeline、创建 Runner 或授权令牌。

## 实际模拟输出

页面同时显示 Pipeline syntax is correct 和模拟成功；五个 job 都为 `on_success`，没有被跳过或改为可失败。

| 阶段 | job | 实际解析的标签 |
| --- | --- | --- |
| verify | backend | `amd64` |
| verify | frontend | `amd64` |
| integration | integration | `amd64` |
| release-test | container-release | `studyroom-release-arm64` |
| package | package | `amd64` |

页面仍显示历史 pipeline 683030 / 3012051 canceled；该历史状态与当前编辑内容的模拟结果不同。模拟没有生成新的成功 pipeline ID，也没有验证 Runner 调度、实际 Docker 构建、浏览器任务或 MR 最新提交关联。

原始本机证据：

- [截图](../../output/gitlab/ci-default-branch-simulation.png)，SHA256 `def6d7d999986fbdff6eefab9e7933d1559709eed90502d9113c8af6b378563a`。
- [页面可见文本](../../output/gitlab/ci-default-branch-simulation.txt)，SHA256 `59b16a418cf00311035fac225b01eca1e91a9521dd7f6f6c8783dc46bc6f5fc3`。

## 同期源码及历史核对

独立 agent 只读核对 `3012051` 到 `098d8a2`：五个提交共 35 个差异文件，分为 CI 1 个、测试及浏览器评审 2 个、README/docs 32 个。242 项生产及构建输入的字节与模式不变，包含 apps、services、modules、tools、db、infra、scripts、contracts、Maven、包锁、依赖基线及安装入口。

正式 `verify_bundle` 检查通过；R4 source.tar.gz 的 414 份文件字节和模式与内存中的 `git archive 3012051` 完全一致，归档提交标记、portable 清单、release 清单一致。R4 包不包含后续五个提交，不能把其源码版本写成 098d8a2。

实际 fetch 后，main 仍为 `df767bc40f51323e0784cae19ac0f50d2feb2e6f`，是上述 HEAD 的祖先；`main..HEAD` 共 62 个提交、零 merge commit。此计数是核验时快照，不包含本证据的后续文档提交。尚未合入 main 或创建最终 tag。

Runner 的 [隔离准备及本地探针](gitlab-dind-startup.md)已完成，注册和启用仍等待用户对持续执行权限的确认。服务端模拟成功不替代这项授权，也不关闭完整发布门禁。
