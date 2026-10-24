import { useEffect, useState } from "react";
import { Alert, App, Button, Form, Modal, Pagination, Select, Space, Tag } from "antd";
import { DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import { api, ApiError, items, query } from "../services/api";
import { roleLabel, permissionLabel } from "../access";
import { Failure, useData } from "../components/business/common";
import type { Page, RecordData } from "../services/types";

export const responsibility = (role?: RecordData) => {
  const descriptions: Record<string, string> = {
    STUDENT: "查找座位、预约和签到，处理自己的预约与报修。",
    ROOM_APPLICANT: "为活动申请整间教室，并查看两级审批进度。",
    DEPT_APPROVER: "审核所负责院系师生提交的教室申请（第一级）。",
    SPACE_APPROVER: "确认所负责教室是否可用，完成第二级审批。",
    ROOM_ADMIN: "维护所负责教室的座位、开放时间和签到屏幕。",
    PROPERTY_DISPATCHER: "为所负责教室的故障安排维修人员。",
    REPAIRER: "处理派给自己的维修任务，提交完工说明。",
    PROPERTY_ADMIN: "监督所负责教室的维修并验收，不能验收自己的维修。",
    OPERATOR: "查看所负责教室的使用统计。",
    SYSTEM_ADMIN: "维护账号、授权、系统参数和审计；业务审批需另行授权。",
  };
  return role ? descriptions[role.code] || "自定义职责。实际能力以此角色已勾选的权限为准。" : "先选择这个人需要承担的职责。";
};
const scopeOptions = [
  { value: "SELF", label: "仅本人业务 / 派给本人的任务" },
  { value: "ROOM", label: "选定教室" },
  { value: "DEPARTMENT", label: "选定院系" },
  { value: "SHARED", label: "全校共享教室" },
  { value: "ALL", label: "全校所有对象" },
];
const isDepartmentReviewer = (role?: RecordData) => (role?.permissionCodes || []).includes("room_application:review_dept");
const suggestedScope = (role?: RecordData) =>
  ["STUDENT", "ROOM_APPLICANT", "REPAIRER"].includes(role?.code) ? "SELF"
    : isDepartmentReviewer(role) ? "DEPARTMENT"
    : role?.code === "SYSTEM_ADMIN" ? "ALL" : "ROOM";

export function scopeExplanation(type: string, role?: RecordData) {
  if (type === "SELF") return role?.code === "REPAIRER"
    ? "只能处理派给自己的维修工单。"
    : "用于本人预约、申请等个人业务。学生仍可使用共享教室和本院系教室；这不会授予管理他人业务的权限。";
  if (type === "DEPARTMENT") return isDepartmentReviewer(role)
    ? "按申请人的所属院系分工：审核该院系师生提交的教室申请。"
    : "负责归属于所选院系的教室；学校共享教室需要另加负责对象。";
  if (type === "ROOM") return "只负责下面选中的教室。多个教室请逐项添加；每项都是同一职责负责的一个对象。";
  if (type === "SHARED") return "负责没有归属院系的共享教室，不包含院系专属教室。";
  if (type === "ALL") return "此职责覆盖全校，但不会增加这个角色本身没有的能力。请确认确实需要全校范围。";
  return "为这项职责选择负责对象。";
}

function RoomChoice({ value, onChange, known, id }: {
  value?: string; onChange?: (value: string) => void; known: Record<string, string>; id?: string;
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const rooms = useData<Page<RecordData>>("/role-scope-rooms?" + query({ q: search, page, size: 20 }));
  const [selectedNames, setSelectedNames] = useState<Record<string, string>>(known);
  const choices = items(rooms.data).map((r) => ({
    value: r.id,
    label: r.name + " · " + r.buildingName + " · " + (r.departmentName || "全校共享") + (r.enabled ? "" : "（已停用）"),
  }));
  if (value && !choices.some((c) => c.value === value)) {
    choices.unshift({ value, label: selectedNames[value] || known[value] || "已选教室（名称暂不可用，请重试查询）" });
  }
  return <>
    <Select id={id} aria-label="选择教室" virtual={false} value={value}
      onChange={(next, option: any) => {
        setSelectedNames((previous) => ({ ...previous, [next]: option.label }));
        onChange?.(next);
      }}
      showSearch={{ filterOption: false, onSearch: (next) => { setSearch(next); setPage(1); } }}
      placeholder="搜索教室名称或教室编号…"
      options={choices}
      loading={rooms.loading}
      notFoundContent={rooms.loading ? "正在查找教室…" : "没有匹配的教室，请换个名称或编号"}
      popupRender={(menu) => <>{menu}<div style={{ padding: 8 }}>
        <Pagination simple current={page} total={rooms.data?.total || 0} pageSize={20}
          onChange={setPage} showSizeChanger={false} hideOnSinglePage />
      </div></>}
    />
    <Failure error={rooms.error} onRetry={rooms.reload} />
  </>;
}

export function AccessEditor({ target, roles, departments, onClose, onSaved }: {
  target: RecordData; roles: RecordData[]; departments: RecordData[];
  onClose: () => void; onSaved: () => void;
}) {
  const [form] = Form.useForm();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [dirty, setDirty] = useState(false);
  const [pendingAt, setPendingAt] = useState<number>();
  const [expired, setExpired] = useState(false);
  const [pending, setPending] = useState<Record<string, any>>();
  const { message, modal } = App.useApp();
  const bindings = Form.useWatch("bindings", form) || [];
  const original = target.roleBindings || target.bindings || [];
  const knownRooms = Object.fromEntries(original.flatMap((b: any) => (b.scopes || [])
    .filter((s: any) => s.roomId).map((s: any) => [s.roomId, s.roomName || "已选教室（名称暂不可用）"])));
  const knownDepartments = Object.fromEntries(original.flatMap((b: any) => (b.scopes || [])
    .filter((s: any) => s.departmentId).map((s: any) => [s.departmentId, s.departmentName || "已选院系"])));
  const conflict = error instanceof ApiError && error.status === 409;
  useEffect(() => { form.setFieldsValue({ bindings: original.map((b: any) => ({
    roleId: b.roleId, scopes: b.scopes || [],
  })) }); }, [target.id]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
  const close = () => {
    if (busy) return;
    if (!dirty && !pending) return onClose();
    modal.confirm({
      title: pending ? "保存结果尚未确认" : "放弃尚未保存的授权调整？",
      content: pending ? "返回后请先刷新账号列表，核对授权是否已生效，再开始新的修改。" : "关闭后将保留上一次保存的授权。",
      okText: "关闭并返回", cancelText: "继续查看", onOk: onClose,
    });
  };
  const save = async () => {
    if (busy || conflict || expired) return;
    if (pendingAt && Date.now() - pendingAt >= 24 * 60 * 60 * 1000) {
      setExpired(true);
      return;
    }
    try {
      const values = pending ? undefined : await form.validateFields();
      const body = pending || {
        bindings: (values.bindings || []).map((b: any) => ({
          roleId: b.roleId,
          scopes: (b.scopes || []).map((s: any) => ({
            scopeType: s.scopeType,
            ...(s.scopeType === "DEPARTMENT" ? { departmentId: s.departmentId }
              : s.scopeType === "ROOM" ? { roomId: s.roomId } : {}),
          })),
        })),
        expectedVersion: target.version,
      };
      setBusy(true);
      setError(undefined);
      try {
        await api("/users/" + target.id + "/role-bindings", { method: "PUT", body, key });
        message.success("授权已保存，请通知该用户重新登录");
        onSaved();
      } catch (e) {
        if (e instanceof ApiError && e.status === 0) { setPending(body); setPendingAt((previous) => previous || Date.now()); }
        throw e;
      }
    } catch (e: any) {
      if (e.errorFields?.length) form.scrollToField(e.errorFields[0].name, { focus: true });
      else if (e instanceof Error) setError(e);
    } finally { setBusy(false); }
  };
  return <Modal open title={"设置职责与负责对象 · " + target.displayName} width={760}
    onCancel={close} onOk={save} confirmLoading={busy}
    okButtonProps={{ disabled: conflict || expired }}
    okText={pending ? "重试原请求" : "保存授权"} cancelText="返回账号列表">
    <p>先选“这个人能做什么”，再选“由他负责哪些对象”。每项职责单独设置，保存后该用户需重新登录。</p>
    <Failure error={error} />
    {expired && <Alert type="warning" showIcon title="原请求已超过可安全重试的时间"
      description="请返回并刷新账号列表，先核对授权结果，再决定是否需要新的修改。" className="mb-20" />}
    {conflict && <Alert type="warning" showIcon title="这位用户的授权已发生变化"
      description="返回账号列表并刷新，再打开最新授权进行调整。" className="mb-20" />}
    {pending && <Alert type="warning" showIcon title="尚未确认是否保存成功"
      description="当前内容已保留，请重试原请求；重试不会重复添加授权。" className="mb-20" />}
    <Form form={form} layout="vertical" disabled={busy || !!pending || conflict}
      onValuesChange={() => { setDirty(true); setKey(crypto.randomUUID()); }}>
      <Form.List name="bindings">
        {(fields, { add, remove }) => <>
          {fields.length === 0 && <Alert type="info" title="尚未分配职责" description="添加所需职责后再保存。没有业务职责的账号不能创建新的预约或申请。" className="mb-20" />}
          {fields.map(({ key: fieldKey, name }) => {
            const selected = roles.find((r) => r.id === bindings[name]?.roleId);
            const codes = selected?.permissionCodes || [];
            return <section className="binding-card" key={fieldKey}>
              <div className="binding-title">
                <Form.Item name={[name, "roleId"]} label={(name + 1) + ". 能做什么"}
                  rules={[{ required: true, message: "请选择一项职责" }]}>
                  <Select placeholder="选择职责…" showSearch={{ optionFilterProp: "label" }}
                    options={roles.map((r) => ({ value: r.id, label: roleLabel(r) + (r.code === "STUDENT" && !target.schoolNo ? "（需先有学号）" : ""),
                      disabled: (r.code === "STUDENT" && !target.schoolNo) || bindings.some((b: any, i: number) => i !== name && b?.roleId === r.id) }))} />
                </Form.Item>
                <Button type="text" danger disabled={busy || !!pending || conflict} icon={<DeleteOutlined />} aria-label={"移除第 " + (name + 1) + " 项职责"}
                  onClick={() => { remove(name); setDirty(true); setKey(crypto.randomUUID()); }}>移除</Button>
              </div>
              <p>{responsibility(selected)}</p>
              {selected && <details className="mb-20"><summary>{"查看此角色当前的 " + codes.length + " 项权限"}</summary>
                <Space wrap>{codes.map((code: string) => <Tag key={code}>{permissionLabel(code)}</Tag>)}</Space>
              </details>}
              <Form.List name={[name, "scopes"]}>
                {(scopes, { add: addScope, remove: removeScope }) => <>
                  <h4>负责哪些对象</h4>
                  {!scopes.length && <p className="muted">尚未设置负责对象。管理职责需要明确对象后才能处理业务。</p>}
                  {scopes.map(({ key: scopeKey, name: scopeName }) => {
                    const scope = bindings[name]?.scopes?.[scopeName] || {};
                    return <div key={scopeKey} className="mb-20">
                      <div className="scope-row">
                        <Form.Item name={[scopeName, "scopeType"]} label={"负责对象 " + (scopeName + 1)}
                          rules={[{ required: true, message: "请选择负责对象的类型" }]}>
                          <Select options={scopeOptions} placeholder="选择对象类型…" />
                        </Form.Item>
                        {scope.scopeType === "ROOM" && <Form.Item name={[scopeName, "roomId"]} label="选择教室"
                          rules={[{ required: true, message: "请按名称选择教室" }]}>
                          <RoomChoice known={knownRooms} />
                        </Form.Item>}
                        {scope.scopeType === "DEPARTMENT" && <Form.Item name={[scopeName, "departmentId"]}
                          label={isDepartmentReviewer(selected) ? "申请人所属院系" : "教室所属院系"}
                          rules={[{ required: true, message: "请选择院系" }]}>
                          <Select showSearch={{ optionFilterProp: "label" }} placeholder="选择院系…"
                            options={[...departments.map((d) => ({ value: d.id, label: d.name + (d.enabled ? "" : "（已停用）") })),
                              ...Object.entries(knownDepartments).filter(([id]) => !departments.some((d) => d.id === id))
                                .map(([id, label]) => ({ value: id, label: String(label) }))]} />
                        </Form.Item>}
                        <Button type="text" danger disabled={busy || !!pending || conflict} icon={<DeleteOutlined />} aria-label={"移除第 " + (scopeName + 1) + " 个负责对象"}
                          onClick={() => { removeScope(scopeName); setDirty(true); setKey(crypto.randomUUID()); }} />
                      </div>
                      <p className="muted" style={{ marginTop: 0 }}>{scopeExplanation(scope.scopeType, selected)}</p>
                    </div>;
                  })}
                  <Button type="dashed" disabled={!selected || busy || !!pending || conflict}
                    onClick={() => { addScope({ scopeType: suggestedScope(selected) }); setDirty(true); setKey(crypto.randomUUID()); }}>
                    {scopes.length ? "再添加一个负责对象" : "添加负责对象"}
                  </Button>
                </>}
              </Form.List>
            </section>;
          })}
          <Button type="dashed" block icon={<PlusOutlined />} disabled={busy || !!pending || conflict}
            onClick={() => { add({ scopes: [] }); setDirty(true); setKey(crypto.randomUUID()); }}>添加另一项职责</Button>
        </>}
      </Form.List>
    </Form>
    <Alert style={{ marginTop: 20 }} type="info" showIcon title="审批与维修仍须分工"
      description="申请人不能审批自己的申请，两级审批须由不同人员完成；维修人不能验收自己的维修。系统管理员也遵守这些规则。" />
  </Modal>;
}
