# 真实资源同步故障下的前端验收

日期：2026-10-22。使用 Playwright skill 的独立 `fault-review` / `fault-admin` 浏览器会话，在 `http://127.0.0.1:18183` 操作。后端为独立 core 18181、repair 18182、故障代理 18180，使用专属数据库；没有操作 8000 或 19282 的安装。

此环境是独立服务和生产静态前端副本，**不是最终不可变发布包**。原始服务/静态文件哈希由 `output/fault-matrix/runtime.json` 记录；轮询修复预览的源 HEAD、dirty 标记、补丁 SHA256 与静态文件哈希另存于 `output/fault-matrix/ui-fixed-runtime.json`，人工关注提示补齐后的副本另存 `ui-attention-runtime.json`。不能用工作区 HEAD 冒充运行 JAR 的源码证明。

登录通过真实 UI 完成。为避免 CLI 自动页面快照记录密码框值，会话状态只保存在忽略的 `.local/fault-browser/`，目录 0700、文件 0600；后续 CLI 载入这些私有会话，不输出凭据。没有记录 trace/HAR/storageState 到公开证据目录。

## 注入方式与初次失败

代理只在持久开关存在时断开 `/internal/v1/resource-commands` 的真实 TCP 请求；权限检查和详情读取继续转发到真实 core。没有伪造业务响应、改变浏览器时钟、直接修改工单状态或缩短后台重试间隔。

第一次夹具的 Java 环境继承了测试用 `STUDYROOM_CORE_URL`，覆盖正式 `CORE_INTERNAL_URL`，导致资源请求绕过故障代理。首个工单短暂进入同步中后正常进入维修中，不能算作断网验收。夹具已修正环境隔离，首张工单通过真实完工/验收关闭。初始截图保留为 `output/playwright/fault-initial-before-injector-fix-*.png`。

第二张专属工单为 `d0a806ed-469a-4283-9bd7-cf3536561c04`。15:10:02 UTC，维修人员在手机页面点击开始维修。后台只读核验确认 BLOCK 命令持久重试、代理 `requestBlocked=true`、core 尚无该来源占用。手机显示「正在同步下架，尚未开始维修」，不提供提交完工动作。375px 视口的 document 和抽屉宽度均为 375px。证据：`output/playwright/fault-block-pending-mobile.png`。

## 发现并修复轮询重启缺陷

旧代码在详情轮询满 60 秒后停止定时器。物业管理员点击「重试同步」成功时，文案恢复为自动刷新，但工单 ID 与 pending 状态均未变化，effect 没有重建定时器。

| 实际观察（UTC） | 真实详情 GET 累计数 | 页面反馈 |
| --- | ---: | --- |
| 修复前 15:11:32 | 30 | 轮询满 60 秒，提示手动刷新 |
| 修复前，重试后 15:11:51 | 31 | 提示自动刷新 |
| 修复前 15:12:15 | 31 | 24 秒无新请求，仍提示自动刷新 |
| 修复后 15:15:14 | 30 | 再次等满 60 秒，提示手动刷新 |
| 修复后，重试后 15:15:19 | 32 | 提示自动刷新 |
| 修复后 15:15:38 | 42 | 19 秒新增 10 次真实 GET，轮询已恢复 |

修复仅在 `Repairs.tsx` 为成功动作增加轮询代次，让相同 ID/状态下的重试也能重新建立有界定时器。每 2 秒刷新和 60 秒截止均保留，服务端状态仍决定操作权限。TypeScript、6 项请求层测试和 Umi 生产构建通过；上述复测使用修复后的真实静态构建。

计数来自浏览器 `PerformanceResourceTiming` 对精确详情 URL 的只读观察；结构化证据为 `output/fault-matrix/ui-polling.json`，截图为 `fault-block-poll-expired.png`、`fault-retry-poll-stalled-before-fix.png`、`fault-retry-polling-after-fix.png`。

## 人工关注与恢复验收

第 8 次失败时真实详情已返回 `sync.needsAttention=true` 和 `nextAttemptAt`，但原界面只显示次数和错误码，缺少明确的人工关注提示。已在同步告警中补上「需要人工关注」、继续重试说明和下次重试计划。15:20:23 UTC 的真实 375px 页面同时显示次数 8、下次重试计划 23:20；顶部刷新和底部重试按钮可见，文本没有横向裁切。前后截图为 `fault-attention-backoff-mobile.png` / `fault-attention-backoff-mobile-after-fix.png`。该修改的 TypeScript 和生产构建通过。

解除 BLOCK 网络故障后，后台仍遵守第 8 次失败后的真实 300 秒退避，在 15:20:27 UTC 自动进入维修中，没有额外手工重试加速，也没有改写持久重试时间。随后重新阻断 RELEASE 请求，完成如下真实 UI 操作：

1. 维修人员填写完工说明并提交，收到 HTTP 200；物业账号看到等待验收及「座位仍保持下架」。
2. 独立物业账号填写验收意见并通过，收到 HTTP 202；手机页面进入「正在同步恢复，工单尚未关闭」。真实请求失败后显示尝试次数 1、最近错误和下次重试计划。
3. `RELEASE_PENDING` 期间没有提交完工或验收按钮；物业只保留服务器允许的重试动作。375px 的 viewport/document 都为 375。截图为 `fault-release-pending-mobile.png`、`fault-release-pending-worker-desktop.png`。
4. 解除 RELEASE 故障后，维修人员页面在 15:22:59 UTC 已通过自动轮询看到「已关闭」。物业页面的有界轮询已结束，15:23:28 点击实际刷新按钮后也显示已关闭；没有处理截止、同步告警或同步按钮，维修姓名仍可读。截图 `fault-recovered-closed-mobile.png`。

末次查看两个浏览器会话的控制台均为 0 error / 0 warning。故障环境测试工单已关闭；后台双侧审计、旧命令重放、资源可用性及授权 503 的独立核验由维修模块证据另行记录。本证据只覆盖真实 Chromium 桌面/375px 操作和上述精确预览副本，最终不可变发布包仍需重新执行完整浏览器套件。
