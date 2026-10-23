# HTTP 接口契约

这里是接口字段与响应结构的维护真值。第一次联调先读[API 调用指南](../../docs/development/api.md)，其中有完整登录、刷新 CSRF、幂等写入和退出示例；修改实现的顺序见[后端指南](../../docs/development/backend.md)。

| 文件 | 拥有方与用途 | 客户端 |
| --- | --- | --- |
| [core.yaml](core.yaml) | core 的 56 个公开操作：认证、管理、资源、预约、申请、通知、统计 | 生成浏览器 core 客户端 |
| [repairs.yaml](repairs.yaml) | repair 公开报修、派单、开修、验收、评价与同步查询 | 生成浏览器 repair 客户端 |
| [internal-core.yaml](internal-core.yaml) | core 的 5 个私有操作，供 repair 授权/引用/资源/通知调用 | 服务端 CoreClient；不生成浏览器客户端 |

这些文件用 JSON 语法保存，属于合法 YAML，仓库工具可用标准 JSON 解析器读取。不要仅按文件扩展名更换序列化方式；字段结构须与生成器、契约检查器一致。

## 协议中容易漏掉的边界

公开成功响应通常为 data/requestId，失败为 error/requestId；分页声明 items/page/size/total。有 body 的 core 操作均有具名模型，限流响应声明 Retry-After。请求保留当前允许附加字段的行为。M1/M2 的完整原始 JSON 参与幂等哈希，包括未知字段和“缺失/显式 null”差异；其他入口按交给幂等层的命令体计算，例如座位创建先将 `roomId` 规范化为路径 id。当前例外见 [API 指南](../../docs/development/api.md)，不能据此改变原 key/body 重试的约定。审计快照与通知 payload 按动作变化，不能伪造为一个固定对象。

查询参数只声明实际支持项：房间可按 buildingId/departmentId 过滤，可用性按资源条件筛选，申请支持 view/status，统计日期为本地自然日边界。详情、配置、显示码、认证接口不声明被忽略的分页。角色/权限目录当前只返回最多 100 条的固定第一页，不承诺任意分页。

退出与新密码重置返回 204 无 body，座位创建 201，通知重试 202。旧密码重置成功幂等记录保留原 200 重放；不能重写已完成记录。维修开修/通过验收的 202 代表已接受，最终资源同步由详情状态和 sync 确认。

私有接口用 HTTP Basic 机器身份，不使用浏览器会话/CSRF；公开代理必须拒绝所有 internal 路径。权限、状态、时间和事务约束仍由业务服务实现，schema 本身不替代这些检查。

## 修改后如何生成与检查

先同步契约、Java Controller/DTO/服务及测试，再从仓库根执行（已按本机指南安装前端依赖）：

```sh
./scripts/web generate
./scripts/web typecheck
./scripts/web test
git diff -- apps/web/src/services/generated
```

[生成器](../../apps/web/scripts/generate.mjs)读取 core/repairs，写 `apps/web/src/services/generated/{core,repairs}`。Umi `dataFields: ["data"]` 生成解包后的类型，与共享请求适配器对应；不要手改生成源码。M1/M2 写请求在 [api-contracts/command](../../modules/api-contracts/src/main/java/com/campus/studyroom/api/command)保留不可变原始 JSON，解析位于幂等 action 内，先查已有结果再解析。其他认证/管理/私有输入仍由各自解析实现维护，不要误称全仓已统一 DTO。

`python3 tests/integration/run_core_contract.py` 在已准备的 local PostgreSQL 内创建/复用独立库，用实际随机端口 HTTP 检查响应必需字段、类型、枚举、可空性、格式、额外字段。它需要当前迁移 JAR 和本地配置；前置、写入副作用、报告位置见[测试指南](../../docs/development/testing.md)。检查器支持本仓库所用 schema 特性，不是通用 OpenAPI 校验器，也不证明每个操作的全部权限与业务矩阵。

最终执行适合改动的真实 PG/HTTP 测试与 `./scripts/check`；记录本次执行结果，历史覆盖参见[契约证据](../../docs/evidence/core/contracts.md)。
