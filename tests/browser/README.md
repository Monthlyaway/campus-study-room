# 真实浏览器 E2E

[测试指南](../../docs/development/testing.md) · [前端指南](../../docs/development/frontend.md) · [准备隔离安装](../../docs/operations/installation.md)

对应 M1/M2/M3、共享权限和前端可用性验收。独立依赖包锁定 `@playwright/test 1.64.1`，来源为 [官方 npm 发布元数据](https://registry.npmjs.org/@playwright/test/1.64.1)。应用依赖和生成客户端不受影响。首次人工式操作使用 Playwright skill 的 CLI；本目录是按交付任务明确要求增加的可重复运行测试。

## 运行

使用 Node 24。目标必须是已完成迁移、基础数据与 demo seed 的隔离 local/test 安装，且 core/repair/Web 同源可用。测试实际创建预约、整教室申请与报修工单，涉及学生、申请人、两级审批、派单与维修角色；不要指向生产或共享验收环境。

下面假设你已经单独准备了监听 19382 的打包测试实例；19382 不是安装默认端口。请替换为自己的隔离目标，并使用同一目标的密码文件。不要指向既有 19282 交付实例。

```sh
cd tests/browser
npm ci
npm run install:browser
STUDYROOM_E2E_BASE_URL=http://127.0.0.1:19382 \
STUDYROOM_E2E_ALLOW_MUTATIONS=1 \
STUDYROOM_TEST_PASSWORD_FILE=/absolute/private/state/secrets/demo_password \
npm test
```

**打包版本前置：** [global-setup.mjs](global-setup.mjs) 读取 `/version.json`、`/api/v1/version`、`/api/v1/repairs/version`，要求三个 releaseId 非空且一致、commit 一致且为完整 40 位 SHA。8000 原生开发页不等于这个正式目标；源码容器的 development 标识也不能绕过检查，应按安装指南准备版本化发布。

`STUDYROOM_TEST_PASSWORD_FILE` 推荐读取现有随机秘密；CI 也可使用 masked `STUDYROOM_TEST_PASSWORD` 环境变量。不要将密码写进命令实参、测试源码或提交文件。`npm run list` 仅检查测试加载，不连接应用，也不等于业务验收。`npm run test:headed` 用于可视化复查，但不会自动触发 `pretest`，先执行 `npm run test:report-safety`。

CI 可直接在已启动隔离应用的 job 中执行上述命令；Linux runner 首次用 `npx playwright install --with-deps chromium` 安装固定浏览器及系统依赖。设置可从 runner 到达的 `STUDYROOM_E2E_BASE_URL`，不要把桌面机的 localhost 当成 runner 的应用。测试单 worker、无自动重试，避免失败操作被自动重复。环境创建/迁移/回收由外层 DevOps job 管理，测试不会删除数据库或卷。启动时校验 Web/core/repair 的 commit 与 releaseId 完全一致，并生成 `run-metadata.json`；可设置 `STUDYROOM_E2E_EXPECT_COMMIT` 校验预期的完整 40 位提交。

## 覆盖范围

1. 错误密码收到真实 401、错误提示、成功登录、刷新后会话保持、学生无管理页面权限。
2. 真实可用性查询→选择座位→创建预约；第二浏览器页取消后，旧页产生真实 409，确认按钮禁用并提示重新选择；375px 查看取消历史→再次预约，核对教室/座位带入、重新选择时间和请求真实余位→新编号，原取消记录保持不变。
3. 申请人提交→不同院系/空间审批人分别通过→手机查看两级意见→撤销并释放教室；另一次申请验证驳回理由必填、空值不发请求、真实拒绝结果、刷新后不能再审批，以及申请人手机历史可见理由。
4. 手机报修→派单人分配→维修人开始（等待实际资源写回）→完工→报修人验收（等待实际恢复）→关闭；验证报修人不能开始维修、维修人不能自验。
5. 系统管理员搜索真实空结果，并确认其没有学生预约页面权限。
6. 375px 导航可点击、页面及弹层不横向溢出；CDP 增加真实网络延迟观察 loading；浏览器断网观察错误与恢复重试；不存在的业务编号显示真实错误和 requestId。

7. 11 个基础账号逐一检查默认任务入口、全部适用页面、桌面与 375px 导航；新增授权跨院系教室名称回显/保存、真实签到屏布局检查。签到屏不生成含明码截图。

业务操作均点击实际页面，不 mock/intercept/fulfill API，不替换日期或服务状态。网络延迟和断网只改变浏览器真实网络条件。`finally` 清理由真实公共接口撤销本测试创建的记录；已进入维修的工单通过真实完工/验收流程释放资源，清理失败会明确失败。测试账号使用基础 demo 身份，各 test 串行运行；失败后应查看清理结果再重新运行。

## 证据与安全

输出在仓库忽略目录 `output/browser/`：JUnit、JSON、HTML、发布版本元数据和按场景截图。成功与失败都保留需要的状态截图。为防止 auth 请求体、Cookie 或密码输入步骤进入附件，默认关闭 trace/HAR/video；密码通过不含值的 DOM 输入事件函数填入。Playwright 1.64.1 的自动 ARIA 失败快照会包含密码框值，因此配置关闭自动页面文本快照，并在所有发布 reporter 前对断言自带的快照、错误和文本附件中的测试密码作脱敏。`npm test` 先执行真实 Chromium 失败报告探针：填入随机非秘密标记、故意失败，然后检查控制台、JUnit、JSON、HTML 及其内嵌 ZIP 数据不含标记。探针不连接应用，临时报告自动删除；可独立运行 `npm run test:report-safety`。不要在 CI 另外打开 `--trace`，也不要上传浏览器 storageState。

CI artifacts 可收集 `output/browser/`，JUnit 路径为 `output/browser/junit.xml`。界面截图、断言和源码错误上下文仍包含测试业务数据，只应在项目受控的 CI artifacts 中保存。执行结果以实际报告为准；测试加载成功不能冒充真实浏览器通过。

历史实际执行结果见 [REVIEW.md](REVIEW.md) 和[证据索引](../../docs/evidence/README.md)。每条记录只代表其原版本及范围；本说明更新不代表重新运行浏览器。
