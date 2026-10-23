# 调用与修改 API

这页面向后端开发者和前端联调人员。接口字段和状态码以 [OpenAPI](../../contracts/openapi/README.md)为准；先完成[本机启动](local-setup.md)，账号与密码来源见[演示账号](demo-accounts.md)。

## 地址与响应格式

浏览器使用同源 `/api/v1`：`/api/v1/repairs` 及其子路径进入 repair，其余公开 API 进入 core。原生开发可直接访问 core `http://localhost:8081`、repair `http://localhost:8082`；发行版使用安装时配置的前端入口。私有 `/internal/v1` 只供服务调用，公开代理拒绝 `/internal` 与 `/actuator`。

普通成功响应为 `{"data": ..., "requestId": "UUID"}`。分页 data 为 `{"items": [], "page": 1, "size": 20, "total": 0}`，页号从 1 开始，默认 size 20、最大 100；是否支持分页和过滤以具体契约为准。角色/权限目录当前只提供最多 100 条的固定第一页。退出和新密码重置返回 **204 空响应**，客户端不能强行解析 JSON。前端静态 `/version` 是发行身份对象，不套 API envelope。

失败响应为 `{"error":{"code":"...","message":"...","details":...},"requestId":"UUID"}`。有效的入站 `X-Request-ID` 会贯穿响应和内部调用，否则服务生成 UUID；保留它用于[排障](../modules/observability.md)。

## 登录、权限与写入约定

1. 保存 Cookie，先 `GET /auth/csrf`，取得 `data.csrfToken` 与 `data.headerName`。
2. `POST /auth/login` 发送用户名/密码以及该 CSRF header。登录会更换会话 ID 并清除旧 CSRF。
3. 用同一 Cookie 容器再次 `GET /auth/csrf`，以新 token 做后续写入。`GET /auth/me` 返回当前身份、权限、范围与预约时长限制。
4. 业务写入附 UUID `Idempotency-Key`；状态动作还要带最近详情中的非负整数 `expectedVersion`。登录/退出不要求幂等键。
5. `POST /auth/logout` 携带新 CSRF，预期 204。Cookie、密码、CSRF 不写到代码库、命令参数或调试日志中。

会话 Cookie 为 `STUDYROOMSESSION`，HttpOnly、SameSite=Lax；生产启用 Secure。闲置 30 分钟或登录满 8 小时失效。账号停用、密码/角色/范围修改也会使旧会话失效。`allowedActions` 用于界面提示，服务端仍独立复核权限、范围、归属和状态；管理账号不隐式拥有审批资格。

幂等成功结果保留至少 24 小时；相同账号/方法/规范路径/key 和相同命令体重放原状态码与 data。参与哈希的命令体不同则返回 409，失败事务不会留下成功占位。网络结果不明时重用原 key/body；有意改变操作时才生成新 key。超过保留窗口先查业务状态，不能依赖旧 key 无限去重。

M1/M2 写命令保留完整原始 JSON，未知字段、缺失字段与显式 null 都参与哈希。其他入口要核对其规范化步骤：例如 `POST /rooms/{id}/seats` 先用路径 id 覆盖请求体中的 `roomId`，再计算哈希。这是当前实现的例外，不代表所有入口逐字段保留原始输入；调用方仍应原样重试，不能擅自删改 body。

`expectedVersion` 解决并发更新，不能用幂等键替代。读取旧版本后发生 409 应刷新对象、重新判断意图；不要把所有 409 当成可自动重试。预约与教室申请的起止时段必须带 UTC 偏移，以 Asia/Shanghai 的整点小时表达，区间为 `[startAt,endAt)`，开始晚于当前、结束不超过 14 天且符合完整开放时段。

## 一次完整的本机会话

下面示例只连接你自己的原生 local core，使用 `student`。它查询明天 10:00–11:00 的可用座位，**创建一笔预约、重放创建、再取消**；会保留预约历史和审计。前置是 `dev-up local` 已完成演示种子，且该账号没有此时段冲突。不要把地址改成当前交付实例或共享验收环境。密码通过终端隐藏输入，Cookie 和 CSRF 仅在内存中。

在交互终端从仓库根执行：

```sh
python3 - <<'PY'
import datetime as dt
import getpass
import http.cookiejar
import json
import urllib.error
import urllib.parse
import urllib.request
import uuid

base = 'http://localhost:8081/api/v1'
opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf_header, csrf_token = 'X-CSRF-TOKEN', ''

def call(method, path, body=None, *, key=None, expected=200):
    headers = {'Accept': 'application/json', 'X-Request-ID': str(uuid.uuid4())}
    if csrf_token:
        headers[csrf_header] = csrf_token
    if key:
        headers['Idempotency-Key'] = key
    raw = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        raw = json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=raw, headers=headers, method=method)
    try:
        response = opener.open(request, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        payload = json.loads(raw) if raw else None
        if response.status != expected:
            code = (payload or {}).get('error', {}).get('code', 'UNEXPECTED_STATUS')
            request_id = response.headers.get('X-Request-ID', '')
            raise RuntimeError(f'HTTP {response.status}: {code}; requestId={request_id}')
        return None if response.status == 204 else payload['data']

token = call('GET', '/auth/csrf')
csrf_header, csrf_token = token['headerName'], token['csrfToken']
password = getpass.getpass('本机 local student 密码: ')
try:
    call('POST', '/auth/login', {'username': 'student', 'password': password})
finally:
    del password

try:
    token = call('GET', '/auth/csrf')
    csrf_header, csrf_token = token['headerName'], token['csrfToken']
    call('GET', '/auth/me')
    call('GET', '/reservations?page=1&size=20')
    start = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))) + dt.timedelta(days=1))
    start = start.replace(hour=10, minute=0, second=0, microsecond=0)
    window = {'startAt': start.isoformat(), 'endAt': (start + dt.timedelta(hours=1)).isoformat()}
    seats = call('GET', '/availability/seats?' + urllib.parse.urlencode(window))['items']
    if not seats:
        raise RuntimeError('没有可用座位；先检查账号已有预约、开放规则或更换示例时段。')
    command = {'seatId': seats[0]['seatId'], **window}
    create_key = str(uuid.uuid4())
    created = call('POST', '/reservations', command, key=create_key, expected=201)
    print('创建预约:', created['id'])
    replay = call('POST', '/reservations', command, key=create_key, expected=201)
    assert replay == created, '重放应返回相同 data'
    current = call('GET', '/reservations/' + created['id'])
    cancelled = call('POST', '/reservations/' + created['id'] + '/cancel',
                     {'expectedVersion': current['version']}, key=str(uuid.uuid4()))
    assert cancelled['status'] == 'CANCELLED'
    print('重放一致；预约已取消:', cancelled['id'])
finally:
    call('POST', '/auth/logout', expected=204)
    print('已退出，HTTP 204。')
PY
```

成功时终端显示预约编号、取消成功及退出 204，不显示任何秘密。创建成功后若网络/断言或取消失败，退出不会自动取消预约；到本机界面“我的预约”确认并收尾。不要修改 key 或 body 来猜测创建结果。若登录后刷新 CSRF 失败，重新建立会话再操作。

## 看懂失败与异步结果

| 响应 | 开发者下一步 |
| --- | --- |
| 400 | 检查字段类型、UUID、偏移时间；版本必须是整数，不能用字符串或小数 |
| 401 | 检查会话是否已失效，重新登录；不要关闭身份复核 |
| 403 | 区分 CSRF 与业务权限；核对登录后 token、同一 Cookie 和角色绑定范围 |
| 404 | 对象可能不存在或不可见；普通用户不能借详情接口枚举他人业务 |
| 409 | 按 `error.code` 区分版本、状态、资源、幂等冲突，刷新后判断；不一律重试 |
| 422 | 请求格式可解析但违反业务规则，修改业务输入 |
| 429 | 遵守 `Retry-After`，避免持续重复登录/签到 |
| 503 | 依赖不可用；维修无法确认 core 授权时不执行操作，先恢复依赖 |
| 202 | 操作已接收但可能在同步中；维修查询详情 `sync` 和状态，不能当成开修/关闭已经完成 |

维修资源调用使用固定 `commandId`、内容和来源版本；`BLOCK_PENDING`/`RELEASE_PENDING` 由后台确认后推进。内部 HTTP Basic 不使用浏览器 Cookie/CSRF，也不生成浏览器客户端。恢复步骤见[维修模块](../modules/repair.md)。

## 修改契约与生成客户端

分别维护 [core.yaml](../../contracts/openapi/core.yaml)、[repairs.yaml](../../contracts/openapi/repairs.yaml)、[internal-core.yaml](../../contracts/openapi/internal-core.yaml)。文件采用合法 YAML 的 JSON 语法。接口变化同步 Java 输入/输出、控制器、业务测试、契约测试与前端调用；不要只改 schema。

前端依赖安装后，从仓库根执行：

```sh
./scripts/web generate
./scripts/web typecheck
./scripts/web test
git diff -- apps/web/src/services/generated
```

生成脚本只读取两个公开契约，输出 `apps/web/src/services/generated/{core,repairs}`；Umi `dataFields: ["data"]` 与共享请求适配器匹配，生成类型直接表示 data。不要手改生成文件。M1/M2 写命令已有类型化 Java DTO；认证、管理和私有输入仍有独立的解析实现，不能假设所有控制器都使用同一 DTO 模式。

[真实 HTTP 契约测试](testing.md)检查实际响应的字段、类型、枚举、可空性、格式和额外字段；它只支持仓库所用 schema 特性，且不替代每个操作的权限/业务矩阵。生成后运行相应行为测试，最终用 `./scripts/check` 检查全仓一致性。
