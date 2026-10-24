import { useEffect, useState } from "react";
import {
  Alert,
  App,
  Button,
  Checkbox,
  DatePicker,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
} from "antd";
import {
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  SettingOutlined,
} from "@ant-design/icons";
import { api, items, query } from "../services/api";
import { AccessEditor, responsibility } from "./AdminAccess";
import { can, roleLabel, permissionLabel } from "../access";
import { useSession } from "../services/session";
import {
  PageHeading,
  useData,
  useAction,
  LoadingBlock,
  Failure,
  EmptyState,
  Status,
  dateTime,
  Identifier,
  shanghai,
} from "../components/business/common";
import type { RecordData, Page, Room } from "../services/types";

export function Users() {
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [binding, setBinding] = useState<RecordData>();
  const { user } = useSession();
  const users = useData<Page<RecordData>>(
    `/users?${query({ q: search, page, size: 20 })}`
  );
  const roles = useData<any>(can(user, "role:manage") ? "/roles?size=100" : null);
  const departments = useData<any>("/departments?size=100");
  const action = useAction(() => users.reload());
  const fields = (edit = false) => [
    ...(!edit
      ? [
          { name: "username", label: "账号", required: true, max: 64 },
          {
            name: "password",
            label: "初始密码",
            type: "password" as const,
            required: true,
            help: "12–64 个字符，UTF-8 不超过 72 字节。",
          },
        ]
      : []),
    { name: "displayName", label: "姓名", required: true, max: 64 },
    { name: "email", label: "邮箱", required: true, max: 254 },
    {
      name: "departmentId",
      label: "所属院系",
      type: "select" as const,
      required: true,
      options: items(departments.data).map((d) => ({
        value: d.id,
        label: d.name,
      })),
    },
    ...(!edit
      ? [{ name: "schoolNo", label: "学号（学生必填）", max: 32 }]
      : []),
  ];
  const edit = (r: RecordData) =>
    action.open({
      title: "编辑账号",
      path: `/users/${r.id}`,
      method: "PATCH",
      body: { expectedVersion: r.version },
      fields: fields(true).map((f) => ({ ...f, initial: r[f.name] })),
    });
  return (
    <>
      <PageHeading
        eyebrow="ACCESS MANAGEMENT"
        title="账号与授权"
        description="先创建账号，再设置这个人能做什么、负责哪些教室或院系。"
        action={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            disabled={departments.loading || !!departments.error}
            onClick={() =>
              action.open({
                title: "创建账号",
                path: "/users",
                fields: fields(),
              })
            }
          >
            创建账号
          </Button>
        }
      />
      <div className="list-toolbar">
        <Input.Search
          aria-label="搜索账号或姓名"
          placeholder="搜索账号或姓名…"
          allowClear
          onSearch={(v) => {
            setSearch(v);
            setPage(1);
          }}
          style={{ maxWidth: 320 }}
        />
        <Button onClick={users.reload} icon={<ReloadOutlined />}>
          刷新
        </Button>
      </div>
      <Failure error={users.error} onRetry={users.reload} />
      <Failure error={departments.error} onRetry={departments.reload} />
      <Failure error={roles.error} onRetry={roles.reload} />
      <div className="panel table-panel">
        <Table
          rowKey="id"
          dataSource={items(users.data)}
          loading={users.loading}
          pagination={false}
          scroll={{ x: 900 }}
          columns={[
            {
              title: "姓名 / 账号",
              render: (_, r) => (
                <>
                  <strong>{r.displayName}</strong>
                  <div className="muted">{r.username}</div>
                </>
              ),
            },
            { title: "邮箱", dataIndex: "email" },
            {
              title: "院系",
              render: (_, r) =>
                r.departmentName ||
                items(departments.data).find((d) => d.id === r.departmentId)
                  ?.name,
            },
            {
              title: "状态",
              dataIndex: "enabled",
              render: (v) => (
                <Tag color={v ? "success" : "default"}>
                  {v ? "启用" : "已停用"}
                </Tag>
              ),
            },
            {
              title: "管理",
              render: (_, r) => (
                <Space wrap>
                  <Button size="small" onClick={() => edit(r)}>
                    编辑
                  </Button>
                  {can(user, "role:manage") && <Button size="small"
                    disabled={roles.loading || !!roles.error || departments.loading || !!departments.error}
                    onClick={() => setBinding(r)}>
                    设置职责
                  </Button>}
                  <Button
                    size="small"
                    onClick={() =>
                      action.open({
                        title: "重置密码",
                        path: `/users/${r.id}/reset-password`,
                        body: { expectedVersion: r.version },
                        fields: [
                          {
                            name: "newPassword",
                            label: "新密码",
                            type: "password",
                            required: true,
                            help: "12–64 个字符。重置后原会话全部失效。",
                          },
                        ],
                      })
                    }
                  >
                    重置密码
                  </Button>
                  <Button
                    size="small"
                    danger={r.enabled}
                    onClick={() =>
                      action.open({
                        title: r.enabled ? "停用账号" : "启用账号",
                        path: `/users/${r.id}`,
                        method: "PATCH",
                        body: {
                          enabled: !r.enabled,
                          expectedVersion: r.version,
                        },
                        danger: r.enabled,
                        description: (
                          <p>
                            {r.enabled
                              ? "停用立即使该账号的会话失效。已有历史仍会保留。"
                              : "该账号将可以重新登录。"}
                          </p>
                        ),
                      })
                    }
                  >
                    {r.enabled ? "停用" : "启用"}
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </div>
      <Pagination
        className="pagination"
        current={page}
        pageSize={20}
        total={users.data?.total || 0}
        onChange={setPage}
        hideOnSinglePage
        showSizeChanger={false}
      />
      {binding && <AccessEditor target={binding} roles={items(roles.data)}
        departments={items(departments.data)} onClose={() => setBinding(undefined)}
        onSaved={() => { setBinding(undefined); users.reload(); }} />}
      {action.dialog}
    </>
  );
}

export function Roles() {
  const roles = useData<any>("/roles?size=100");
  const permissions = useData<any>("/permissions");
  const [role, setRole] = useState<RecordData>();
  const [codes, setCodes] = useState<string[]>([]);
  const [error, setError] = useState<Error>();
  const [busy, setBusy] = useState(false);
  const [key, setKey] = useState(crypto.randomUUID());
  const action = useAction(() => roles.reload());
  const { message } = App.useApp();
  const save = async () => {
    if (!role) return;
    setBusy(true);
    setError(undefined);
    try {
      await api(`/roles/${role.id}/permissions`, {
        method: "PUT",
        body: { permissionCodes: codes, expectedVersion: role.version },
        key,
      });
      setRole(undefined);
      roles.reload();
      message.success("角色权限已保存");
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageHeading
        eyebrow="ROLES & PERMISSIONS"
        title="角色与职责"
        description="角色是一组工作权限。配置后，到“账号与授权”把职责分配给具体人员，并选择负责对象。"
        action={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() =>
              action.open({
                title: "新建角色",
                path: "/roles",
                fields: [
                  { name: "code", label: "角色编码", required: true, max: 64, help: "用于区分角色的固定代号，例如 LIBRARY_OPERATOR；日常操作显示下面的中文名称。" },
                  { name: "name", label: "角色名称", required: true, max: 100 },
                ],
              })
            }
          >
            新建角色
          </Button>
        }
      />
      <Failure error={roles.error} onRetry={roles.reload} />
      <div className="panel table-panel">
        <Table
          rowKey="id"
          dataSource={items(roles.data)}
          loading={roles.loading}
          pagination={false}
          scroll={{ x: 600 }}
          columns={[
            { title: "角色", render: (_, r) => <><strong>{roleLabel(r)}</strong><p className="muted">{responsibility(r)}</p></> },
            { title: "编码", dataIndex: "code" },
            {
              title: "权限数量",
              render: (_, r) =>
                (r.permissionCodes || r.permissions || []).length,
            },
            {
              title: "操作",
              render: (_, r) => (
                <Space>
                  <Button
                    onClick={() => {
                      setRole(r);
                      setCodes(
                        (r.permissionCodes || r.permissions || []).map(
                          (p: any) => (typeof p === "string" ? p : p.code)
                        )
                      );
                      setError(undefined);
                      setKey(crypto.randomUUID());
                    }}
                  >
                    配置权限
                  </Button>
                  <Button
                    danger
                    onClick={() =>
                      action.open({
                        title: "停用角色",
                        path: `/roles/${r.id}`,
                        method: "DELETE",
                        body: { expectedVersion: r.version },
                        danger: true,
                        description: (
                          <p>仍有用户关联的角色以及系统关键角色不能停用。</p>
                        ),
                      })
                    }
                  >
                    停用
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </div>
      <Modal
        zIndex={1200}
        forceRender
        title={`配置权限 · ${roleLabel(role)}`}
        open={!!role}
        onCancel={() => setRole(undefined)}
        onOk={save}
        confirmLoading={busy}
        okText="保存角色权限"
        cancelText="取消"
        okButtonProps={{ disabled: permissions.loading || !!permissions.error }}
        width={720}
      >
        <Failure error={error || permissions.error} />
        <p className="muted">
          勾选这个角色需要完成的工作。修改会影响所有使用该角色的人员，他们需要重新登录。负责哪些教室或院系，在用户的“设置职责”中单独指定。
        </p>
        <Checkbox.Group
          className="permission-grid"
          value={codes}
          onChange={(v) => {
            setCodes(v as string[]);
            setKey(crypto.randomUUID());
          }}
          options={items(permissions.data).map((p) => ({
            value: typeof p === "string" ? p : p.code,
            label: (
              <span>
                <strong>{permissionLabel(p)}</strong>

              </span>
            ),
          }))}
        />
      </Modal>
      {action.dialog}
    </>
  );
}

function ReferenceTable({
  kind,
}: {
  kind: "departments" | "campuses" | "buildings";
}) {
  const data = useData<any>(`/${kind}?size=100`);
  const campus = useData<any>(
    kind === "buildings" ? "/campuses?size=100" : null
  );
  const action = useAction(data.reload);
  const label = { departments: "院系", campuses: "校区", buildings: "楼宇" }[
    kind
  ];
  return (
    <>
      <div className="list-toolbar">
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() =>
            action.open({
              title: `添加${label}`,
              path: `/${kind}`,
              fields: [
                ...(kind === "buildings"
                  ? [
                      {
                        name: "campusId",
                        label: "校区",
                        type: "select" as const,
                        required: true,
                        options: items(campus.data).map((c) => ({
                          value: c.id,
                          label: c.name,
                        })),
                      },
                    ]
                  : []),
                { name: "code", label: "编号", required: true, max: 32 },
                { name: "name", label: "名称", required: true, max: 100 },
              ],
            })
          }
        >
          添加{label}
        </Button>
      </div>
      <Failure error={data.error} />
      <Table
        rowKey="id"
        dataSource={items(data.data)}
        loading={data.loading}
        pagination={false}
        scroll={{ x: 500 }}
        columns={[
          { title: "编号", dataIndex: "code" },
          { title: "名称", dataIndex: "name" },
          {
            title: "状态",
            dataIndex: "enabled",
            render: (v) => (
              <Tag color={v ? "success" : "default"}>{v ? "启用" : "停用"}</Tag>
            ),
          },
          {
            title: "操作",
            render: (_, r) => (
              <Space>
                <Button
                  onClick={() =>
                    action.open({
                      title: `编辑${label}`,
                      path: `/${kind}/${r.id}`,
                      method: "PATCH",
                      body: { expectedVersion: r.version },
                      fields: [
                        {
                          name: "name",
                          label: "名称",
                          required: true,
                          max: 100,
                          initial: r.name,
                        },
                      ],
                    })
                  }
                >
                  编辑
                </Button>
                <Button
                  danger={r.enabled}
                  onClick={() =>
                    action.open({
                      title: r.enabled ? "停用资源" : "启用资源",
                      path: `/${kind}/${r.id}`,
                      method: "PATCH",
                      body: { enabled: !r.enabled, expectedVersion: r.version },
                      danger: r.enabled,
                      description: <p>有启用资源或账号引用时，将拒绝停用。</p>,
                    })
                  }
                >
                  {r.enabled ? "停用" : "启用"}
                </Button>
              </Space>
            ),
          },
        ]}
      />
      {action.dialog}
    </>
  );
}

export function Spaces() {
  const { user } = useSession();
  const canManageRooms = can(user, "room:manage");
  const [tab, setTab] = useState(canManageRooms ? "rooms" : "departments");
  const [room, setRoom] = useState<RecordData>();
  const [rulesOpen, setRulesOpen] = useState(false);
  const [rulesForm] = Form.useForm();
  const [rulesBusy, setRulesBusy] = useState(false);
  const [rulesError, setRulesError] = useState<Error>();
  const [rulesKey, setRulesKey] = useState(crypto.randomUUID());
  const [roomPage, setRoomPage] = useState(1);
  const rooms = useData<Page<Room>>(canManageRooms ? "/rooms?page=" + roomPage + "&size=20" : null);
  const buildings = useData<any>("/buildings?size=100");
  const departments = useData<any>("/departments?size=100");
  const seats = useData<any>(room ? `/rooms/${room.id}/seats?size=100` : null);
  const detail = useData<any>(room ? `/rooms/${room.id}` : null);
  const [claimPage, setClaimPage] = useState(1);
  const claims = useData<any>(
    room ? `/rooms/${room.id}/resource-claims?page=${claimPage}&size=10` : null
  );
  useEffect(() => setClaimPage(1), [room?.id]);
  const action = useAction(() => {
    rooms.reload();
    seats.reload();
    detail.reload();
  });
  const { message } = App.useApp();
  const saveRules = async () => {
    if (!room) return;
    try {
      const value = await rulesForm.validateFields();
      setRulesBusy(true);
      setRulesError(undefined);
      await api(`/rooms/${room.id}/opening-rules`, {
        method: "PUT",
        body: {
          rules: value.rules || [],
          expectedVersion: detail.data?.version ?? room.version,
        },
        key: rulesKey,
      });
      message.success("开放时间已更新");
      setRulesOpen(false);
      detail.reload();
      rooms.reload();
    } catch (e) {
      if (e instanceof Error) setRulesError(e);
    } finally {
      setRulesBusy(false);
    }
  };
  return (
    <>
      <PageHeading
        eyebrow="SPACE MANAGEMENT"
        title="教室与座位"
        description={canManageRooms ? "选择教室后管理座位与开放时间。新教室需添加座位并设置开放时间，才能供师生预约。" : "维护院系、校区和楼宇。教室与座位由获授权的教室管理员维护。"}
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          ...(canManageRooms ? [{ key: "rooms", label: "教室与座位" }] : []),
          ...(can(user, "user:manage")
            ? [{ key: "departments", label: "院系" }, { key: "campuses", label: "校区" }, { key: "buildings", label: "楼宇" }]
            : []),
        ]}
      />
      {tab !== "rooms" ? (
        <div className="panel table-panel">
          <ReferenceTable kind={tab as any} />
        </div>
      ) : (
        <>
          <div className="list-toolbar">
            <Button
              type="primary"
              icon={<PlusOutlined />}
              disabled={buildings.loading || departments.loading || !!buildings.error || !!departments.error}
              onClick={() =>
                action.open({
                  title: "添加教室",
                  path: "/rooms",
                  fields: [
                    {
                      name: "buildingId",
                      label: "楼宇",
                      type: "select",
                      required: true,
                      options: items(buildings.data).map((b) => ({
                        value: b.id,
                        label: b.name,
                      })),
                    },
                    {
                      name: "code",
                      label: "教室编号",
                      required: true,
                      max: 32,
                    },
                    {
                      name: "name",
                      label: "教室名称",
                      required: true,
                      max: 100,
                    },
                    {
                      name: "departmentId",
                      label: "所属院系（留空为全校共享）",
                      type: "select",
                      options: items(departments.data).map((d) => ({
                        value: d.id,
                        label: d.name,
                      })),
                    },
                  ],
                })
              }
            >
              添加教室
            </Button>
            <Button onClick={rooms.reload} icon={<ReloadOutlined />}>
              刷新
            </Button>
          </div>
          <Failure error={rooms.error} onRetry={rooms.reload} />
          <Failure error={buildings.error} onRetry={buildings.reload} />
          <Failure error={departments.error} onRetry={departments.reload} />
          <div className="panel table-panel">
            <Table
              rowKey="id"
              dataSource={items(rooms.data)}
              loading={rooms.loading}
              pagination={false}
              scroll={{ x: 700 }}
              columns={[
                {
                  title: "教室",
                  render: (_, r) => (
                    <>
                      <strong>{r.name}</strong>
                      <p className="muted">{r.code}</p>
                    </>
                  ),
                },
                {
                  title: "位置",
                  render: (_, r) =>
                    `${r.campusName || ""} · ${r.buildingName || ""}`,
                },
                {
                  title: "谁可以预约",
                  render: (_, r) =>
                    r.departmentId
                      ? items(departments.data).find(
                          (d) => d.id === r.departmentId
                        )?.name || "院系专属"
                      : "全校共享",
                },
                { title: "座位", dataIndex: "seatCount" },
                {
                  title: "状态",
                  dataIndex: "enabled",
                  render: (v) => (
                    <Tag color={v ? "success" : "default"}>
                      {v ? "启用" : "停用"}
                    </Tag>
                  ),
                },
                {
                  title: "操作",
                  render: (_, r) => (
                    <Button onClick={() => setRoom(r)}>管理教室</Button>
                  ),
                },
              ]}
            />
          </div>
          <Pagination className="pagination" current={roomPage} pageSize={20}
            total={rooms.data?.total || 0} onChange={setRoomPage}
            hideOnSinglePage showSizeChanger={false} />
        </>
      )}
      <Drawer
        title={`管理教室 · ${room?.name || ""}`}
        open={!!room}
        onClose={() => setRoom(undefined)}
        size={700}
      >
        <Failure error={detail.error} onRetry={detail.reload} />
        <p className="muted">教室启用、至少一个座位启用且设置开放时间后，符合院系要求的师生才可以预约。</p>
        <Space wrap className="mb-20">
          <Button
            onClick={() =>
              action.open({
                title: "编辑教室",
                path: `/rooms/${room?.id}`,
                method: "PATCH",
                body: {
                  expectedVersion: detail.data?.version ?? room?.version,
                },
                fields: [
                  {
                    name: "name",
                    label: "教室名称",
                    required: true,
                    max: 100,
                    initial: detail.data?.name || room?.name,
                  },
                ],
              })
            }
          >
            编辑名称
          </Button>
          <Button disabled={detail.loading || !!detail.error}
            onClick={() => {
              setRulesOpen(true);
              setRulesError(undefined);
              setRulesKey(crypto.randomUUID());
              rulesForm.setFieldsValue({
                rules: detail.data?.openingRules || detail.data?.rules || [],
              });
            }}
          >
            开放时间
          </Button>
          <Button
            danger={detail.data?.enabled ?? room?.enabled}
            onClick={() =>
              action.open({
                title:
                  detail.data?.enabled ?? room?.enabled
                    ? "停用教室"
                    : "启用教室",
                path: `/rooms/${room?.id}`,
                method: "PATCH",
                body: {
                  enabled: !(detail.data?.enabled ?? room?.enabled),
                  expectedVersion: detail.data?.version ?? room?.version,
                },
                danger: detail.data?.enabled ?? room?.enabled,
                description: (
                  <p>
                    存在有效预约、整室占用或维修封禁时，系统会拒绝停用。停用不会删除历史。
                  </p>
                ),
              })
            }
          >
            {detail.data?.enabled ?? room?.enabled ? "停用教室" : "启用教室"}
          </Button>
          {can(user, "room:display_code") && (
            <Button href={`/display/${room?.id}`} target="_blank">
              打开签到屏幕
            </Button>
          )}
        </Space>
        <div className="section-line">
          <h3>座位管理</h3>
          <Button
            icon={<PlusOutlined />}
            onClick={() =>
              action.open({
                title: "新增座位",
                path: `/rooms/${room?.id}/seats`,
                fields: [
                  { name: "code", label: "座位编号", required: true, max: 32 },
                  {
                    name: "hasPower",
                    label: "电源插座",
                    type: "select",
                    required: true,
                    initial: false,
                    options: [
                      { value: false, label: "无插座" },
                      { value: true, label: "有插座" },
                    ],
                  },
                ],
              })
            }
          >
            新增座位
          </Button>
        </div>
        <Failure error={seats.error} />
        <Table
          rowKey="id"
          dataSource={items(seats.data)}
          loading={seats.loading}
          pagination={false}
          scroll={{ x: 450 }}
          columns={[
            { title: "编号", dataIndex: "code" },
            {
              title: "插座",
              dataIndex: "hasPower",
              render: (v) => (v ? "有" : "无"),
            },
            {
              title: "状态",
              dataIndex: "enabled",
              render: (v) => (v ? "启用" : "停用"),
            },
            {
              title: "操作",
              render: (_, s) => (
                <Space wrap>
                  <Button
                    size="small"
                    onClick={() =>
                      action.open({
                        title: "更改插座标记",
                        path: `/seats/${s.id}`,
                        method: "PATCH",
                        body: {
                          expectedVersion: s.version,
                          hasPower: !s.hasPower,
                        },
                        description: (
                          <p>
                            确认将 {s.code} 标记为{s.hasPower ? "无" : "有"}
                            插座？
                          </p>
                        ),
                      })
                    }
                  >
                    {s.hasPower ? "改为无插座" : "改为有插座"}
                  </Button>
                  <Button
                    size="small"
                    danger={s.enabled}
                    onClick={() =>
                      action.open({
                        title: s.enabled ? "停用座位" : "启用座位",
                        path: `/seats/${s.id}`,
                        method: "PATCH",
                        body: {
                          expectedVersion: s.version,
                          enabled: !s.enabled,
                        },
                        danger: s.enabled,
                      })
                    }
                  >
                    {s.enabled ? "停用" : "启用"}
                  </Button>
                </Space>
              ),
            },
          ]}
        />
        <div className="section-line">
          <h3>资源占用记录</h3>
          <Button icon={<ReloadOutlined />} onClick={claims.reload}>
            刷新
          </Button>
        </div>
        <p className="muted">
          只读查看个人预约、整室申请与维修封禁的生效和释放记录。
        </p>
        <Failure error={claims.error} onRetry={claims.reload} />
        <Table
          rowKey="id"
          size="small"
          loading={claims.loading}
          dataSource={items(claims.data)}
          pagination={false}
          scroll={{ x: 560 }}
          columns={[
            {
              title: "来源",
              dataIndex: "sourceType",
              render: (v) =>
                (({ M1: "个人预约", M2: "教室申请", M3: "维修封禁" } as any)[
                  v
                ] || v),
            },
            {
              title: "业务编号",
              dataIndex: "sourceId",
              render: (v) => <Identifier value={v} />,
            },
            { title: "开始", dataIndex: "startAt", render: dateTime },
            {
              title: "结束",
              dataIndex: "endAt",
              render: (v) => (v ? dateTime(v) : "恢复前持续"),
            },
            {
              title: "状态",
              dataIndex: "releasedAt",
              render: (v) => (
                <Tag color={v ? "default" : "processing"}>
                  {v ? "已释放" : "生效中"}
                </Tag>
              ),
            },
          ]}
        />
        <Pagination
          className="pagination"
          current={claimPage}
          pageSize={10}
          total={claims.data?.total || 0}
          onChange={setClaimPage}
          hideOnSinglePage
          showSizeChanger={false}
        />
      </Drawer>
      <Modal
        zIndex={1200}
        forceRender
        title="每周开放时间"
        open={rulesOpen}
        onCancel={() => setRulesOpen(false)}
        onOk={saveRules}
        confirmLoading={rulesBusy}
        width={640}
        okText="保存"
        cancelText="取消"
      >
        <Alert
          className="mb-20"
          type="info"
          title="每项为一个连续开放区间"
          description="可为同一天添加多个不重叠区间。已有未来有效占用时不能修改。跨午夜请拆分到两天。"
        />
        <Failure error={rulesError} />
        <Form
          layout="vertical"
          form={rulesForm}
          onValuesChange={() => setRulesKey(crypto.randomUUID())}
        >
          <Form.List name="rules">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name }) => (
                  <div className="rule-row" key={key}>
                    <Form.Item
                      name={[name, "weekday"]}
                      label="星期"
                      rules={[{ required: true }]}
                    >
                      <Select
                        aria-label="星期"
                        options={["一", "二", "三", "四", "五", "六", "日"].map(
                          (d, i) => ({ value: i + 1, label: `周${d}` })
                        )}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[name, "startMinute"]}
                      label="开放开始"
                      rules={[{ required: true }]}
                    >
                      <Select
                        aria-label="开放开始"
                        options={Array.from({ length: 24 }, (_, i) => ({
                          value: i * 60,
                          label: `${String(i).padStart(2, "0")}:00`,
                        }))}
                      />
                    </Form.Item>
                    <span>至</span>
                    <Form.Item
                      name={[name, "endMinute"]}
                      label="开放结束"
                      rules={[{ required: true }]}
                    >
                      <Select
                        aria-label="开放结束"
                        options={Array.from({ length: 24 }, (_, i) => ({
                          value: (i + 1) * 60,
                          label: `${String(i + 1).padStart(2, "0")}:00`,
                        }))}
                      />
                    </Form.Item>
                    <Button
                      danger
                      type="text"
                      aria-label="移除时间段"
                      icon={<DeleteOutlined />}
                      onClick={() => remove(name)}
                    />
                  </div>
                ))}
                <Button
                  block
                  type="dashed"
                  onClick={() =>
                    add({ weekday: 1, startMinute: 480, endMinute: 1320 })
                  }
                >
                  添加开放区间
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </Modal>
      {action.dialog}
    </>
  );
}

export function Config() {
  const config = useData<any>("/system-config");
  const action = useAction(config.reload);
  return (
    <>
      <PageHeading
        eyebrow="SYSTEM PREFERENCES"
        title="系统参数"
        description="调整预约时长。新参数仅作用于后续创建的业务。"
      />
      <Failure error={config.error} onRetry={config.reload} />
      <LoadingBlock loading={config.loading}>
        <div className="settings-grid">
          {items(config.data).map((c: any) => (
            <section className="panel setting-card" key={c.key}>
              <span className="setting-icon">
                <SettingOutlined />
              </span>
              <h3>
                {/m1|reservation/i.test(c.key)
                  ? "个人预约最长时长"
                  : /m2|application/i.test(c.key)
                  ? "整教室申请最长时长"
                  : c.description || c.key}
              </h3>
              <div className="setting-value">
                {c.value ?? c.valueJson}
                <small>小时</small>
              </div>
              <p>允许 1–12 小时，已有预约不受影响。</p>
              <Button
                onClick={() =>
                  action.open({
                    title: "调整最长时长",
                    path: "/system-config",
                    method: "PATCH",
                    body: { expectedVersion: config.data.version },
                    transform: (body) => ({
                      expectedVersion: body.expectedVersion,
                      changes: [{ key: c.key, value: body.newValue }],
                    }),
                    fields: [
                      {
                        name: "newValue",
                        label: "最长时长",
                        type: "select",
                        required: true,
                        initial: c.value ?? c.valueJson,
                        options: Array.from({ length: 12 }, (_, i) => ({
                          value: i + 1,
                          label: `${i + 1} 小时`,
                        })),
                      },
                    ],
                    description: (
                      <p>调整后，后续创建的预约将使用新时长限制。</p>
                    ),
                  })
                }
              >
                调整参数
              </Button>
            </section>
          ))}
        </div>
      </LoadingBlock>
      {action.dialog}
    </>
  );
}

export function Statistics() {
  const [dates, setDates] = useState<any>([
    shanghai().startOf("month"),
    shanghai(),
  ]);
  const [roomId, setRoomId] = useState<string>();
  const rooms = useData<any>("/rooms?size=100");
  const dateCount = dates[1].startOf("day").diff(dates[0].startOf("day"), "day") + 1;
  const validDates = dateCount > 0 && dateCount <= 31;
  const data = useData<any>(
    validDates ? `/statistics/usage?${query({
      fromDate: dates[0].format("YYYY-MM-DD"),
      toDate: dates[1].format("YYYY-MM-DD"),
      roomId,
    })}` : null
  );
  const d = validDates ? data.data : undefined;
  return (
    <>
      <PageHeading
        eyebrow="SPACE INSIGHTS"
        title="使用统计"
        description="选择日期和教室，查看预约量、签到情况与座位使用时长。仅统计你负责的教室。"
      />
      <div className="list-toolbar">
        <DatePicker.RangePicker
          aria-label="统计起止日期"
          value={dates}
          allowClear={false}
          onChange={(v) => v && setDates(v)}
        />
        <Select
          aria-label="统计教室"
          showSearch={{ optionFilterProp: "label" }}
          placeholder="全部负责教室"
          allowClear
          value={roomId}
          onChange={setRoomId}
          options={items(rooms.data).map((r) => ({
            value: r.id,
            label: r.name,
          }))}
        />
        <Button icon={<ReloadOutlined />} onClick={data.reload}>
          刷新
        </Button>
      </div>
      <Alert
        className="mb-20"
        type="info"
        showIcon
        title="预约 / 签到记录指标"
        description="统计按中国标准时间的自然日计算，单次查询最多 31 天。此数据不代表实际人员在场率。"
      />
      {!validDates && <Alert className="mb-20" type="warning" showIcon title="请选择最多 31 天的日期范围" description="缩短结束日期后会自动重新查询。" />}
      <Failure error={data.error || rooms.error} onRetry={() => { data.reload(); rooms.reload(); }} />
      <LoadingBlock loading={data.loading}>
        <div className="metric-grid">
          {[
            { label: "预约总数", value: d?.reservationCount, unit: "次" },
            {
              label: "签到率",
              value:
                d?.checkinRate == null
                  ? "无数据"
                  : `${(d.checkinRate * 100).toFixed(1)}%`,
              unit: "",
            },
            {
              label: "预约座位时长",
              value: d?.reservedSeatHours,
              unit: "小时",
            },
            { label: "取消预约", value: d?.cancelledCount, unit: "次" },
            { label: "未签到", value: d?.noShowCount, unit: "次" },
          ].map((m) => (
            <div className="panel metric" key={m.label}>
              <span>{m.label}</span>
              <strong>
                {m.value ?? "—"} <small>{m.unit}</small>
              </strong>
            </div>
          ))}
        </div>
      </LoadingBlock>
      <section className="panel metric-explanation">
        <h3>如何理解这些数字</h3>
        <p>
          签到率仅统计已经到达签到截止时刻、且未取消的预约。维修前已签到的中断记录仍计入签到；维修导致的未签到取消不计入。
        </p>
        <p>
          座位小时计算有效预约时段与查询日期的交集；维修中断只计算中断前的时长。
        </p>
      </section>
    </>
  );
}

const auditActions: Record<string, string> = {
  USER_CREATED: "创建账号", USER_UPDATED: "修改账号资料", PASSWORD_RESET: "重置密码",
  USER_ROLE_BINDINGS_CHANGED: "调整人员职责与负责对象",
  ROLE_CREATED: "创建角色", ROLE_DISABLED: "停用角色", ROLE_PERMISSIONS_CHANGED: "调整角色权限",
  MASTER_CREATED: "添加基础资料", MASTER_UPDATED: "修改基础资料", OPENING_RULES_UPDATED: "调整开放时间",
  CONFIG_UPDATED: "调整系统参数", RESOURCE_DISABLED: "停用资源",
  RESERVATION_CREATED: "创建座位预约", RESERVATION_CANCELLED: "取消座位预约",
  RESERVATION_CHECKED_IN: "完成签到", RESERVATION_COMPLETED: "预约结束", RESERVATION_NO_SHOW: "记录未按时签到",
  APPLICATION_CREATED: "提交教室申请", APPLICATION_APPROVE: "通过教室审批",
  APPLICATION_REJECT: "驳回教室申请", APPLICATION_CANCELLED: "取消教室申请",
  APPLICATION_EXPIRED: "教室申请过期", RESOURCE_CLAIMED: "占用教室或座位", RESOURCE_RELEASED: "释放资源占用",
  REPAIR_SUBMITTED: "提交报修", REPAIR_ASSIGN: "派发维修任务", REPAIR_START: "开始维修",
  REPAIR_COMPLETE: "提交维修完工", REPAIR_ACCEPT: "提交验收结果", REPAIR_CANCEL: "取消报修",
  REPAIR_RATE: "评价维修", REPAIR_RETRY_SYNC: "重试教室状态更新",
  RESOURCE_BLOCK_CONFIRMED: "确认暂停教室使用", RESOURCE_RELEASE_CONFIRMED: "确认恢复教室使用",
  LOGIN_SUCCESS: "登录成功", LOGIN_FAILURE: "登录失败", LOGOUT: "退出登录",
};
const auditSources: Record<string, string> = {
  M1: "座位预约", M2: "教室申请", M3: "报修维修", user: "账号", role: "角色",
  room: "教室", seat: "座位", campus: "校区", building: "楼宇", department: "院系",
  config: "系统参数",
};

export function Audit() {
  const [source, setSource] = useState("core");
  const [objectId, setObjectId] = useState("");
  const [page, setPage] = useState(1);
  const data = useData<any>(
    `${source === "repair" ? "/repairs" : ""}/audit-events?${query({
      objectId,
      page,
      size: 20,
    })}`
  );
  return (
    <>
      <PageHeading
        eyebrow="TRACEABLE OPERATIONS"
        title="操作审计"
        description="查看谁在何时做了什么。可从预约、申请或报修详情复制编号，追踪该业务的全部操作。"
      />
      <Tabs
        activeKey={source}
        onChange={(v) => {
          setSource(v);
          setPage(1);
        }}
        items={[
          { key: "core", label: "预约、审批与系统管理" },
          { key: "repair", label: "报修与维修" },
        ]}
      />
      <div className="list-toolbar">
        <Input.Search
          aria-label="按完整业务编号查询"
          placeholder="粘贴从业务详情复制的完整编号…"
          onSearch={(v) => {
            setObjectId(v);
            setPage(1);
          }}
          allowClear
          style={{ maxWidth: 400 }}
        />
        <Button icon={<ReloadOutlined />} onClick={data.reload}>
          刷新
        </Button>
      </div>
      <Failure error={data.error} />
      <div className="panel table-panel">
        <Table
          rowKey="id"
          loading={data.loading}
          dataSource={items(data.data)}
          pagination={false}
          scroll={{ x: 1000 }}
          columns={[
            { title: "时间", dataIndex: "createdAt", render: dateTime },
            { title: "做了什么", dataIndex: "action", render: (v) => <details><summary>{auditActions[v] || "其他系统操作"}</summary><small>{v}</small></details> },
            { title: "业务", render: (_, r) => auditSources[r.sourceType || r.objectType] || "系统管理" },
            {
              title: "业务编号",
              dataIndex: "objectId",
              render: (v) => <Identifier value={v} />,
            },
            {
              title: "操作人员编号",
              dataIndex: "actorId",
              render: (v) => <Identifier value={v} />,
            },
            {
              title: "请求编号",
              dataIndex: "requestId",
              render: (v) => <Identifier value={v} />,
            },
            { title: "原因", dataIndex: "reason" },
          ]}
        />
      </div>
      <Pagination
        className="pagination"
        current={page}
        pageSize={20}
        total={data.data?.total || 0}
        onChange={setPage}
        hideOnSinglePage
        showSizeChanger={false}
      />
    </>
  );
}
