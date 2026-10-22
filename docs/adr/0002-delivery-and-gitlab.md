# ADR 0002：GitLab 与当前交付边界

日期：2026-10-22。状态：用户直接指示。

## 决策依据

用户明确要求：完成易用、稳定、可迁移的系统；本地安装并提供安装入口；维护 README、Agents 和模块文档；使用 GitLab DevOps；自主 commit、发版；分派 agent 并实际试用前端；不发布为 Site。后续用户明确选择“先完成本机及可迁移交付”和“先使用本地邮件测试服务”。

## 变更

1. GitHub Actions 改为 GitLab CI/CD，仓库 `https://code.agibot.com/yangchunyu/pj2`，项目 ID 11753；PR 对应 Merge Request。以 `resource_group` 串行化环境任务，服务器锁覆盖非 CI 入口。
2. 当前自主开发、提交和本地发版已有用户授权，不逐次等待原文里的人工提交确认；agent 独立评审不能记成人工签名。正式生产独立审批设计保留。
3. 当前交付验证 local/dev/test 隔离环境、可迁移安装、不可变应用组合发布与回退、真实 PostgreSQL 和 HTTP、浏览器及本地邮件。正式公网域名/TLS、外部 SMTP 实际收信、正式服务器上线留待目标信息可用时验收，不伪称已通过。
4. GitLab 实例页面明确显示容器镜像仓库未启用。安装/发布支持自备 OCI registry；本机使用本机私有 registry，发行可携带校验过的离线镜像包。不得假设 `CI_REGISTRY` 存在。
5. 业务规则、权限、数据库事务、重试和数据保护不缩减。P1 在 P0 之后，当前未启动。

原三份文档保留课程与产品基线，首页标记本 ADR。用户当前指示优先，未被调整的验收项继续有效。

## 官方参考

- [GitLab deployment safety](https://docs.gitlab.com/ci/environments/deployment_safety/)
- [GitLab deployment approvals](https://docs.gitlab.com/ci/environments/deployment_approvals/)
- [Docker Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/)
