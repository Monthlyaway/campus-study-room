# GitLab 容器任务的 Alpine HTTPS 镜像修正

2026-10-23，完整流水线 `683462` 的 `container-release` 任务 `4481130` 在专用 Runner `5666` 正常完成 checkout 和 artifact 下载后，执行 `apk add --no-cache python3 git` 失败。官方 CDN 的 `v3.22/main/aarch64/APKINDEX.tar.gz` 请求报告 TLS unexpected EOF / Permission denied，community 请求超时，随后出现 `python3 (no such package)`，任务在 2 分 22 秒以 exit 1 失败。应用构建与容器验收尚未执行；这不是应用测试失败，也不是人工取消。

## 最小修正

仅在 `.gitlab-ci.yml` 的 `container-release.before_script` 增加一条受限 `sed`：把行首精确匹配的 `https://dl-cdn.alpinelinux.org/alpine/` 换成 `https://mirrors.tuna.tsinghua.edu.cn/alpine/`。保留原有 `v3.22/main`、`v3.22/community` 路径和 `apk add --no-cache python3 git`；没有切换发行版、启用 edge、增加不可信包选项、修改 APK 公钥或关闭证书校验。五个 job、needs、失败门禁、DinD TLS、固定镜像及发布脚本均不变。

选择依据是 [Alpine 官方镜像清单](https://dl-cdn.alpinelinux.org/alpine/MIRRORS.txt)收录 TUNA，且 [TUNA 官方 Alpine 帮助](https://mirrors.tuna.tsinghua.edu.cn/help/alpine/)明确提供 HTTPS 换源方式。发行版、渠道与镜像地址的区别见 [Alpine 官方 apk 文档](https://docs.alpinelinux.org/user-handbook/0.1a/Working/apk.html)。现有 CI 的 Debian/Ubuntu 工具安装也使用同一镜像站；此次未修改 `scripts/ci-apt` 或任何生产输入。

## 实际验证

在专用 `studyroom-ci` Linux ARM64 VM 中，以与 CI 相同的固定镜像运行临时容器：

`m.daocloud.io/docker.io/library/docker:28.5.2-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d`

容器没有宿主挂载、秘密挂载或 privileged 权限，并启用 no-new-privileges。实际执行当前候选的换源命令和 `apk add`，从 `2026-10-23T00:36:06.041449Z` 到 `00:36:09.772754Z`，约 3.7 秒，exit 0：Alpine `3.22.2`、Python `3.12.15`、Git `2.49.1`。默认 APK 签名与 HTTPS 证书验证保持启用。容器通过 `--rm` 清理，随后确认不存在；没有修改 VM 系统仓库、Runner 配置、网络或现有 job。

独立 guest curl 对两个固定 aarch64 索引均返回 HTTP 200、证书验证结果 0。更早一次 curl 探针的变量引用构造错误导致字面路径 404，修正路径后通过；该探针错误没有被归因于镜像站。

- 运维测试 **124/124 通过**，其中 workflow 12 项包含两个新增回归：实际运行 CI 中的替换表达式，验证版本/渠道保留、不相关地址不变、重复执行稳定及安装安全选项不变。
- YAML 解析和结构比较通过：移除新增一条命令后，与原配置完全一致。
- 独立代理只读评审通过；本轮五份新增/变更输入的已知本机秘密扫描零命中。
- 原始证据保留于 `output/gitlab/alpine-mirror-probe.json`、`alpine-mirror-probe.log` 和 `alpine-mirror-ops-tests.log`。

此结果证明相同固定镜像中的实际依赖安装可行；**修正后的完整远端 GitLab 流水线尚未重跑**，不能把探针替代最终发布门禁。既有 V5 构建、便携包与隐私审计保留原样，后续最终交付版本需绑定真正通过的提交。
