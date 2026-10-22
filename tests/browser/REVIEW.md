# 2026-10-22 离线发布包浏览器验收

目标是经 `scripts/import-release` 安装到全新隔离状态目录 `.local/portable-install` 的运行版本，通过 `http://localhost:19282` 访问。Web/core/repair 均返回：

- releaseId：`r-20261022T132000-66f2b928550f`
- commit：`66f2b928550f50d89fe39ed23057beefd66f39cd`
- Playwright：`1.64.1`；Chromium `156.0.8078.4`；Mac ARM64 控制端。

最终完整执行为 **6/6 通过，0 skipped、0 unexpected、0 flaky，108.59 秒**，开始时间 `2026-10-22T13:43:09.633Z`。覆盖真实登录与错误密码、会话刷新、学生/管理员权限边界、预约创建与跨页面真实 409、两级不同人员审批、维修派单/资源下架/完工/验收/资源恢复、管理员真实空结果、375px 导航与布局、真实网络延迟/断网恢复、真实不存在记录错误。没有 mock API、自动重试或降低后端约束。测试创建的预约/申请已取消，维修工单已关闭，finally 清理无失败。

## 截图与实际反馈

以下证据位于被 Git 忽略的 `output/browser/results/` 对应场景目录，CI 可保存同一输出目录的 JUnit/HTML/JSON：

| 场景 | 截图文件名 | 实际观察 |
| --- | --- | --- |
| 桌面预约 | `desktop-reservation-created.png` | 时间、座位、状态、下一步操作清楚 |
| 真正的旧版本冲突 | `real-stale-version-conflict.png` | 显示服务端错误与 requestId，确认按钮禁用，可返回重新选择 |
| 手机预约历史 | `mobile-reservation-history.png` | 375px 无稳定横向溢出，取消后的签到按钮不存在 |
| 两级审批 | `desktop-two-level-approved.png`、`mobile-application-cancelled.png` | 两级意见、人员和时间可见；手机撤销弹窗可点击 |
| 维修多角色 | `mobile-repair-created.png`、`desktop-repair-awaiting-acceptance.png`、`mobile-repair-closed.png` | 下架/恢复确由真实服务写回；工单状态与动作权限符合流程 |
| 手机查询与错误 | `mobile-available-seats.png`、`mobile-offline-error.png`、`mobile-real-missing-record.png` | 导航、查询加载、网络重试与404错误可用，页面和可见弹层均在视口内 |

视觉方向一致：浅色背景、深绿色操作、较清晰的卡片和紧凑导航。移动卡片与审批弹层都能使用。发现两项文字易用性改进：

1. 工单“维修人员”在该旧 B 响应下显示短 UUID，普通报修者不易辨认。页面已有 `assignedToName` 优先逻辑并保留旧响应兼容；已交 root 协调后端增加可读姓名。
2. 已关闭工单仍显示未来“处理截止”。已按 root 授权修改 `apps/web/src/pages/Repairs.tsx`，隐藏 `CLOSED/CANCELLED` 的截止字段，保留活动工单截止时间。此小改动不在上述 B 镜像中；需要在最终新版本上复验，不能把旧 B 的通过归给新代码。

## 初始失败与复验边界

首轮脚本精确匹配“确认/申请教室”等按钮时，未考虑 Ant Design 的图标可访问名称和双中文字间距，导致测试定位失败。依据真实 DOM 修正了定位，并等待弹层动画结束后再断言布局；没有改业务页面以迁就测试。查询首次为空时外层 Spin 的覆盖区域没有高度，但查询按钮已有可见 loading 图标；测试改为验证该真实可见反馈。

M3 首次完整尝试中，“提交完工说明”填入文本后等待 `/complete` 响应超时；当时没有请求完成证据，不能认定业务成功。finally 经真实公共完工/验收接口清理了该工单。后续一次独立 M3 复验和最终全套回归均通过，**该首次失败没有被定位或宣称已修复**。原始失败与截图保留在本机 `output/browser-second/`；最终输出不覆盖这些记录。后续版本验收应继续关注该操作。

最终运行的主要页面没有捕获到 JavaScript 异常或 CSP 拒绝错误。这里只覆盖 Chromium、1440×1000 和 375×812，不能代表 Safari/Firefox、全尺寸或长期稳定性验收。测试证据只涉及上述精确发布版本；后续契约/后端/终态文案变更需针对新版本重新执行。
