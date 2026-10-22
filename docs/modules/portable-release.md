# 离线发布与服务器迁移入口

本模块对应 D 的可迁移安装、固定镜像组合和发布校验要求。`scripts/portable_release.py` 将已经验证的发布清单导出为可复制目录；`scripts/import-release` 在目标服务器离线校验并调用现有部署流程。运行时只需要 Python 3.10+、Docker Engine/Desktop 和 Docker Compose v2，不需要 Git、Java、Node 或依赖下载。

## 发布包内容

| 文件 | 用途 |
| --- | --- |
| `manifest.json` | 保留原始发布清单：提交、三个应用镜像内容 ID、Compose、依赖基线和所有 SQL 迁移摘要 |
| `portable.json` | 格式版本、提交、目标平台、五个镜像的来源引用、归档引用、内容 ID、OS、architecture、variant，以及三项主要文件 SHA-256 |
| `images.tar` | `docker save` 导出的 core、repair、web、PostgreSQL、Mailpit 五个镜像 |
| `source.tar.gz` | `git archive` 指定清单完整提交生成的源码；归档 PAX comment 保留提交 SHA |
| `INSTALL.md` | 针对此包的安装步骤、架构要求和数据边界 |
| `SHA256SUMS` | 前述五个文件的 SHA-256；自身不递归计算摘要 |

源码来自指定提交，不读取未提交工作区。导出拒绝 `.git`、`.local`、`secrets`、依赖/编译目录、运行日志、数据库 dump、符号链接、硬链接和路径穿越条目。秘密与业务数据库均不在发布包里；目标环境第一次部署会生成自己的随机秘密。若要搬迁已有业务数据，另行使用受控备份/恢复流程，不能将发布包当作数据备份。

## 导出

先将离线安装器及其 Compose 接口提交，再基于该提交构建发布。旧提交若没有完整安装器会拒绝导出。

```sh
./scripts/build-release campus-study-room-local-20261022
./scripts/export-release .local/releases/campus-study-room-local-20261022/manifest.json
```

实际发布清单路径以 `build-release` 输出为准。`export-release` 首先执行现有 `verify_manifest`；随后在清单旁新建 `portable/`。目录已存在时拒绝覆盖，避免把不同发布混入同一目录。复制整个目录到目标服务器。

三个应用引用和 ID 来自已验证清单。运行镜像优先读取可选的 `runtimeImages.postgres` / `runtimeImages.mailpit`，其结构同应用 `{ "ref": "…", "id": "sha256:…" }`；未提供时从归档源码的 `docs/dependency-baseline.json` 读取固定引用。无论哪种来源，运行镜像引用必须与归档基线一致。提供 ID 时进一步检查本机实际内容。

五个镜像必须都是同一 `linux/arm64` 或 `linux/amd64` 平台，且与导出 Docker daemon 一致。导出后再次检查所有镜像 ID 和平台，完整目录最终一次重命名落地。导出不自动拉取镜像；缺失镜像由发布准备步骤处理。

## 离线导入

从可信来源取得完整目录，在发布包目录执行：

```sh
sha256sum -c SHA256SUMS
# macOS 可改用 shasum -a 256 -c SHA256SUMS
mkdir campus-study-room
tar -xzf source.tar.gz -C campus-study-room
./campus-study-room/scripts/import-release test .
```

这些命令不需要 `.git`。导入器默认核对自身所在源码目录；如果从已有可信安装器运行，也可用 `--source-dir /srv/campus-study-room-release` 将包内源码解到新目录。已存在目录只允许与归档中每个文件及可执行权限一致，不覆盖修改后的源码；目录内现有 `.local` 等运行状态不参与源码摘要，也不会被删除。升级时建议使用新源码目录和固定 `STUDYROOM_STATE_DIR`。

导入按顺序执行：

1. `SHA256SUMS` 必须恰好覆盖五个文件，逐项验证摘要，拒绝指向其他文件的符号链接。
2. 验证 `portable.json` 与原清单的版本、提交、镜像引用/内容 ID 和文件摘要一致。
3. 验证源码归档全部路径、提交、Compose、依赖基线、完整迁移集合，以及离线安装器文件是否齐全。
4. 检查目标 Docker daemon OS/architecture 与五个镜像平台一致，再校验或解包源码。
5. `docker load` 后检查所有镜像内容 ID、OS、architecture、variant。
6. 调用核验源码中的 `ops.deploy(environment, manifest_path, runtime_images=...)`，沿用部署锁、维护窗口、备份、迁移、基线数据、健康检查及失败处理。

校验和能发现传输损坏与组件不一致，不是签名，也不能证明整个包的发布者身份。应使用可信传输渠道，并保留可信发布清单。导入前校验失败不会加载镜像或创建环境；加载后镜像身份不匹配不会开始部署。

当前命令支持 `local`、`dev`、`test`；`prod` 沿用现有正式环境门禁，不在此模块绕过。默认 `test` 页面为 `http://localhost:18082`，演示密码在目标状态目录 `secrets/demo_password`。需要更改监听端口时，先用解包源码的 `scripts/init-env test` 生成目标配置，再修改该环境的 `compose.env`，然后运行导入。

## 运行镜像与离线保证

Docker 加载本地归档后未必保留仓库 `RepoDigests`。PostgreSQL、Mailpit 因此分别保存为 `studyroom-runtime-postgres:offline-<完整内容ID>` 和 `studyroom-runtime-mailpit:offline-<完整内容ID>`。导入重新校验内容 ID 后，将这两个别名显式传入 `POSTGRES_IMAGE`、`MAILPIT_IMAGE`。部署器将覆盖值写入环境 `release.env`，Compose 的运行镜像字段允许这两个覆盖键，后续启动无需向仓库解析原 digest。

这不会放宽镜像身份验证：别名中的 ID、导出记录以及实际 Docker image ID 必须相等。应用镜像仍使用原发布清单的引用。导入只执行 `docker load`、检查和部署，不执行 `docker pull` 或构建。

本机生成的 ARM64 包用于 ARM64 Docker 服务器。AMD64 目标必须另行构建并导出对应平台的五个镜像；不能把同一归档宣称为跨架构安装包。导入器拒绝架构不一致，即使目标装有模拟器也不静默切换。

## 接口与验证

```python
export_release(manifest_path, verified_manifest, output_dir=None, *, root=ROOT)
verify_bundle(bundle_dir)
import_release(bundle_dir, environment, source_dir=None)
```

`export_release` 返回完整包目录；`verify_bundle` 只读校验，不调用 Docker；`import_release` 返回实际部署源码目录。CLI 输出错误时返回非零状态。文件检查与 Docker 参数均使用标准库和参数数组，不使用 shell 拼接。

单元测试位于 `tests/ops/test_portable_release.py`，覆盖全部文件损坏、缺失摘要、篡改源码与清单不一致、路径穿越、私密目录、链接条目、ARM64/AMD64 不匹配、混合镜像架构、运行镜像 ID 变化、清单验证后的变化、加载后 ID 错误、无 Git 源码导入、运行别名传递和 prod 门禁。

```sh
python3 -m unittest discover -s tests/ops -p test_portable_release.py -v
```

上述单测用模拟 Docker 检查发布与拒绝顺序；真实 Docker 导出、移除原 digest 引用后导入、独立目标状态目录启动和业务验收由发布演练记录补充。不能用单测通过替代实际离线安装验证。

2026-10-22 已实际导出B并在独立项目、新源码目录、新秘密/卷上导入，五个运行镜像ID完全匹配，见[发布演练](../evidence/release/README.md)。本次同ARM64 daemon内隔离，未切断宿主网络；跨服务器/AMD64未实测。
