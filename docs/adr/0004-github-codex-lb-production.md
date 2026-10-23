# ADR 0004：GitHub、codex-lb 与生产交付

日期：2026-10-23。状态：用户直接指示；执行结果由新证据分别记录。

## 问题与依据

用户已将仓库迁移到私有 [Monthlyaway/campus-study-room](https://github.com/Monthlyaway/campus-study-room)，要求完整保留 GitLab 分支、提交、作者与时间，并明确后续开发不再使用 Mac。随后要求在 codex-lb 调试，GitHub Actions 也使用 codex-lb 的资源，完成持续提交、Packages、Release、生产部署，最后由工作分支提交 PR 并 rebase merge 到 main，默认不 squash。服务器为 2 核、约 2 GiB 内存，已有 4 GiB swap；公网 IP 为 `49.51.193.169`，没有域名。

这些当前指示替代 [ADR 0002](0002-delivery-and-gitlab.md) 中 GitLab 为现行平台、仅本机/可迁移交付和延后正式服务器的边界。ADR 0002 与旧验收报告保留原日期、原版本和原结果。三份根目录规格不重写。

## 决策

1. **开发与发布位置。** GitHub 私有仓库是当前协作入口；源码编辑、构建、测试、提交和 Git push 均在 codex-lb。Mac 只用于 SSH/浏览器控制，不新增 GitHub remote，不从 Mac 上传代码。远程开发 checkout 为 `/home/ubuntu/Codes/campus-study-room`；CI checkout 与生产持久状态分别隔离。
2. **CI 与引用。** 使用仅服务此仓库的 GitHub Actions self-hosted runner，按同机资源预算串行执行构建、真实 PostgreSQL/HTTP/SMTP 集成、容器发布与浏览器测试。只运行仓库 owner 信任的代码；runner 标签不构成访问控制。生产数据和秘密不得挂入普通测试环境，不能将生产 URL 作为可写测试目标。
3. **依赖。** 使用官方源和已固定版本/摘要，修复实际网络问题；不沿用为公司内网准备的镜像绕行，不关闭 TLS、摘要检查、冻结锁文件或必要测试以取得通过。
4. **分支与合入。** 在 `ycy/` 工作分支保留每个逻辑提交，最新提交通过完整检查后提交 GitHub PR。需要时先 rebase 到最新 main 并重新验证；合并使用 rebase merge，默认不 squash、不生成 merge commit、不重写 main 已有历史。
5. **不可变发布。** GitHub Packages/GHCR 保存三应用镜像；GitHub Release 保存版本、源码、清单及校验和。生产推广 test 实际通过的同一组镜像，按 digest 和完整 commit 核对，不能重新构建另一组镜像后冒称同一候选。保留历史版本、镜像与迁移，不使用 `latest` 作为发布身份。
6. **生产。** codex-lb 是生产目标，采用生产环境配置和单独状态/卷，初始化随机秘密及首次管理员，不导入 demo 账号/业务。公网只提供 HTTP 到 HTTPS 跳转及 HTTPS 应用入口；数据库、内部 API、Mailpit 保持私网/回环。IP 证书、续期、备份、恢复与实际容量分别验证。外部 SMTP 未提供，Mailpit 的本机收件不能写成真实外部投递验收。
7. **授权与平台限制。** 用户在本任务直接授权自主提交、Release、配置与生产上线及最终 PR 合并；这份 owner 授权是本轮操作依据，不虚构第二位人工审批人。当前私有仓库 main protection API 返回套餐限制（403）；不能声称已获得 GitHub 强制 required checks、双人审批或不可绕过保护。脚本/工作流必须核对候选与通过证据，合入前复核最新 SHA 与所有必要结果；限制与实际设置在 [GitHub 手册](../operations/github.md)记录。

## 影响与验证

同机 2 核/2 GiB 与原规格的 4 核/8 GiB、CI 独立主机不同，这是用户明确选择的运行条件。限制进程内存并串行构建只能控制竞争，不能证明原容量目标已经在新主机达到；需要按真实负载和资源指标报告。swap 也不等于可用物理内存。

共享宿主机仍有共同故障与权限边界：持有宿主 Docker 权限的可信 runner 代码可影响宿主资源，因此不接纳任意 fork 或不可信 PR。项目/路径隔离不能被描述成独立主机级安全隔离。

业务权限、时间规则、数据库资源裁决、独立迁移、维护/排空/备份/回退门禁、秘密保护和测试断言不因平台迁移而缩减。GitHub 迁移、AMD64 发布、TLS、恢复、真实浏览器和生产运行按各自实测新增证据；旧 GitLab/ARM64 通过记录不自动覆盖这些新条件。当前结果以 [进度](../progress.md)、[证据索引](../evidence/README.md)及生产接手记录为准。
