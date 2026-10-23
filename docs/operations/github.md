# GitHub CI、镜像与发布

[运维导航](README.md) · [开发环境](../development/local-setup.md) · [交付决策](../adr/0004-github-codex-lb-production.md) · [发布与回退](../modules/release-operations.md)

当前仓库是私有 [Monthlyaway/campus-study-room](https://github.com/Monthlyaway/campus-study-room)。开发、GitHub Actions runner、发布和生产运行均在 codex-lb；GitLab 的历史提交与证据保留，但不再作为新修改的验收平台。通过 [Actions](https://github.com/Monthlyaway/campus-study-room/actions)、[Pull requests](https://github.com/Monthlyaway/campus-study-room/pulls)、[Releases](https://github.com/Monthlyaway/campus-study-room/releases)查看各自结果；不要把“已创建 workflow”当作“已运行成功”。

## 接手开发与 runner

从控制电脑连接服务器后工作：

```sh
ssh codex-lb
cd /home/ubuntu/Codes/campus-study-room
git status --short --branch
git fetch origin
```

先检查未提交变更和当前分支，再创建自己的 `ycy/` 工作分支。所有代码编辑、构建、commit 和 push 在上述服务器执行。不能在 Mac clone 后直接推 GitHub，也不能使用现有生产目录做测试状态目录。

服务器资源约为 2 核、2 GiB RAM、4 GiB swap。Actions 使用本仓库专用 self-hosted runner 2.338.1，Docker 29.1.3 / Compose 2.40.3（Ubuntu 包完整版本 `2.40.3+ds1-0ubuntu1~22.04.1`，CI 严格比对完整值）；版本来源见[依赖基线](../dependency-baseline.json)。工作流以 `campus-study-room-codex-lb` concurrency 组串行调度，构建与集成按阶段执行；部署还持有生产环境文件锁。运行前核对 runner 在线、目标仓库和完整提交。只允许可信项目代码使用该 runner；不可信 fork/PR 不应取得服务器或生产访问能力。

## 检查和合入

工作流按 `verify → publish → deploy` 顺序执行：verify 负责验证；publish 和 deploy 只在版本 tag 发布路径执行。deploy 单独绑定 `production` environment，使用只读 contents/packages 与 deployments 写权限，可在部署失败后独立重跑。main 的 push、目标为 main 的 PR、版本 tag push 和手动 `workflow_dispatch` 会触发工作流；工作分支通过 PR 验证，不再同时对 feature push 重复运行，以免占满 2 GiB 服务器。需要独立调试时可在可信 `ycy/` 分支手动 dispatch。实际执行还要求 owner actor/triggering actor、同仓库可信引用，准确条件以[工作流源码](../../.github/workflows/ci.yml)为准。每次改动必须实际运行完整 `./scripts/check`，包括编译、生成客户端差异、类型/单元检查和真实 PostgreSQL/HTTP/SMTP 测试；镜像发布还要完成隔离 test 部署、三应用版本核对和真实浏览器 E2E。失败、缺失、零用例、必需套件跳过或旧 SHA 的绿色结果均不能当作当前候选通过。

合入顺序：

1. 在工作分支完成修改，运行针对性测试和完整门禁，检查 diff、生成物、秘密与文档链接，保留逻辑 commit。
2. 推送工作分支并创建 PR。比较最新 main，必要时 rebase 并重新执行新 SHA 的验证。
3. 复核 PR 的最新 head SHA、全部必要 Actions 结果及其真实报告。失败的候选继续修复，不能删检查、降低断言或手写成功结果。
4. 使用 GitHub rebase merge，不 squash，不产生 merge commit。核对 PR 已合并、main 历史线性、每个逻辑提交仍存在。
5. 核对 main 对应工作流，再将通过 test 的不可变镜像组合晋级发布和生产。

当前私有仓库的 branch protection API 返回需要升级套餐的 403，因此这里是实际执行的维护流程与脚本校验，不是 GitHub 已强制、不可绕过的分支保护。不能把 owner 自主授权写成不存在的第二位人工审批或 required reviewer。若后续套餐支持，再配置 required checks/环境保护并新增验证记录。

## Packages、Release 与生产

三应用仓库采用 `ghcr.io/monthlyaway/campus-study-room-core`、`campus-study-room-repair`、`campus-study-room-web`。镜像必须保持 Private，版本由完整 commit、Release/tag 和 registry digest 追溯。生产只推广实际通过隔离 test 的组合；重新构建、移动 tag 或改 manifest 不会继承先前通过结果。

Release 应保存源码、清单、校验和与 Actions 运行链接。源码归档不包含运行镜像、秘密或数据库，不等同五镜像离线包。测试报告不能包含密码、Cookie、完整业务响应或私有状态。GitHub artifact 有保留期，不能代替长期保留的发行清单和镜像。

生产配置、证书、运行版本与备份位置以[当前实例接手卡](current-instance.md)为准。新环境需要自己的随机秘密和首次管理员；不能导入 demo 账号，不能复用 CI 数据。发布保持维护/暂停、排空、备份、独立迁移、三版本与入口验证；回退只切换兼容应用，数据库不降级。

## 发布命令与产物

[CI 工作流](../../.github/workflows/ci.yml)的 `verify` 先执行完整检查，再运行 `python3 scripts/ci-release.py`。它要求真实 GitHub Actions 身份和当前 checkout SHA；分支候选使用 `ci-<run>-<attempt>-<sha>` 身份，版本 tag 候选使用 tag 本身。每次 run 使用自己的 Compose project、状态目录及随机回环端口；结束时停止并移除本 run 的容器/网络，保留命名卷与私有诊断状态。

测试产物为 `output/container-release/manifest.json`、`result.json`、`services.log` 和 `output/browser/`；报告上传前经过秘密检查，artifact 名为 `verified-release-<runId>-<attempt>`。实际报告必须包含完整真实容器/浏览器成功证据及清单 SHA，不能手写 passed 或复用其他 run。

仅版本 tag 的 `publish` job 在 `verify` 成功后执行：

```sh
python3 scripts/github-release.py publish
```

随后独立的 `deploy` job 下载本次已发布的 `published-release-<tag>` artifact，并执行：

```sh
python3 scripts/github-release.py deploy output/github-release
```

这两条由 workflow 在服务器执行，不应通过伪造 `GITHUB_*` 环境变量从开发 shell 模拟通过。workflow token 通过 `GH_TOKEN` 注入，使用临时 Docker 凭据目录，不写入源码或命令参数。publish 要求候选在 main 历史中、工作区干净、本 run 测试匹配；已存在的 Release 或镜像版本拒绝覆盖。

发布先将通过 test 的三个 image ID 推到 GHCR，再按 registry digest 重新 pull 并比对 image ID。`output/github-release/` 包含 `manifest.json`、`tested-manifest.json`、`container-test.json`、`source.tar.gz`、`build.json`、`SHA256SUMS` 及 Release 说明。manifest 记录 `ghcr-digest` 传输格式、每个应用的 digest 引用和内容 ID，以及 PostgreSQL/Mailpit 固定运行镜像。`tested-manifest.json` 保存 test 实际验证的原清单；推广校验将测试 run/attempt、commit、releaseId、迁移/配置摘要和三应用 image ID 绑定到发布清单。GitHub Release 从草稿变为发布后，deploy 再核对稳定 Release、tag SHA、GitHub 发布资产的服务端 digest、私有包及拉取镜像的内容 ID，从同一源码归档部署；相邻本地校验文件本身不作为发布者身份依据，不重建应用镜像。

每版源码解到 `/home/ubuntu/Deployments/campus-study-room/releases/<tag>/source`，清单同目录上一级保存。重试不会覆盖正在被容器挂载的源码；每次加载前都按原 source 归档重新核对各路径的类型、权限和文件摘要，拒绝 symlink，不能只依赖 source.sha256 标记。无关生成缓存可以保留，部署器直接使用已核对源码。生产长期状态固定为 `/home/ubuntu/.local/state/campus-study-room/prod`；checkout、CI 清理和下一版解包不得移除它。

## 首次准备生产状态

以下只在 codex-lb 的**全新生产状态目录**执行一次；已有状态先走[接手与巡检](runbook.md#先确定操作对象)，不能重新生成秘密修复旧库。

```sh
cd /home/ubuntu/Codes/campus-study-room
./scripts/init-env prod --directory /home/ubuntu/.local/state/campus-study-room/prod
```

在服务器私下编辑新目录的 `compose.env`，填写 `PUBLIC_BASE_URL=https://49.51.193.169`、首次管理员 `ADMIN_USERNAME` 与 `ADMIN_EMAIL`；不要向日志输出整个文件。保留 `COOKIE_SECURE=true`、`BIND_ADDRESS=127.0.0.1`、Web 18443、Mailpit 18028。宿主 HTTPS 反代转到回环 Web；公网只放行 TCP 80/443，SSH 保持既有规则，数据库和 Mailpit 不开放。

初始化器生成 0700 状态目录及 0600 随机秘密，生产只生成 `secrets/admin_password` 而不生成 `demo_password`。管理员密码通过只读秘密挂载交给一次性迁移器，不放入 argv 或普通环境配置。首次生产部署按 migrate → reference → bootstrap 初始化管理员；后续升级不重新 bootstrap、不重设密码。生产拒绝 HTTP origin、非 Secure Cookie、公开应用绑定及 demo 状态/账号，原生 `deps`、`migrate`、demo/install 开发入口不能用来替代生产发布。

默认资源预算为 core 512 MiB、repair 448 MiB、PostgreSQL 256 MiB、Mailpit 96 MiB、Web 64 MiB；两后端分别使用 192 MiB Java heap、128 MiB metaspace 与 6 个数据库连接。迁移器容器限制 384 MiB、Java heap 128 MiB；PostgreSQL 使用 64 MiB shared buffers、最多 40 个连接。配置键在新 `compose.env` 中，不能因一次 OOM 把所有限制取消或并行运行更多构建。预算是限制，不是实测容量；首次上线与 CI 共同运行时检查内存、swap、OOM 和入口健康。

证书、反代和首次生产配置就绪后，由正常版本 tag 工作流推广候选。成功信号是 published Release、完整部署步骤成功、三应用 digest/commit/release 一致、HTTPS 登录页和 readiness 正常。bootstrap 只建立系统管理员，不授予业务审批资格；正式用户与授权由管理员按[用户管理工作流](../user-guide/workflows.md)配置。备份调度与恢复验证按[运维手册](runbook.md)，不能把成功启动等同数据保护已经通过。

## IP HTTPS 与续期

codex-lb 没有域名，使用 `49.51.193.169` 的公开受信任 IP 证书。宿主 nginx 负责 TCP 80 的 ACME 验证/HTTPS 跳转和 TCP 443 的 TLS，再反代到回环 `18443`。证书私有状态在 `/home/ubuntu/.local/state/campus-study-room/tls`，不进入源码、镜像或 Actions artifact。配置脚本为 [setup-https.py](../../scripts/setup-https.py)，首次执行需要已安装 nginx、官方 Certbot 5.4.0、可用的免交互 sudo、80/443 已放行和实际维护者邮箱：

```sh
python3 scripts/setup-https.py --ip 49.51.193.169 --email maintainer@example.org
```

示例邮箱必须替换为实际维护者邮箱。脚本只管理 `/etc/nginx/conf.d/campus-study-room.conf` 和本项目证书 timer，不应手动复制私钥到源码。证书路径使用 `tls/config/live/49.51.193.169/`；到期时间会随真实续期更新，首次签发与本次验证事实见[接手卡](current-instance.md)。

系统 `campus-study-room-certificate.timer` 每天 00/06/12/18 点检查，最多随机延迟十分钟，持久化补做错过的检查；`campus-study-room-certificate.service` 用 Certbot renew，在实际续期成功后执行 nginx reload。查询和模拟续期：

```sh
systemctl status campus-study-room-certificate.timer --no-pager
systemctl list-timers campus-study-room-certificate.timer --all --no-pager
/home/ubuntu/.local/tools/certbot/bin/certbot renew --dry-run --run-deploy-hooks \
  --config-dir /home/ubuntu/.local/state/campus-study-room/tls/config \
  --work-dir /home/ubuntu/.local/state/campus-study-room/tls/work \
  --logs-dir /home/ubuntu/.local/state/campus-study-room/tls/logs
```

`--dry-run --run-deploy-hooks` 使用模拟续期并验证 reload hook；通过不代表生产证书已经换新。timer enabled 也不独立证明下次自动执行成功。证书失败查 timer/service 日志、ACME HTTP 可达性及 nginx 配置；不要用 `curl -k` 或关闭 Secure Cookie 掩盖故障。

## 失败时检查什么

| 现象 | 检查 | 继续条件 |
| --- | --- | --- |
| Actions 排队 | runner 在线/忙状态、标签、可信仓库与事件、串行队列 | 恢复现有 runner 或等当前任务结束，不临时放开不可信引用 |
| 依赖下载失败 | 官方源、DNS、TLS、固定版本与摘要、可用磁盘 | 修复网络或下载条件，保留冻结依赖和所有校验 |
| 进程退出 137 / OOM | 主机与容器内存、swap、并行构建和生产健康 | 修复资源竞争或预算并重跑，不能只跳过耗内存测试 |
| 集成失败或零用例 | 本次 result/JUnit、数据库/HTTP/SMTP 断言、套件实际执行 | 修复后同一候选的所有必要套件通过 |
| 镜像或浏览器失败 | build、隔离部署、健康、commit/digest、E2E 阶段 | 同一组合真实通过，不用 mock 或只构建替代 |
| GHCR / Release 写入失败 | 仓库 owner、workflow token 的 packages/contents 权限与私有访问 | 修复授权后恢复同一候选发布，不上传到公开替代仓库 |
| 生产 deploy job 失败 | pending/schema/current 清单、维护标记、日志、备份 | 排除真实原因后仅重跑失败的 deploy job，复用已发布 artifact；按[发布故障流程](runbook.md#发布失败或入口持续-503)处理，保留数据与失败证据 |

publish 仍要求同一次 run attempt 的测试证据，且拒绝覆盖已有 tag 的 Release/Packages。部署失败时不要重跑全部作业来重新发布同一 tag；独立 deploy 会重新核对已发布资产和镜像，再从保留的 pending 状态按正式门禁继续。若失败发生在 publish 且已经留下部分远端资产，先核实哪些不可变资产已存在，保留失败记录，不删除或移动 tag 来伪装首次发布。

旧 GitLab Runner、内网镜像绕行和 v0.1.0 历史说明保留在 [GitLab 归档手册](gitlab.md)，不用于新工作流授权或绕过失败。
