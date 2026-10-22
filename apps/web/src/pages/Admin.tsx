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
  const [bindingForm] = Form.useForm();
  const [bindingBusy, setBindingBusy] = useState(false);
  const [bindingError, setBindingError] = useState<Error>();
  const [bindingKey, setBindingKey] = useState(crypto.randomUUID());
  const { message } = App.useApp();
  const users = useData<Page<RecordData>>(
    `/users?${query({ q: search, page, size: 20 })}`
  );
  const roles = useData<any>("/roles?size=100");
  const departments = useData<any>("/departments?size=100");
  const rooms = useData<any>("/rooms?size=100");
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
  const bind = (r: RecordData) => {
    setBinding(r);
    setBindingError(undefined);
    setBindingKey(crypto.randomUUID());
    bindingForm.setFieldsValue({
      bindings: (r.roleBindings || r.bindings || []).map((b: any) => ({
        roleId: b.roleId,
        scopes: b.scopes?.length ? b.scopes : [{ scopeType: "SELF" }],
      })),
    });
  };
  const saveBindings = async () => {
    if (!binding) return;
    try {
      const v = await bindingForm.validateFields();
      setBindingBusy(true);
      setBindingError(undefined);
      await api(`/users/${binding.id}/role-bindings`, {
        method: "PUT",
        body: {
          bindings: (v.bindings || []).map((b: any) => ({
            roleId: b.roleId,
            scopes: (b.scopes || []).map((s: any) => ({
              scopeType: s.scopeType,
              ...(s.scopeType === "DEPARTMENT"
                ? { departmentId: s.departmentId }
                : s.scopeType === "ROOM"
                ? { roomId: s.roomId }
                : {}),
            })),
          })),
          expectedVersion: binding.version,
        },
        key: bindingKey,
      });
      message.success("权限已更新，用户需重新登录");
      setBinding(undefined);
      users.reload();
    } catch (e) {
      if (e instanceof Error) setBindingError(e);
    } finally {
      setBindingBusy(false);
    }
  };
  return (
    <>
      <PageHeading
        eyebrow="ACCESS MANAGEMENT"
        title="用户管理"
        description="管理校园账号，为每个人分配恰当的角色与范围。"
        action={
          <Button
            type="primary"
            icon={<PlusOutlined />}
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
          placeholder="搜索账号或姓名"
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
                  <Button size="small" onClick={() => bind(r)}>
                    角色与范围
                  </Button>
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
      <Modal
        zIndex={1200}
        forceRender
        title={`角色与数据范围 · ${binding?.displayName || ""}`}
        width={720}
        open={!!binding}
        onCancel={() => setBinding(undefined)}
        onOk={saveBindings}
        confirmLoading={bindingBusy}
        okText="保存授权"
        cancelText="取消"
      >
        <Alert
          className="mb-20"
          type="info"
          title="权限与范围按同一角色绑定生效"
          description="修改会使该用户原会话失效。管理角色没有范围时，不能访问全校数据。"
        />
        <Failure error={bindingError} />
        <Form
          form={bindingForm}
          layout="vertical"
          onValuesChange={() => setBindingKey(crypto.randomUUID())}
        >
          <Form.List name="bindings">
            {(fields, { add, remove }) => (
              <>
                {fields.map(({ key, name }) => (
                  <section className="binding-card" key={key}>
                    <div className="binding-title">
                      <Form.Item
                        name={[name, "roleId"]}
                        label="角色"
                        rules={[{ required: true, message: "请选择角色" }]}
                      >
                        <Select
                          options={items(roles.data).map((r) => ({
                            value: r.id,
                            label: `${roleLabel(r)} (${r.code})`,
                          }))}
                        />
                      </Form.Item>
                      <Button
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                        aria-label="移除角色"
                        onClick={() => remove(name)}
                      />
                    </div>
                    <Form.List name={[name, "scopes"]}>
                      {(scopes, { add: addScope, remove: removeScope }) => (
                        <>
                          {scopes.map(({ key: sk, name: sn }) => (
                            <div className="scope-row" key={sk}>
                              <Form.Item
                                name={[sn, "scopeType"]}
                                label="范围"
                                rules={[{ required: true }]}
                              >
                                <Select
                                  options={[
                                    { value: "SELF", label: "本人" },
                                    { value: "DEPARTMENT", label: "指定院系" },
                                    { value: "ROOM", label: "指定教室" },
                                    { value: "ALL", label: "全校" },
                                  ]}
                                />
                              </Form.Item>
                              <Form.Item noStyle shouldUpdate>
                                {() => {
                                  const type = bindingForm.getFieldValue([
                                    "bindings",
                                    name,
                                    "scopes",
                                    sn,
                                    "scopeType",
                                  ]);
                                  return type === "DEPARTMENT" ? (
                                    <Form.Item
                                      name={[sn, "departmentId"]}
                                      label="院系"
                                      rules={[{ required: true }]}
                                    >
                                      <Select
                                        options={items(departments.data).map(
                                          (d) => ({
                                            value: d.id,
                                            label: d.name,
                                          })
                                        )}
                                      />
                                    </Form.Item>
                                  ) : type === "ROOM" ? (
                                    <Form.Item
                                      name={[sn, "roomId"]}
                                      label="教室"
                                      rules={[{ required: true }]}
                                    >
                                      <Select
                                        options={items(rooms.data).map((d) => ({
                                          value: d.id,
                                          label: d.name,
                                        }))}
                                      />
                                    </Form.Item>
                                  ) : null;
                                }}
                              </Form.Item>
                              <Button
                                type="text"
                                danger
                                aria-label="移除范围"
                                icon={<DeleteOutlined />}
                                onClick={() => removeScope(sn)}
                              />
                            </div>
                          ))}
                          <Button
                            type="dashed"
                            onClick={() => addScope({ scopeType: "SELF" })}
                          >
                            添加范围
                          </Button>
                        </>
                      )}
                    </Form.List>
                  </section>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() => add({ scopes: [{ scopeType: "SELF" }] })}
                >
                  添加角色绑定
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
        title="角色权限"
        description="组合系统已有权限，形成职责清晰的角色。"
        action={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() =>
              action.open({
                title: "新建角色",
                path: "/roles",
                fields: [
                  { name: "code", label: "角色编码", required: true, max: 64 },
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
            { title: "角色", render: (_, r) => roleLabel(r) },
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
        okText="保存"
        cancelText="取消"
        width={720}
      >
        <Failure error={error || permissions.error} />
        <p className="muted">
          只有代码注册的权限可被授予。修改后关联用户需重新登录。
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
                <small>{p.code || ""}</small>
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
  const [tab, setTab] = useState("rooms");
  const [room, setRoom] = useState<RecordData>();
  const [rulesOpen, setRulesOpen] = useState(false);
  const [rulesForm] = Form.useForm();
  const [rulesBusy, setRulesBusy] = useState(false);
  const [rulesError, setRulesError] = useState<Error>();
  const [rulesKey, setRulesKey] = useState(crypto.randomUUID());
  const rooms = useData<Page<Room>>("/rooms?size=100");
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
        title="空间管理"
        description="维护教室、座位与开放时段，保留所有历史使用记录。"
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: "rooms", label: "教室与座位" },
          { key: "campuses", label: "校区" },
          { key: "buildings", label: "楼宇" },
          ...(can(user, "user:manage")
            ? [{ key: "departments", label: "院系" }]
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
          <Failure error={rooms.error} />
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
                  title: "共享范围",
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
        </>
      )}
      <Drawer
        title={`管理教室 · ${room?.name || ""}`}
        open={!!room}
        onClose={() => setRoom(undefined)}
        size={700}
      >
        <Failure error={detail.error} />
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
          <Button
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
                <Space>
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
                    插座
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
  const data = useData<any>(
    `/statistics/usage?${query({
      fromDate: dates[0].format("YYYY-MM-DD"),
      toDate: dates[1].format("YYYY-MM-DD"),
      roomId,
    })}`
  );
  const d = data.data;
  return (
    <>
      <PageHeading
        eyebrow="SPACE INSIGHTS"
        title="使用统计"
        description="了解授权空间的预约与签到情况，帮助校园资源合理分配。"
      />
      <div className="list-toolbar">
        <DatePicker.RangePicker
          value={dates}
          allowClear={false}
          onChange={(v) => v && setDates(v)}
        />
        <Select
          placeholder="全部授权教室"
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
      <Failure error={data.error} onRetry={data.reload} />
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
        description="按业务编号追踪状态变更与资源占用，所有记录只读。"
      />
      <Tabs
        activeKey={source}
        onChange={(v) => {
          setSource(v);
          setPage(1);
        }}
        items={[
          { key: "core", label: "预约 / 审批 / 公共层" },
          { key: "repair", label: "报修服务" },
        ]}
      />
      <div className="list-toolbar">
        <Input.Search
          placeholder="按完整业务编号查询"
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
            { title: "动作", dataIndex: "action" },
            { title: "来源", dataIndex: "sourceType" },
            {
              title: "对象",
              dataIndex: "objectId",
              render: (v) => <Identifier value={v} />,
            },
            {
              title: "操作者",
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
