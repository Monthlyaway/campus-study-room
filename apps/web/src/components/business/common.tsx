import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Spin,
  Tag,
  Typography,
} from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";
import timezone from "dayjs/plugin/timezone";
import "dayjs/locale/zh-cn";
import { api, ApiError } from "../../services/api";
dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.locale("zh-cn");
export { dayjs };
export const shanghai = (value?: string | number | Date) =>
  dayjs(value).tz("Asia/Shanghai");
export const dateTime = (value?: string) =>
  value ? shanghai(value).format("MM月DD日 HH:mm") : "—";
export const slotLabel = (start?: string, end?: string) =>
  `${dateTime(start)} — ${end ? shanghai(end).format(start && shanghai(start).isSame(shanghai(end), "day") ? "HH:mm" : "MM月DD日 HH:mm") : "—"}`;
export const statusLabels: Record<string, string> = {
  RESERVED: "待签到",
  CHECKED_IN: "使用中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  NO_SHOW: "未签到",
  INTERRUPTED: "维修中断",
  PENDING: "审批中",
  APPROVED: "已批准",
  REJECTED: "未通过",
  EXPIRED: "已过期",
  SUBMITTED: "待派单",
  ASSIGNED: "待维修",
  BLOCK_PENDING: "正在暂停教室使用",
  IN_REPAIR: "维修中",
  AWAITING_ACCEPTANCE: "待验收",
  RELEASE_PENDING: "正在恢复教室使用",
  CLOSED: "已完成",
  WAITING: "待前级审批",
  SENT: "已发送",
  RETRYING: "重试中",
  LEASED: "发送中",
};
export function Status({ value }: { value?: string }) {
  const color = [
    "CHECKED_IN",
    "APPROVED",
    "COMPLETED",
    "CLOSED",
    "SENT",
  ].includes(value || "")
    ? "success"
    : ["REJECTED", "NO_SHOW"].includes(value || "")
    ? "error"
    : ["BLOCK_PENDING", "RELEASE_PENDING", "RETRYING"].includes(value || "")
    ? "warning"
    : [
        "RESERVED",
        "PENDING",
        "ASSIGNED",
        "SUBMITTED",
        "IN_REPAIR",
        "AWAITING_ACCEPTANCE",
      ].includes(value || "")
    ? "processing"
    : "default";
  return <Tag color={color}>{statusLabels[value || ""] || value || "—"}</Tag>;
}
export function useData<T = any>(path: string | null) {
  const [data, setData] = useState<T>();
  const [dataPath, setDataPath] = useState<string | null>(null);
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const reload = useCallback(() => setRevision((r) => r + 1), []);
  useEffect(() => {
    window.addEventListener("business-data-changed", reload);
    return () => window.removeEventListener("business-data-changed", reload);
  }, [reload]);
  useEffect(() => {
    if (!path) {
      setData(undefined);
      setError(undefined);
      setLoading(false);
      return;
    }
    let active = true;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 20000);
    setLoading(true);
    setError(undefined);
    api<T>(path, { signal: controller.signal })
      .then((d) => {
        if (active) { setData(d); setDataPath(path); }
      })
      .catch((e) => {
        if (active) setError(e);
      })
      .finally(() => {
        window.clearTimeout(timeout);
        if (active) setLoading(false);
      });
    return () => {
      active = false;
      window.clearTimeout(timeout);
      controller.abort();
    };
  }, [path, revision]);
  return { data: dataPath === path ? data : undefined, error, loading, reload, setData };
}
export function Failure({
  error,
  onRetry,
}: {
  error?: Error;
  onRetry?: () => void;
}) {
  if (!error) return null;
  const e = error as ApiError;
  return (
    <Alert
      className="error-box"
      type={e.status === 0 ? "warning" : "error"}
      showIcon
      role="alert"
      title={e.message}
      description={
        <div>
          {e.status === 0 ? <p>尚未收到操作结果。查询可以重试；提交操作请重试原请求，或先查看记录确认是否已完成。</p>
            : e.status === 403 ? <p>请联系系统管理员确认你能办理的业务和负责的教室；授权调整后需要重新登录。</p>
            : e.status === 404 ? <p>记录可能不存在，或不在你可查看的范围内。请返回列表重新选择。</p>
            : null}
          {e.requestId ? <small>联系管理员时提供请求编号：<code>{e.requestId}</code></small> : null}
        </div>
      }
      action={
        onRetry ? (
          <Button size="small" onClick={onRetry} icon={<ReloadOutlined />}>
            重试
          </Button>
        ) : undefined
      }
    />
  );
}
export function LoadingBlock({
  loading,
  children,
}: {
  loading: boolean;
  children: React.ReactNode;
}) {
  return <div aria-busy={loading}><Spin spinning={loading}>{children}</Spin></div>;
}
export function EmptyState({
  title = "这里还没有记录",
  description = "新的记录会在这里出现。",
  action,
}: {
  title?: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <Empty
      image={Empty.PRESENTED_IMAGE_SIMPLE}
      description={
        <>
          <strong>{title}</strong>
          <p>{description}</p>
        </>
      }
    >
      {action}
    </Empty>
  );
}
export function PageHeading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <header className="page-heading">
      <div>
        <div className="eyebrow" aria-hidden="true">{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </header>
  );
}
export const textRequired = (max: number) => [
  { required: true, whitespace: true, message: "请填写此项" },
  { max, message: `最多 ${max} 个字` },
];
export interface Field {
  name: string;
  label: string;
  type?: "text" | "textarea" | "select" | "password";
  required?: boolean;
  max?: number;
  options?: { label: string; value: any }[];
  initial?: any;
  help?: string;
  placeholder?: string;
}
export interface ActionSpec {
  title: string;
  path: string;
  method?: string;
  body?: Record<string, any>;
  fields?: Field[];
  description?: React.ReactNode;
  danger?: boolean;
  success?: string;
  confirmText?: string;
  transform?: (body: Record<string, any>) => Record<string, any>;
}
export function useAction(onSuccess?: (result: any) => void) {
  const [spec, setSpec] = useState<ActionSpec>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const [form] = Form.useForm();
  const attempt = useRef<
    { fingerprint: string; key: string; at: number } | undefined
  >(undefined);
  const { message } = App.useApp();
  const open = (next: ActionSpec) => {
    setError(undefined);
    attempt.current = undefined;
    form.resetFields();
    form.setFieldsValue(
      Object.fromEntries((next.fields || []).map((f) => [f.name, f.initial]))
    );
    setSpec(next);
  };
  const submit = async () => {
    if (!spec || busy || (error as ApiError)?.status === 409) return;
    let values;
    try {
      values = await form.validateFields();
    } catch (validation) {
      const field = (validation as { errorFields?: { name: (string | number)[] }[] }).errorFields?.[0]?.name;
      if (field) form.scrollToField(field, { focus: true, block: "center" });
      return;
    }
    const rawBody = { ...spec.body, ...values };
    const body = spec.transform ? spec.transform(rawBody) : rawBody;
    const fingerprint = JSON.stringify({
      path: spec.path,
      method: spec.method,
      body,
    });
    if (!attempt.current || attempt.current.fingerprint !== fingerprint)
      attempt.current = {
        fingerprint,
        key: crypto.randomUUID(),
        at: Date.now(),
      };
    if (Date.now() - attempt.current.at > 86400000) {
      setError(
        new Error(
          "原请求已超过 24 小时，请先检查业务记录，再决定是否发起新操作。"
        )
      );
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      const result = await api(spec.path, {
        method: spec.method || "POST",
        body,
        key: attempt.current.key,
      });
      message.success(spec.success || "操作成功");
      setSpec(undefined);
      onSuccess?.(result);
    } catch (e) {
      setError(e as Error);
      if ((e as ApiError).status === 409)
        window.dispatchEvent(new Event("business-data-changed"));
    } finally {
      setBusy(false);
    }
  };
  return {
    open,
    dialog: (
      <Modal
        zIndex={1200}
        open={!!spec}
        title={spec?.title}
        onCancel={() => {
          if (!busy) setSpec(undefined);
        }}
        onOk={submit}
        confirmLoading={busy}
        okText={(error as ApiError)?.status === 0 ? "重试原请求" : spec?.confirmText || spec?.title || "提交"}
        cancelText={
          (error as ApiError)?.status === 409 ? "返回并重新选择" : "返回"
        }
        okButtonProps={{
          danger: spec?.danger,
          disabled: (error as ApiError)?.status === 409,
          // Loading icons can outlive their exit animation while a dialog is hidden.
          // Keep the action name stable for assistive technology and expose progress separately.
          "aria-label": (error as ApiError)?.status === 0 ? "重试原请求" : spec?.confirmText || spec?.title || "提交",
          "aria-busy": busy,
        }}
        forceRender
        afterClose={() => form.resetFields()}
      >
        <div className="modal-content">
          {spec?.description}
          <Failure error={error} />
          {(error as ApiError)?.status === 409 && (
            <p className="muted">已刷新服务器数据，请返回后重新选择操作。</p>
          )}
          <Form form={form} layout="vertical" disabled={busy || [0, 409].includes((error as ApiError)?.status)} onFinish={submit}>
            {spec?.fields?.map((f) => (
              <Form.Item
                key={f.name}
                name={f.name}
                label={f.label}
                extra={f.help}
                rules={
                  f.type === "select"
                    ? f.required
                      ? [{ required: true, message: `请选择${f.label}` }]
                      : []
                    : f.required
                    ? textRequired(f.max || 2000)
                    : f.max
                    ? [{ max: f.max, message: `最多 ${f.max} 个字` }]
                    : []
                }
              >
                {f.type === "textarea" ? (
                  <Input.TextArea rows={4} showCount maxLength={f.max} placeholder={f.placeholder} />
                ) : f.type === "select" ? (
                  <Select
                    options={f.options}
                    showSearch
                    optionFilterProp="label"
                    placeholder={f.placeholder || `请选择${f.label}…`}
                    notFoundContent="暂无可选项，请先完成相关资料设置"
                  />
                ) : f.type === "password" ? (
                  <Input.Password autoComplete="new-password" maxLength={64} />
                ) : (
                  <Input maxLength={f.max} placeholder={f.placeholder} autoComplete="off" />
                )}
              </Form.Item>
            ))}
          </Form>
        </div>
      </Modal>
    ),
  };
}
export function Identifier({ value }: { value?: string }) {
  return (
    <Typography.Text
      type="secondary"
      copyable={value ? { text: value } : false}
    >
      <code>{value?.slice(0, 8).toUpperCase() || "—"}</code>
    </Typography.Text>
  );
}
