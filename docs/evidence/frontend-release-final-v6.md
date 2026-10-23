# 最终V6真实浏览器验收（2026-10-23）

V6正式离线导入后，对 `http://localhost:19282` 执行既有完整六流程，**6/6通过，113.061秒，0 skipped / unexpected / flaky，无重试**。发布号为 `r-20261023T022529-1079900`，完整源码为 `10799003cd38a28629dc5ea39835c8313b1e9d79`。启动门禁实际读取Web `/version.json`、core `/api/v1/version`、repair `/api/v1/repairs/version`，三个版本完全一致，并匹配显式预期提交；测试源码与该提交无差异。

唯一一次业务运行开始于 `2026-10-23T02:29:46.917Z`（上海10:29），完整命令于 `02:31:40.050660Z` 以0退出。使用Node24.22.1、固定Playwright1.64.1与真实Chromium，单worker，桌面1440×1000、手机375×812。没有mock API、修改时钟、跳过断言或自动重试。密码只读取既有私有文件，不进入命令实参或报告。

## 实际操作与结果

| 流程 | 实测结果 |
| --- | --- |
| 登录与权限 | 错误密码真实401、正确登录、刷新保留会话；学生不能进入管理功能。 |
| M1预约与历史 | 实际查询余位、创建预约；另一页面取消后旧页面收到真实409并禁用确认；手机历史再约预填原教室/座位，重新查询后生成新编号，原历史保持。 |
| M2审批与拒绝 | 不同人员逐级审批，申请人手机撤销；空拒绝理由无法提交，实际拒绝后不能再次审批，申请人可见理由。 |
| M3维修与资源 | 手机报修、派单、开始维修、资源下架同步、完工、独立验收、恢复同步和关闭；维修人姓名可读，终态没有活动截止提示，第二次报修待房间就绪后提交并取消。 |
| 管理空态 | 搜索不存在用户显示真实空结果；管理员不能使用学生预约动作。 |
| 手机与异常 | 375px导航/弹层可点击且无横向溢出；实际网络延迟有加载反馈，断网后恢复重试；不存在编号显示真实错误与requestId。 |

本轮创建的活动预约/申请通过真实公共接口取消，维修沿实际完工/验收路径关闭，finally清理没有失败。用例监视范围内没有JavaScript异常或CSP拒绝；命令日志保留Node关于NO_COLOR/FORCE_COLOR的非业务警告，不声称日志无警告。没有重复午夜签到、性能负载或额外慢网络专项。

## 独立试用反馈

本代理实际执行六流程并目视复核以下三张截图。桌面列表的时间、状态及主操作清楚；手机拒绝原因、审批人员和维修人员可读，详情未横向裁切，未发现新的使用阻塞。历史累计后列表较长，但筛选和分页入口均可见，没有为整理界面删除旧记录。界面延续浅色背景、绿色操作和紧凑导航。

- [桌面预约](../../output/browser-release-final-v6/results/studyroom-M1-availability--d8c41-se-create-a-new-reservation/desktop-reservation-created.png)
- [手机拒绝原因](../../output/browser-release-final-v6/results/studyroom-M2-independent-a-9a2a7--a-mandatory-visible-reason/mobile-rejection-reason.png)
- [手机关闭工单](../../output/browser-release-final-v6/results/studyroom-M3-mobile-report-81b90-chronization-and-acceptance/mobile-repair-closed.png)

截图为full-page长图，手机交互视口仍为375×812；视口外的底层历史列表不代表详情弹层超过可用视口。

## 隐私反证与原始归档

`npm test`前置的真实Chromium失败报告反证 **1/1通过，1287.548ms**：填入随机非秘密密码标记、故意触发断言失败，再检查控制台、JUnit、JSON、HTML及内嵌ZIP不含该标记。探针不连接应用，临时目录随后清理。

61份原始产物对本环境7份已知运行秘密扫描0命中，1个HTML内嵌ZIP的2个成员已解码检查。未开启trace/HAR/video，不保存Cookie或storageState，也未读取签到码。这个结论只覆盖上述已知值和本轮报告，不代替后续限定审计或GitLab日志审计。

归档 `output/browser-release-final-v6/` 共63份文件；`SHA256SUMS`覆盖除自身外62份。包含命令元数据/日志、JUnit、JSON、HTML、三版本元数据、18张场景截图及reporter附件、[核验结果](../../output/browser-release-final-v6/verification.json)。业务与隐私探针结束后已通知主代理和发布代理释放宿主；后续仅复核现有文件与截图，没有新的浏览器或服务操作。

本次证据精确绑定V6，V5/R4及早期失败报告保持原样。历史跨升级午夜签到、R4性能及恢复保留各自真实版本和条件；本轮通过不等于main流水线、tag或正式Release已经完成。运行源码身份与后续纯文档提交HEAD分别记录，不为归档本文重新构建镜像。
