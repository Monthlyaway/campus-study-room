# 用 requestId 排查后端问题

这页面向开发者和接手运行环境的维护者。先确认目标与版本：[原生开发](../development/local-setup.md)或[交付实例接手卡](../operations/current-instance.md)。需求关联：REQ-D-07、REQ-X-03、REQ-ENG-08。

## 从响应找到日志

1. 保存失败响应的状态码、`error.code` 和 `requestId`，或响应头 `X-Request-ID`。不要复制 Cookie、密码、CSRF、签到码或整个请求正文。
2. 确认请求进入 core 还是 repair；同步问题通常要查两边。原生 local 日志为 `.local/local/core.log` 与 `.local/local/repair.log`，自定义状态目录随之变化；容器日志入口见[运维手册](../operations/runbook.md)。
3. 用 requestId 查关联事件，必要时再按记录中的 commandId/orderId 查重试。后台续跑可能晚于原请求，不能只检查同一秒。
4. 对照运行 releaseId/commit 和具体代码，必要时通过有权限的审计接口查询业务变化。运行日志不替代业务审计。

下面是只读 local 日志查询，将示例 UUID 替换为失败响应里的编号。命令不访问秘密、不调用业务 API：

```sh
python3 - 00000000-0000-0000-0000-000000000000 <<'PY'
import json
from pathlib import Path
import sys
import uuid
request_id = str(uuid.UUID(sys.argv[1]))
fields = ('timestamp', 'level', 'service', 'releaseId', 'requestId', 'actorId',
          'event', 'errorCode', 'durationMs', 'status', 'commandId', 'orderId', 'taskId')
for name in ('core', 'repair'):
    path = Path('.local/local') / (name + '.log')
    with path.open() as stream:
        for line in stream:
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if row.get('requestId') == request_id:
                print(json.dumps({key: row[key] for key in fields if key in row}, ensure_ascii=False))
PY
```

没有结果时先检查目标日志/状态目录、日志时间与 requestId 是否来自本服务，不要通过开启请求体日志追踪登录。若需查 commandId，可按同样方式改为该字段的 UUID 匹配。

## 按症状判断下一步

| 现象 | 首先确认 | 恢复或修改方向 |
| --- | --- | --- |
| 登录 401/429 | 用户启用、正确环境的密码；429 看 Retry-After | 重新确认账号/等待限流，不把认证拒绝当宕机 |
| 登录成功，写入 403 | 是否登录后重取 CSRF、同一 Cookie；再看权限/绑定范围 | 按 [API 顺序](../development/api.md)重建会话；不关闭 CSRF |
| 改角色后两个服务 401 | authVersion 是否变化 | 预期会话失效，重新登录；仍失败再检查启用状态 |
| 列表空/详情 404 | 筛选、本人/管理视图、房间准入和对象归属 | 清除错误筛选或使用正确授权账号，不越权枚举对象 |
| 409 | 错误码区分 VERSION、STATE、RESOURCE、IDEMPOTENCY | 刷新对象/资源后判断；不盲目换 key 重试未知结果 |
| repair 503 | core readiness、CORE_INTERNAL_URL、私有服务认证配置 | 通过运维流程恢复依赖；无法确认授权时不绕过校验 |
| 工单一直同步中 | 详情 sync、下次尝试、commandId；core 是否已完成原命令 | 恢复依赖后等重试，或授权物业管理员重试原命令；不直接改状态或重建来源 |
| 邮件未收到 | 通知记录、投递状态、Mailpit、后台是否暂停 | 按原通知重试；已读不等于已送达，邮件失败不回滚业务 |
| Java 修改未生效 | 本次编译与运行版本、实际工作区、稳定运行 JAR | 原生 native-up 会复用正确 PID，使用 native-restart；不杀不属于本工作区的进程 |
| 数据库启动校验/checksum 失败 | 运行版本、已应用迁移和新增迁移是否匹配 | 保留现场，修复前向迁移；不删 Flyway 历史、不重写已部署文件 |

## 实现和日志约束

[StudyroomLogFormatter](../../modules/security-common/src/main/java/com/campus/studyroom/security/StudyroomLogFormatter.java)为两个服务统一输出 JSON；[RequestTelemetry](../../modules/security-common/src/main/java/com/campus/studyroom/security/RequestTelemetry.java)与各自关联 filter 处理请求上下文。

每条记录有 timestamp、level、service、releaseId、requestId、actorId、event、errorCode、durationMs；请求结束加 status，后台白名单可加 commandId/taskId/orderId。身份只输出内部 UUID，启动/无用户后台记录身份可空。入站 X-Request-ID 必须是 UUID，否则生成新值；响应头和成功/失败 envelope 使用同一编号。401/403 记录最小安全事件，5xx 为 ERROR；finally 恢复原 MDC，避免线程池串身份。健康轮询不逐次写访问日志。

格式化器不输出请求体、query string、Cookie、CSRF、密码、签到码、邮件正文、自由消息参数、异常正文/堆栈。异常类型用于定位，具体业务内容通过授权审计查询。Spring/Hibernate 启动日志同样格式化，未分类事件为 runtime。

## 验证边界

日志单元入口是 `./mvnw -B -ntp -pl modules/security-common -am test`；真实 HTTP 入口 `logging_http.py` 必须具备双服务实际日志文件、正确地址和测试密码，缺失则失败。完整安全注入例子与运行前置见[测试指南](../development/testing.md)，完整 `./scripts/check` 会提供其隔离环境的真实日志。

测试关注字段/秘密排除、非法 requestId 和异常路径上下文恢复、跨服务成功/CSRF 拒绝的响应与日志一致。历史结果见[日志证据](../evidence/logging.md)；更改后须重新执行相关测试，不能把本页静态说明作为运行证据。
