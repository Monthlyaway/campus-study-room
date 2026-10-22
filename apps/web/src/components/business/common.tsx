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
  `${dateTime(start)} — ${end ? shanghai(end).format("HH:mm") : "—"}`;
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
  BLOCK_PENDING: "下架同步中",
  IN_REPAIR: "维修中",
  AWAITING_ACCEPTANCE: "待验收",
  RELEASE_PENDING: "恢复同步中",
  CLOSED: "已关闭",
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
      return;
    }
    let active = true;
    setLoading(true);
    setError(undefined);
    api<T>(path)
      .then((d) => {
        if (active) setData(d);
      })
      .catch((e) => {
        if (active) setError(e);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [path, revision]);
  return { data, error, loading, reload, setData };
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
      title={e.message}
      description={
        e.requestId ? (
          <span>
            请求编号：<code>{e.requestId}</code>
          </span>
        ) : undefined
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
  return <Spin spinning={loading}>{children}</Spin>;
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
        <div className="eyebrow">{eyebrow}</div>
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
    if (!spec) return;
    let values;
    try {
      values = await form.validateFields();
    } catch {
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
        okText={(error as ApiError)?.status === 0 ? "重试原请求" : "确认"}
        cancelText={
          (error as ApiError)?.status === 409 ? "返回并重新选择" : "返回"
        }
        okButtonProps={{
          danger: spec?.danger,
          disabled: (error as ApiError)?.status === 409,
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
          <Form form={form} layout="vertical" disabled={busy}>
            {spec?.fields?.map((f) => (
              <Form.Item
                key={f.name}
                name={f.name}
                label={f.label}
                extra={f.help}
                rules={
                  f.type === "select"
                    ? f.required
                      ? [{ required: true, message: "请选择此项" }]
                      : []
                    : f.required
                    ? textRequired(f.max || 2000)
                    : f.max
                    ? [{ max: f.max, message: `最多 ${f.max} 个字` }]
                    : []
                }
              >
                {f.type === "textarea" ? (
                  <Input.TextArea rows={4} showCount maxLength={f.max} />
                ) : f.type === "select" ? (
                  <Select
                    options={f.options}
                    showSearch
                    optionFilterProp="label"
                  />
                ) : f.type === "password" ? (
                  <Input.Password autoComplete="new-password" maxLength={64} />
                ) : (
                  <Input maxLength={f.max} />
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
