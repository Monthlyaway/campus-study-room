# 隐私、伦理与工程风险记录

[文档首页](README.md) · [安全设计](architecture/security.md) · [用户常见问题](user-guide/faq.md) · [历史证据](evidence/README.md)

更新于2026-10-22。只使用合成演示账号与验收夹具；下列结论以关联测试的实际断言为限，不将代理评审标成人工签字。

| 问题 | 已采取的措施 | 可核对证据与边界 |
| --- | --- | --- |
| 签到码可转发，不能证明实际在场 | 不采集定位；统计称“预约/签到”，不称实际出勤；普通接口不返回明码、hash或密文；昨日/他室码拒绝，失败计数限流 | `66f2b92`的[真实HTTP/PG矩阵](evidence/core/acceptance-matrix.md)覆盖时间、房间、归一化、普通接口隔离与第6次429；`09ceb46`补非法version也计数。授权屏幕显示码属于明确功能，不对其作防拍摄保证 |
| 维修影响使用者，可能被错误计违约 | 维修取消/中断不产生违约；释放维修来源不复活已取消订单，不移除其他来源 | [同室五态与并发级联](evidence/core/atomic-workflows.md)核对3条对应通知/审计、零违约、释放不复活；[维修验收](evidence/repair-acceptance.md)验证独立验收和范围。完整进程故障矩阵另行记录 |
| 管理员或维修人员越权读取、审批 | 功能与同一角色绑定的范围同时校验；系统管理员不自动获得业务审批权；禁止申请人自批、同人两级、维修人自验 | `66f2b92`、`09ceb46`的[权限矩阵](evidence/core/typed-inputs.md)包括学生9读/19写受拒、跨室范围拒绝；[M3负面HTTP](evidence/repair-acceptance.md)核对报修人兼维修人兼物业也不能自验，失败不改变状态 |
| 统计被误用于个人排名 | 采用授权范围内的聚合预约/签到指标；普通学生不可访问管理统计，不提供个人违约排名 | [固定数据手算与API对照](evidence/core/acceptance-matrix.md)验证7预约、2取消、1未签到、签到率3/4、7.5座位小时以及空集null；不能据此推断学习投入或真实在场 |
| 调试报告可能泄露密码与会话 | 曾在故意失败的Playwright ARIA上下文中发现密码值；已在报告器最先脱敏错误、步骤和文本附件，并关闭trace/HAR/video、遮挡截图；上传前另扫描并隔离含秘密或无法解析的嵌入ZIP | `6c464db`及`tests/browser/artifact-safety.test.mjs`真实Chromium故意失败反证；`tests/ops/test_private_artifacts.py`验证明文、压缩报告及损坏报告隔离。扫描只能覆盖提供的秘密清单，不能代替全部隐私审查 |
| 非结构化异常日志暴露业务正文 | 请求关联日志采用固定字段，不输出Cookie、密码、完整签到码或异常正文 | `51439f8`结构化日志与`tests/integration/logging_http.py`实际断言。跨服务关联与最终发布的全部日志扫描单独归档，不把一次扫描写成永久保证 |
| AI实现遗漏真实用户体验或异常路径 | 分模块代理实现，另一代理实际使用桌面与375px页面；保留失败记录并复测，不以mock页面代替系统 | [独立浏览器报告](../tests/browser/REVIEW.md)及[证据索引](requirements-traceability.md)。首次点击未发送请求的失败保留原记录，后续通过不能证明其原因已被定位 |
| 同机多个环境共享宿主硬件 | 每环境使用独立Compose项目、网络、卷、随机秘密；运维以环境锁串行化 | [独立离线安装](evidence/release/portable-install.json)核对新源码、五镜像及七份独立秘密；[备份/重建](evidence/release/backup-and-recreate.json)核对原会话和数据。相同Docker daemon不代表不同服务器的故障隔离 |
| GitLab没有内置镜像仓库，迁移交付可能依赖浮动镜像 | 使用固定五镜像离线包、源提交、内容ID及SHA-256；升级、失败恢复和回退绑定完整三应用组合，数据库不降级 | [A/B发布与恢复](evidence/release/README.md)已实际执行。导入不执行pull/build；本次没有断开外网，也尚未实测另一服务器或AMD64导入 |
| 正式邮件/TLS环境尚未给定 | 按用户选择以本机可迁移交付和Mailpit收信验收，正式配置通过环境与秘密文件注入 | [ADR 0002](adr/0002-delivery-and-gitlab.md)。真实SMTP失败/恢复与收件对象已验证；Mailpit结果不冒充正式邮箱、域名、TLS或生产发布验收 |
