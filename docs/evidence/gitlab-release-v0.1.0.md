# v0.1.0 GitLab 发版与源码绑定

2026-10-23（Asia/Shanghai），[正式Release v0.1.0](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0)已创建，GitLab页面显示创建成功，并关联两次成功的test部署、源码和验收制品。用户已授权自主提交、发版及专用Runner；本次没有生产服务器或Site发布。

## 同一源码的三个完整门禁

| 引用 | Pipeline | 结果 | 真实集成与容器验收 |
| --- | --- | --- | --- |
| `ycy/campus-platform` | [683506](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683506) | backend、frontend、integration、container-release、package全部PASS | container [4481354](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4481354)：6/6浏览器，1.8分钟；真实隐私反证1/1 |
| `main` | [683689](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683689) | 五项全部PASS，总计35分42秒 | integration [4482291](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482291)：15分43秒，124运维测试、66真实PG、27期限收件人、响应丢失重放；container [4482292](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482292)：16分07秒，6/6浏览器、1.8分钟 |
| `v0.1.0` | [683743](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/683743) | 五项全部PASS，总计34分05秒 | integration [4482637](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482637)：16分50秒，同一完整集成清单；container [4482638](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482638)：13分00秒，6/6浏览器、1.5分钟，隐私反证957.42035ms；package [4482639](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482639)：56秒 |

三条pipeline的源码均为 `10799003cd38a28629dc5ea39835c8313b1e9d79`（S）。main由MR合入自动触发，tag由真实push触发。集成与容器任务按环境锁串行，标签集成曾等待main释放资源；等待时间不计为执行时长。各容器任务在Runner5666中实际构建core/repair/web、执行七份Flyway迁移、部署、核对健康/内部路径/三版本，运行真实Chromium，最后停止本轮应用。该CI环境只在任务寿命内存在，不冒充长期生产服务器。

## 线性历史与版本

[MR !1](https://code.agibot.com/yangchunyu/campus-study-room/-/merge_requests/1)已按Fast-forward合入main；git fetch核对远端main精确等于S，从原main `df767bc40f51323e0784cae19ac0f50d2feb2e6f` 增加67个逻辑提交、0个merge commit，未squash、未删除来源分支。GitLab保持Fast-forward、默认不squash、pipeline必须成功且skipped不算成功。

受保护annotated tag `v0.1.0` 的tag对象为 `257c481e0432183ec059581179b95f0e93127f36`，远端peeled commit为S。没有移动或重建tag。运行版本与后续纯文档/验收归档提交分开记录；这些文档不触发本机发行镜像重建，源码包和镜像仍固定S。

| 交付物 | 精确身份 |
| --- | --- |
| 本机清单 | `r-20261023T022529-1079900`，SHA256 `5141bc6e20a95f62411dd65cdae9e2b7c2d39f5e824fa4402c7709d0bf63a000` |
| 包内源码 | 450个文件，与S逐字节及Git执行位核对；SHA256 `229242d0dd0d3b49e14e56c154dd2f005480220e8bbfec54e656ef273c0fd739` |
| 运输归档 | `.local/releases/v0.1.0/campus-study-room-v0.1.0-linux-arm64.tar.gz`，414289744字节 |
| 运输SHA256 | `7b143161a31778be71ebabc20a1dffbc8d744cda454335df1fa82b3f571be7ee`，旁附`.sha256` |
| main CI发行号 | `ci-683689-10799003cd38` |
| tag CI发行号 | `ci-683743-10799003cd38` |

CI和本机镜像分别构建，发行元数据和镜像ID不同；两者绑定同一S，不混称同一清单。运输包的逐文件核验见[入库元数据](release/final-v6-archive-verification.json)，本机实测见[正式导入](release/final-v6-portable-import.json)、[独立浏览器](frontend-release-final-v6.md)和[正式回退](release/final-v6-v5-rollback.json)。

## 可访问制品与证据保留

正式Release附两项可下载链接：

- [标签CI源码包、SHA256SUMS与build.json](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482639/artifacts/download)
- [标签容器清单、运行结果和真实浏览器报告](https://code.agibot.com/yangchunyu/campus-study-room/-/jobs/4482638/artifacts/download)

主代理实际点击Keep并回读确认main集成/容器及tag集成/容器/源码任务的到期提示消失。正式Release的自动收集证据为[v0.1.0-evidences-31018.json](https://code.agibot.com/yangchunyu/campus-study-room/-/releases/v0.1.0/evidences/31018.json)。附件仍受仓库访问权限控制。CI源码制品不含离线镜像；约395MiB的五镜像便携包是本机交付文件，未宣称已经上传为GitLab下载附件。该GitLab实例没有启用容器registry。

本机原始可见日志和截图保留在`output/gitlab/`：`main-{integration,container}-passed.txt`、`main-pipeline-passed.{txt,png}`、`tag-{integration,container,package}-passed.txt`、`tag-pipeline-passed.{txt,png}`及`v0.1.0-release-created.{txt,png}`。这些忽略目录文件不随Git源码自动分发；上面的GitLab链接提供远端对应证据。提交前的限定隐私检查见[最终报告](runtime-audit-final-v6.md)，不能扩大为对所有未来日志的保证。

## 历史失败与边界

683462在官方Alpine HTTPS CDN下载Python/Git时失败，业务构建尚未执行；683500的后端/前端通过后，为安装文档收口取消，不能写成全绿。HTTPS镜像修正、原TLS/签名和五项阻断任务保持，见[修正证据](gitlab-alpine-mirror.md)。此前共享Runner的挂载拒绝、探针竞态、负面MR门禁和业务缺陷反证保留各自原始结果。成功发版不覆盖这些历史。

当前交付为本机与可迁移安装、Mailpit和linux/arm64包；正式服务器、域名/TLS、外部SMTP、异机和AMD64验收按[ADR0002](../adr/0002-delivery-and-gitlab.md)保留边界。P1自然语言Agent未启动，不伪造人工验收签字。专用Runner按用户授权持续用于项目11753的确切可信保护引用，凭据只在VM中，未扩大授权。
