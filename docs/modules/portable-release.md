# 可迁移发布包：格式、导出与校验

对应 REQ-ENG-03/05/07。第一次安装请走[安装指南](../operations/installation.md#从离线包安装新环境)，维护既有实例请走[升级流程](../operations/runbook.md#升级既有实例)，当前正式包位置与摘要见[接手卡](../operations/current-instance.md#当前可搬运的安装包)。本页面向准备发行包或排查校验失败的维护者。

`scripts/portable_release.py` 把已验证发布清单导出为完整目录；`scripts/import-release` 校验后调用正式部署器。目标需要 Python 3.10+、Docker Engine/Desktop 与 Compose v2，不需要 Git/Java/Node 或编译依赖下载。支持 local/dev/test，prod 拒绝。

## 发布包内容

| 文件 | 用途 |
| --- | --- |
| `manifest.json` | 完整提交、三个应用镜像内容 ID、Compose/依赖基线/全部迁移摘要 |
| `portable.json` | 格式/提交/平台、五镜像来源与归档引用、内容 ID、OS/architecture/variant、主要文件摘要 |
| `images.tar` | docker save 导出的 core、repair、web、PostgreSQL、Mailpit 五镜像 |
| `source.tar.gz` | git archive 指定提交源码；PAX comment 保留完整提交 SHA |
| `INSTALL.md` | 该包版本、安装步骤、平台与数据边界 |
| `SHA256SUMS` | 前述五文件摘要，不递归计算自己 |

指定提交的源码不读取未提交工作区；导出拒绝运行/秘密/依赖/编译目录、日志、数据库 dump、链接及路径穿越条目。初次目标安装生成自己的秘密，运输包不会搬迁来源服务器的业务数据。校验和用于完整性和组件一致性，不是发布者签名；包与可信摘要必须通过可信渠道取得。

## 构建与导出

在具备[完整开发工具](../development/local-setup.md#前置条件)的发布源码根目录执行。先完成检查并提交最终代码，`git status --short` 必须为空；不要在待提交文档/代码尚有修改时构建正式包。Docker context 必须是计划导出平台的 daemon。

```sh
env -u STUDYROOM_STATE_DIR ./scripts/check
git status --short
docker context show
docker info --format '{{.OSType}}/{{.Architecture}}'
```

检查失败先修复；检查生成了需要提交的变更则按[贡献流程](../contributing.md)提交后再继续。构建器固定使用 `local` 配置，不能继承指向 test 实例的状态变量。以下 `env -u` 让构建使用本 checkout 的默认 local 配置；不启动或更新目标 test 应用。

```sh
studyroom_release_id="r-$(date -u +%Y%m%dT%H%M%S)-$(git rev-parse --short=12 HEAD)"
env -u STUDYROOM_STATE_DIR ./scripts/build-release "$studyroom_release_id"
studyroom_manifest="$PWD/.local/releases/$studyroom_release_id/manifest.json"
test -f "$studyroom_manifest"
```

成功输出 manifest 路径，绑定完整 HEAD 与三应用内容 ID。releaseId 目录必须未存在；失败留下的目录保留用于排查，重新构建选新发布号，不覆盖混用。构建成功只代表镜像和清单已生成，还需在隔离目标实际部署/浏览器验收。

导出前需要本 daemon 已有依赖基线指定的 PostgreSQL/Mailpit 镜像。可从 [dependency-baseline.json](../dependency-baseline.json) 核对固定引用；若缺失，在**联网的发布准备机**执行以下固定基线拉取，不在离线目标执行：

```sh
python3 - <<'PY'
import json, pathlib, subprocess
images = json.loads(pathlib.Path('docs/dependency-baseline.json').read_text())['images']
for name in ('postgres', 'mailpit'):
    subprocess.run(['docker', 'pull', images[name]], check=True)
PY
env -u STUDYROOM_STATE_DIR ./scripts/export-release "$studyroom_manifest"
```

导出器自身不自动 pull。五镜像须全部为同一 linux/arm64 或 linux/amd64 且与 daemon 一致；ARM64 正式包不能当作 AMD64 包。成功在 manifest 旁生成 `portable/`，目录已存在时拒绝覆盖。将**完整目录**经可信渠道交付，或另行打成运输归档并附外层校验和；目标新装步骤统一见安装指南。导出、导入和浏览器通过分别记录，不把包成功生成称为已部署。

三个应用引用/ID 来自已验证清单。运行镜像从清单可选 `runtimeImages.postgres/mailpit` 或归档源码基线读取；引用必须与基线一致，已指定 ID 时还要核对本机实际内容。导出后再次检查镜像身份/平台，完整目录最后一次重命名落地。

## 导入接口

推荐先按安装指南校验、解出完整源码，再执行该源码的入口：

```sh
"$studyroom_source_dir/scripts/import-release" "$studyroom_env" "$studyroom_bundle_dir"
```

导入器默认核对自己所在源码目录。另一种方式是用已有可信安装器并指定 `--source-dir /absolute/path/to/new-source`：目标**不存在**时自动解出包内源码；目标存在时只核对已解出的完整源码，不填补空目录，不覆盖修改。两种方式不能混用为“先 mkdir 空目录，再让 --source-dir 自动解包”。现有源码目录下 `.local` 运行状态不参与源码摘要，也不会被删除。

按以下顺序校验与执行：

1. SHA256SUMS 恰好覆盖五个 payload，摘要一致且文件非链接。
2. portable 与原 manifest 的版本、提交、引用/ID、文件摘要一致。
3. 源码归档路径、完整提交、Compose、依赖基线、全部迁移、安装器完整性通过。
4. daemon 平台与五镜像一致，源码目录内容/执行权限符合归档。
5. docker load 后，五个实际镜像 ID/OS/architecture/variant 与记录一致。
6. 以核验源码调用 deploy，执行原有锁、维护、备份、迁移、种子、版本/健康与失败门禁。

文件/平台校验失败不会开始部署；加载后镜像身份不符也会停止。已进入部署阶段的失败由[运维手册](../operations/runbook.md#发布失败或入口持续-503)处理。升级必须保留原环境、状态/project；旧包导入不能越过 schema 门禁，回退要走 rollback。

## 运行镜像与离线语义

docker load 不保证保留 RepoDigests，PostgreSQL/Mailpit 因此保存为带完整内容 ID 的 `studyroom-runtime-<name>:offline-<ID>` 别名。导入再次核对 ID 后将引用传给部署器，写入 `release.env`。后续 Compose 继续读取该文件，无需重新解析原远程 digest；应用仍用 manifest 引用。

别名中的 ID、portable 记录与实际 image ID 必须相同，不能以别名存在替代校验。导入不 pull、不构建；平台不匹配即拒绝，不因目标有模拟器而静默切换。

当前实测是 ARM64 同 daemon 的独立项目安装与后续升级；网络未切断，也不是异机/AMD64 验收。来源数据库、秘密和 Mailpit 不在包内；迁移已有业务需要独立数据方案，restore 仅提供隔离恢复验证。真实结果按版本保留于[发布证据](../evidence/release/README.md)，不将历史 B 首次安装改称 V6 空库安装。

## 开发接口与验证

```text
export_release(manifest_path, verified_manifest, output_dir=None, *, root=ROOT)
verify_bundle(bundle_dir)
import_release(bundle_dir, environment, source_dir=None)
```

export 返回完整目录；verify_bundle 只校验文件且不调用 Docker；import 返回实际部署源码目录。CLI 错误为非零退出。校验与 Docker 使用参数数组，不拼接 shell。

`python3 -m unittest discover -s tests/ops -p test_portable_release.py -v` 检查损坏/缺失摘要、源码不一致、路径穿越/私密目录/链接、平台不符、运行镜像身份变化、加载后错误、无 Git 导入、别名传递与 prod 拒绝。该单测使用模拟 Docker，不能代替实际安装；集成和浏览器目标准备见[隔离发行版环境](../operations/installation.md#准备浏览器-e2e-的隔离发行版环境)。
