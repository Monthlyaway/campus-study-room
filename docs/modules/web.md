# 前端模块参考

[模块索引](README.md) · [前端开发指南](../development/frontend.md) · [用户手册](../user-guide/README.md)

`apps/web/` 实现「栖学 · 校园自习室」，采用 React 19、Umi 4、TypeScript 5、Ant Design 6。精确依赖以 [package.json](../../apps/web/package.json) 和 [pnpm-lock.yaml](../../apps/web/pnpm-lock.yaml) 为准；运行时使用 Node 24（项目基线为 24.22.1），根目录 `scripts/web` 固定 pnpm 10.34.7。静态产物由 Nginx 提供，不依赖外网字体或图片。

## 所有权

| 入口 | 负责内容 |
| --- | --- |
| [App.tsx](../../apps/web/src/pages/App.tsx) | 单一 Umi 通配入口中的路由分发、导航、角色首页、会话、主题与布局 |
| [pages](../../apps/web/src/pages) | M1/M2/M3、通知、管理、教室屏，13 个页面路径见开发指南 |
| [common.tsx](../../apps/web/src/components/business/common.tsx) | 查询/动作 Hook、表单、状态、时间、错误和加载 |
| [api.ts](../../apps/web/src/services/api.ts) | 同源传输、CSRF、幂等键、请求号、204、解包和生成客户端适配器 |
| [session.tsx](../../apps/web/src/services/session.tsx) | 当前身份上下文；业务数据使用页面 state 与查询 Hook |
| [access.ts](../../apps/web/src/access.ts) | UI 权限和中文角色/权限名，不替代后端范围校验 |
| [global.css](../../apps/web/src/global.css) | 响应式、手机卡片、表格滚动和无障碍样式 |
| [generate.mjs](../../apps/web/scripts/generate.mjs) | 公共 OpenAPI 客户端生成，内部契约不进浏览器 |

## 必须保持的行为

- Cookie 同源会话，登录后更新 CSRF；业务写入用幂等键，状态动作带 `expectedVersion`。
- 结果未确认时保留原 key/body；409 刷新并要求重选；错误提供 requestId。
- 服务端 `allowedActions` 决定动作，管理员身份不隐含业务权限。
- 上海时间、整点、左闭右开、14 天结束边界；维修异步同步不能提前显示完成。
- 桌面和 375px 均可完成关键操作；查询失败、无选项和加载有明确反馈。

完整修改步骤、错误决策表、接口生成与验证矩阵统一维护在[前端开发指南](../development/frontend.md)。页面步骤维护在[用户手册](../user-guide/pages.md)。

## 测试与证据

当前测试入口为 [apps/web/tests](../../apps/web/tests) 和 [tests/browser](../../tests/browser/README.md)，E2E 输出 `output/browser/`。全仓检查不自动跑浏览器；正式套件需要隔离、版本一致的打包应用。

历史 [V6 六流程](../evidence/frontend-release-final-v6.md)、[V5](../evidence/frontend-release-final-v5.md)、[R4](../evidence/frontend-release-v4.md)、[早期交互修复](../evidence/frontend-release.md)与[独立试用记录](../../tests/browser/REVIEW.md)保留原版本和范围。早期 CLI 产物不是当前套件默认输出，也不代表本轮重跑。交付状态看[进度](../progress.md)，运行环境看[接手卡](../operations/current-instance.md)。
