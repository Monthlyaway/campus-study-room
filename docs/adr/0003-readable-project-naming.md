# ADR 0003：可理解的项目命名

日期：2026-10-22。状态：用户明确要求，已执行并完成交付验证（2026-10-23 核对）。

用户要求目录、代码和文件名去除不易理解的课程代号。统一使用以下命名：

| 对象 | 新名称 |
| --- | --- |
| 本地目录 / GitLab 路径 | campus-study-room |
| 产品名称 | 栖学 · 校园自习室 |
| Java group / package | com.campus.studyroom |
| 数据库 | studyroom |
| 数据库角色 / 容器前缀 | studyroom |
| 配置属性 | studyroom.* |
| 环境变量前缀 | STUDYROOM_ |
| 运维入口实现 | scripts/studyroom.py |
| 前端 package | @campus-study-room/web |

GitLab 项目 ID 11753 保持不变，origin 已同步到新路径。源码、配置、文档与 Java 文件路径统一变更；Git 历史保留真实提交，不改写历史。

改名前的运行数据保留在仓库外受限缓存目录，旧测试数据库卷未删除。改名后使用新项目的独立网络、卷和随机密钥，从空库重放迁移。构建输出全部重新生成，不复用旧 Java 包名产物。

依赖 lockfile 的密码学完整性字段保持原始值；随机摘要中的字符不是项目命名，不能通过字符串替换破坏。

当前实现见[架构指南](../architecture/README.md)，验证范围见[需求追踪](../requirements-traceability.md)和[发布记录](../evidence/gitlab-release-v0.1.0.md)。历史结果不因更新本决策状态而改写。
