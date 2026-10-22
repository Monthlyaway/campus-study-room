import { useState } from "react";
import { Alert, Button, Pagination, Select, Space, Tabs, Tag } from "antd";
import { BellOutlined, CheckOutlined, ReloadOutlined } from "@ant-design/icons";
import { can } from "../access";
import { useSession } from "../services/session";
import { items, query } from "../services/api";
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
} from "../components/business/common";
const titles: Record<string, string> = {
  PRE_START: "自习即将开始",
  M1_PRE_START: "自习即将开始",
  LATE: "别忘了签到",
  M1_LATE: "别忘了签到",
  NO_SHOW: "预约因未签到已取消",
  M1_NO_SHOW: "预约因未签到已取消",
  MAINTENANCE: "教室维修影响通知",
  ASSIGNED: "你有新的维修任务",
  AWAITING_ACCEPTANCE: "维修已完工，请验收",
  CLOSED: "报修工单已关闭",
  CANCELLED: "业务已取消",
  APPROVED: "教室申请已批准",
  REJECTED: "教室申请未通过",
  ASSIGN_OVERDUE: "工单待派单超时",
  WORK_OVERDUE: "维修处理超时",
  ACCEPT_OVERDUE: "工单验收超时",
};
export default function Notifications() {
  const { user } = useSession();
  const [tab, setTab] = useState("mine");
  const [status, setStatus] = useState<string>();
  const [sourceType, setSourceType] = useState<string>();
  const [roomId, setRoomId] = useState<string>();
  const rooms = useData<any>(
    can(user, "notification:manage") ? "/rooms?size=100" : null
  );
  const [page, setPage] = useState(1);
  const data = useData<any>(
    `${tab === "mine" ? "/notifications" : "/notification-deliveries"}?${query({
      page,
      size: 15,
      status: tab === "deliveries" ? status : undefined,
      sourceType: tab === "deliveries" ? sourceType : undefined,
      roomId: tab === "deliveries" ? roomId : undefined,
    })}`
  );
  const action = useAction(data.reload);
  return (
    <>
      <PageHeading
        eyebrow="KEEP IN THE LOOP"
        title="通知中心"
        description="预约提醒、审批结果与维修进展，在这里及时查看。"
      />
      <Tabs
        activeKey={tab}
        onChange={(v) => {
          setTab(v);
          setPage(1);
        }}
        items={[
          { key: "mine", label: "我的通知" },
          ...(can(user, "notification:manage")
            ? [{ key: "deliveries", label: "邮件送达管理" }]
            : []),
        ]}
        tabBarExtraContent={
          <Button icon={<ReloadOutlined />} onClick={data.reload}>
            刷新
          </Button>
        }
      />
      {tab === "deliveries" && (
        <>
          <Alert
            className="mb-20"
            type="info"
            showIcon
            title="邮件失败不会撤销已成功的业务"
            description="重试继续使用原通知记录，不会创建重复业务。连续失败 8 次后仍每 5 分钟重试。"
          />
          <div className="list-toolbar">
            <Select
              aria-label="通知来源"
              placeholder="全部业务来源"
              allowClear
              value={sourceType}
              onChange={(v) => {
                setSourceType(v);
                setPage(1);
              }}
              options={[
                { value: "M1", label: "个人预约" },
                { value: "M2", label: "教室申请" },
                { value: "M3", label: "设施报修" },
              ]}
            />
            <Select
              aria-label="通知教室"
              placeholder="全部授权教室"
              allowClear
              showSearch
              optionFilterProp="label"
              value={roomId}
              onChange={(v) => {
                setRoomId(v);
                setPage(1);
              }}
              options={items(rooms.data).map((r) => ({
                value: r.id,
                label: r.name,
              }))}
            />

            <Select
              placeholder="全部送达状态"
              allowClear
              value={status}
              onChange={(v) => {
                setStatus(v);
                setPage(1);
              }}
              options={["PENDING", "LEASED", "RETRYING", "SENT"].map((s) => ({
                value: s,
                label: {
                  PENDING: "待发送",
                  LEASED: "发送中",
                  RETRYING: "重试中",
                  SENT: "已发送",
                }[s],
              }))}
            />
          </div>
        </>
      )}
      <Failure error={data.error} onRetry={data.reload} />
      <LoadingBlock loading={data.loading}>
        <div className="notification-list">
          {items(data.data).map((n) => (
            <article
              key={n.id}
              className={`panel notification-card ${n.readAt ? "read" : ""}`}
            >
              <div className="notification-icon">
                <BellOutlined />
              </div>
              <div className="notification-content">
                <div className="record-title">
                  <h3>{n.title || titles[n.eventType] || "业务进展通知"}</h3>
                  {!n.readAt && tab === "mine" && (
                    <Tag color="processing">未读</Tag>
                  )}
                </div>
                <p>
                  {n.message ||
                    n.content ||
                    n.payload?.message ||
                    n.payload?.title ||
                    n.payloadJson?.message ||
                    n.payloadJson?.title ||
                    `请查看关联业务的最新状态。`}
                </p>
                <span className="muted">
                  {dateTime(n.createdAt || n.dueAt)} ·{" "}
                  <Identifier value={n.sourceId} />
                </span>
                {tab === "deliveries" && (
                  <div className="delivery-info">
                    <Status value={n.status} />
                    <span>尝试 {n.attempts || 0} 次</span>
                    {n.attempts >= 8 && (
                      <Tag color="error">发送异常，持续重试</Tag>
                    )}
                    {n.lastErrorCode && <p>最近错误：{n.lastErrorCode}</p>}
                  </div>
                )}
              </div>
              <Space>
                {n.sourceId && ["M1", "M2", "M3"].includes(n.sourceType) && (
                  <Button
                    href={`${
                      (
                        {
                          M1: "/reservations",
                          M2: "/applications",
                          M3: "/repairs",
                        } as Record<string, string>
                      )[n.sourceType]
                    }?id=${encodeURIComponent(n.sourceId)}`}
                  >
                    查看业务
                  </Button>
                )}
                {tab === "mine" && !n.readAt && (
                  <Button
                    icon={<CheckOutlined />}
                    onClick={() =>
                      action.open({
                        title: "标记通知已读",
                        path: `/notifications/${n.id}/read`,
                        body: { expectedVersion: n.version },
                      })
                    }
                  >
                    已读
                  </Button>
                )}
                {tab === "deliveries" && n.status !== "SENT" && (
                  <Button
                    onClick={() =>
                      action.open({
                        title: "立即重试邮件",
                        path: `/notification-deliveries/${n.id}/retry`,
                        body: { expectedVersion: n.version },
                        success: "已安排原记录重试",
                      })
                    }
                  >
                    立即重试
                  </Button>
                )}
              </Space>
            </article>
          ))}
        </div>
        {!data.loading && !data.error && items(data.data).length === 0 && (
          <div className="panel">
            <EmptyState
              title="暂无通知"
              description="有新的预约提醒或处理进展时，我们会在这里告诉你。"
            />
          </div>
        )}
      </LoadingBlock>
      <Pagination
        className="pagination"
        current={page}
        pageSize={15}
        total={data.data?.total || 0}
        onChange={setPage}
        hideOnSinglePage
        showSizeChanger={false}
      />
      {action.dialog}
    </>
  );
}
