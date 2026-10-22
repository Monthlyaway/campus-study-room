# 本机安装、环境与发布运维

对应 REQ-ENG-03～07。当前交付范围是本机和可迁移安装，邮件使用 Mailpit。正式服务器、域名、TLS 与外部 SMTP 的验收按 ADR 0002 延后；`prod` 部署入口明确拒绝，避免把演示环境误用为正式环境。

## 环境入口

需要 Python 3.10+、Docker Engine/Desktop、Compose v2；应用编译在固定镜像中完成。推荐 4 vCPU / 8 GiB 或更高。

```sh
./install.sh test
python3 scripts/studyroom.py status test
```

第一次安装生成独立随机秘密，启动 PostgreSQL/Mailpit，构建三个应用镜像，执行 Flyway、reference 与 demo seed，等待全部应用健康。重复安装保留数据卷和秘密。已有 `current-release.json` 的环境使用 `deploy` 升级，不能再用 development 镜像覆盖。

| 环境 | 应用 | 本地邮件 | 原生开发 PG / SMTP | Compose 项目 |
| --- | --- | --- | --- | --- |
| local | 18080 | 18025 | 15432 / 11025 | studyroom-local |
| dev | 18081 | 18026 | 15433 / 11026 | studyroom-dev |
| test | 18082 | 18027 | 15434 / 11027 | studyroom-test |

默认只绑定本机回环地址。正常容器安装不发布数据库、两个后端和内部控制接口。原生开发额外发布 PG/SMTP 到回环地址；前端开发地址为 `http://localhost:8000`，两个 Java 服务为 8081/8082。原生模式一次使用一个开发环境，不与同端口进程并行启动。

`STUDYROOM_STATE_DIR` 可指定绝对状态目录；默认 `.local/<env>`。`compose.env` 中的环境名必须与命令匹配。为同名环境建立第二套隔离安装时，同时修改 `COMPOSE_PROJECT_NAME`、端口和 `PUBLIC_BASE_URL`，不能只换目录却复用原项目/卷。

## 配置和秘密

状态目录权限 0700，秘密文件 0600。`scripts/init-env` 不覆盖已有秘密。各服务使用独立 PostgreSQL 角色；迁移由 `studyroom_migration` 执行，业务角色无 DDL 权限且审计表只允许查询和插入。PostgreSQL 容器的入口在降权前读取挂载的角色秘密；Java 容器读取秘密后以 UID 10001 启动应用。

`secrets/demo_password` 是本地演示账号的随机共同密码。不要提交状态目录、日志、备份、会话、邮件或秘密。迁移已有数据库到另一服务器时，还必须通过受控渠道搬迁相应秘密，尤其是 `checkin_key`；重新生成签到码加密密钥会使已有加密码无法读取。发行镜像包不包含秘密或业务数据库。

## 不可变应用组合

```sh
./scripts/check
./scripts/build-release r-YYYYMMDDTHHMMSS-commit12
./scripts/deploy test .local/releases/RELEASE_ID/manifest.json
```

构建要求干净且已提交的 checkout。清单记录完整 commit、三个应用的内容 ID、Compose 和依赖基线摘要、全部迁移摘要与兼容系列。部署前核验本地镜像身份；不可变身份是 Docker image ID，当前未将 GitLab 未启用的容器仓库声称为可用。跨主机传输见[离线安装包](portable-release.md)。

同一环境用文件锁串行化安装、原生启停、依赖启停、独立迁移/seed、部署、回退、备份和恢复；首次初始化另有文件锁，防止并发生成覆盖密钥。真实三个独立进程已验证：同环境第二进程等待前一进程释放，另一环境可同时获取锁。发布先开启维护与任务暂停，等待公开入口返回 503、两个服务的在途请求和任务均归零，再正常停止旧后端。随后备份、迁移和 reference seed，启动三个目标应用，检查健康、三个版本标识和私有冒烟；通过后记录 current/previous 和历史并恢复入口。排空失败会恢复原服务入口，不继续迁移。失败后若维护仍在，先查看该环境日志、`pending-release.json` 与数据库迁移状态。

`release.env` 固定当前应用组合及离线运行镜像别名。后续 Compose 调用会读取它，避免重启时退回 development 镜像。发布历史与镜像不会自动清理；至少保留前三个已验证成功版本。

## 应用回退与数据库恢复

```sh
./scripts/rollback test .local/releases/PREVIOUS_ID/manifest.json
./scripts/backup test
./scripts/restore test .local/test/backups/TIMESTAMP.dump
```

应用回退更换三个应用镜像，不运行 down migration。若目标和当前或失败候选的迁移集合不同，要求绑定双方 commit 的兼容演练证据；不能仅凭“新增列”或人工写入 `passed` 就宣称已经实测。未满足兼容条件时保留维护，不能盲目重试。完整 V1→V2 新列→V1 应用回退的实际证据另行归档，当前进度见 `docs/progress.md`。

备份使用 `pg_dump -Fc`，保存 SHA-256 并用 `pg_restore --list` 验证可读性。恢复命令只创建新的隔离数据库，不替换在线库；恢复完成报告四类业务记录数量。数据恢复与应用回退是不同操作；不能用恢复旧备份替代回退而无说明地丢失新业务数据。禁止自动删除命名卷。

## GitLab 流水线

`.gitlab-ci.yml` 在 push/MR/tag 触发，包含后端 verify、前端生成漂移/类型/单测/构建、独立 PostgreSQL 服务中的两应用 HTTP 与四类真实 JPA 集成、丢失已提交 BLOCK 响应后的原命令重试，以及源码制品归档。Python 运维校验也进入集成门禁。每个真实 JPA 套件必须产生非零且无 skip 的对应 JUnit，不能用默认跳过数据库测试冒充成功。

CI 使用实例中在线的 `amd64` Runner（2026-10-22 已核对 #5170）；原 `k8s-amd64` 对应的 Runner 已长期离线。使用 `resource_group: studyroom-test` 串行化集成任务，秘密在每个 job 内随机生成。测试环境记录为 `test/ci-<pipeline>`；只有集成通过才允许打包。实际 pipeline、版本化 Docker 发布和完整自动部署的证据继续在进度表追踪；配置存在不等于门禁已执行。

## 已执行与待执行

已执行：独立 test Compose 首次安装；容器入口管理 2 项、预约/审批 5 项、维修 2 项真实 HTTP；Linux 0600 秘密读取启动；维护 503、任务排空、内部接口 404 和恢复 200；隔离数据库备份恢复；三个应用生产入口与 375px 浏览器试用。

待形成完整发布证据：版本化组合升级与兼容回退、离线包独立安装、真实 GitLab pipeline 正反门禁、自动 test 升级与保留卷重建。未执行项不计为通过。

### CI 镜像拉取诊断

流水线 [682485](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/682485) 已被在线 Runner #5170 接收，但 Kubernetes 在执行脚本前访问 Docker Hub 超时。CI 因此默认使用 `STUDYROOM_CI_REGISTRY=m.daocloud.io/docker.io/library`；三个镜像保持原厂版本及原 SHA-256，未改用可变 latest。已从该缓存读取并核对相同 manifest；实际 Runner 复跑结果仍需核验。该值可按部署网络改为 `docker.io/library`，不是全机 Docker 配置。缓存机制依据 [DaoCloud 官方说明](https://github.com/DaoCloud/public-image-mirror)，缓存不可达时任务失败，不绕过内容校验。

镜像缓存已使 [682503](https://code.agibot.com/yangchunyu/campus-study-room/-/pipelines/682503) 的任务开始运行，暴露两个独立问题：官方 Maven 容器将 `MAVEN_CONFIG` 用作目录，wrapper将其解释为命令行参数；CI显式清空该变量，缓存路径仍由 `MAVEN_OPTS` 指定。Node镜像安装Git时系统源的HTTP 80端口超时；CI仅将原官方APT源改用HTTPS，保留TLS与包签名校验，限制连接超时和重试。上述失败已保留，后续以真实任务结果判断。
