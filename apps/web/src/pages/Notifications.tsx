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
  CLOSED: "本次维修已完成",
  CANCELLED: "业务已取消",
  APPROVED: "教室申请已批准",
  REJECTED: "教室申请未通过",
  ASSIGN_OVERDUE: "请尽快安排维修人员",
  WORK_OVERDUE: "维修已超时，请跟进处理",
  ACCEPT_OVERDUE: "维修等待验收已超时",
};
const deliveryLabels: Record<string, string> = { PENDING: "待发送", LEASED: "发送中", RETRYING: "等待重试", SENT: "已发送" };
const sourceLabels: Record<string, string> = { M1: "座位预约", M2: "教室申请", M3: "设施报修" };
const detailLabels: Record<string, string> = { M1: "查看预约", M2: "查看申请", M3: "查看报修" };
const guidance: Record<string, string> = {
  PRE_START: "请按预约时间到达教室，开始后 15 分钟内完成签到。",
  M1_PRE_START: "请按预约时间到达教室，开始后 15 分钟内完成签到。",
  LATE: "签到时间即将结束，请打开预约详情，输入教室当天签到码。",
  M1_LATE: "签到时间即将结束，请打开预约详情，输入教室当天签到码。",
  NO_SHOW: "未在签到时间内完成签到，座位已释放。可重新选择空闲时段预约。",
  M1_NO_SHOW: "未在签到时间内完成签到，座位已释放。可重新选择空闲时段预约。",
  MAINTENANCE: "教室维修影响了原有安排。请查看记录了解结果，维修造成的取消不计未签到违约。",
  ASSIGNED: "请打开工单查看故障位置和完工截止时间，准备就绪后开始维修。",
  AWAITING_ACCEPTANCE: "维修人员已提交完工说明，请核实问题是否解决后验收。",
  CLOSED: "本次维修已完成。报修人可以打开工单评价服务。",
  ASSIGN_OVERDUE: "工单超过派单期限，请调度员安排维修人员，物业管理员跟进。",
  WORK_OVERDUE: "工单超过完工期限，请负责维修人员继续处理，物业管理员跟进。",
  ACCEPT_OVERDUE: "工单超过验收期限。报修人或物业管理员需检查维修结果并完成验收。",
};
export default function Notifications() {
  const { user } = useSession();
  const [tab, setTab] = useState("mine");
  const [status, setStatus] = useState<string>();
  const [sourceType, setSourceType] = useState<string>();
  const [selectedRoom, setSelectedRoom] = useState<{ value: string; label: string }>();
  const [roomSearch, setRoomSearch] = useState("");
  const [roomPage, setRoomPage] = useState(1);
  const rooms = useData<any>(
    can(user, "notification:manage") ? `/notification-scope-rooms?${query({ q: roomSearch, page: roomPage, size: 20 })}` : null
  );
  const [page, setPage] = useState(1);
  const data = useData<any>(
    `${tab === "mine" ? "/notifications" : "/notification-deliveries"}?${query({
      page,
      size: 15,
      status: tab === "deliveries" ? status : undefined,
      sourceType: tab === "deliveries" ? sourceType : undefined,
      roomId: tab === "deliveries" ? selectedRoom?.value : undefined,
    })}`
  );
  const action = useAction(data.reload);
  return (
    <>
      <PageHeading
        eyebrow="KEEP IN THE LOOP"
        title="通知中心"
        description="查看与你有关的提醒，打开对应记录继续处理。通知记录的是当时进展，详情展示最新结果。"
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
            description="这是邮件发送进度，不需要审批。发送失败时系统自动重试；可点击“立即重试”提前尝试。标记站内通知已读不会停止发信。"
          />
          <Failure error={rooms.error} onRetry={rooms.reload} />
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
              filterOption={false}
              labelInValue
              onSearch={(value) => { setRoomSearch(value); setRoomPage(1); }}
              loading={rooms.loading}
              disabled={!!rooms.error}
              value={selectedRoom}
              onChange={(v) => {
                setSelectedRoom(v);
                setPage(1);
              }}
              options={items(rooms.data).map((r) => ({
                value: r.id,
                label: [r.name, r.buildingName, r.enabled === false ? "已停用" : ""].filter(Boolean).join(" · "),
              }))}
              notFoundContent={rooms.loading ? "正在查找教室…" : "没有匹配的授权教室"}
              popupRender={(menu) => <>
                {menu}
                {(rooms.data?.total || 0) > 20 && <div style={{ padding: 8 }} onMouseDown={(event) => event.preventDefault()}>
                  <Pagination simple current={roomPage} pageSize={20} total={rooms.data?.total || 0} onChange={setRoomPage} showSizeChanger={false} />
                </div>}
              </>}
            />

            <Select
              aria-label="邮件发送状态"
              placeholder="全部送达状态"
              allowClear
              value={status}
              onChange={(v) => {
                setStatus(v);
                setPage(1);
              }}
              options={["PENDING", "LEASED", "RETRYING", "SENT"].map((s) => ({
                value: s,
                label: deliveryLabels[s],
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
                <BellOutlined aria-hidden="true" />
              </div>
              <div className="notification-content">
                <div className="record-title">
                  <h3>{n.title || titles[n.eventType] || "业务进展通知"}</h3>
                  {!n.readAt && tab === "mine" && (
                    <Tag color="processing">未读</Tag>
                  )}
                </div>
                {sourceLabels[n.sourceType] && <Tag>{sourceLabels[n.sourceType]}</Tag>}
                <p>
                  {n.message ||
                    n.content ||
                    n.payload?.message ||
                    n.payloadJson?.message ||
                    guidance[n.eventType] ||
                    "请打开关联记录，查看最新状态和下一步操作。"}
                </p>
                {(n.payload?.title || n.payloadJson?.title) && <p>相关事项：{n.payload?.title || n.payloadJson?.title}</p>}
                <span className="muted">{dateTime(n.createdAt || n.dueAt)}</span>
                {tab === "deliveries" && (
                  <div className="delivery-info">
                    <Tag color={n.status === "SENT" ? "success" : n.status === "RETRYING" ? "warning" : "processing"}>{deliveryLabels[n.status] || "发送状态待确认"}</Tag>
                    <span>尝试 {n.attempts || 0} 次</span>
                    {n.status !== "SENT" && n.attempts >= 8 && (
                      <Tag color="error">发送异常，持续重试</Tag>
                    )}
                    {n.nextAttemptAt && n.status !== "SENT" && <p>下次尝试：{dateTime(n.nextAttemptAt)}</p>}
                    {n.lastErrorCode && <p>暂未发送成功，系统会继续重试。需要排查时展开下方记录信息。</p>}
                    <details>
                      <summary>查看记录信息</summary>
                      <p>关联记录编号：<Identifier value={n.sourceId} /></p>
                      {n.lastErrorCode && <p>错误代码：{n.lastErrorCode}</p>}
                    </details>
                  </div>
                )}
              </div>
              <Space wrap>
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
                    {detailLabels[n.sourceType]}
                  </Button>
                )}
                {tab === "mine" && !n.readAt && (
                  <Button
                    icon={<CheckOutlined />}
                    onClick={() =>
                      action.open({
                        title: "标记为已读",
                        description: "只改变这条通知的未读标记，不影响业务处理和邮件发送。",
                        success: "已标记为已读",
                        path: `/notifications/${n.id}/read`,
                        body: { expectedVersion: n.version },
                      })
                    }
                  >
                    标记已读
                  </Button>
                )}
                {tab === "deliveries" && n.status !== "SENT" && (
                  <Button
                    onClick={() =>
                      action.open({
                        title: "立即重试邮件",
                        description: "系统将再次尝试发送这封邮件，不会重复创建预约、申请或工单。",
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
              title={tab === "mine" ? "暂无通知" : "没有符合条件的邮件"}
              description={tab === "mine" ? "有新的预约提醒或处理进展时，会在这里显示。" : "尝试清除筛选条件，或稍后刷新查看新的邮件发送记录。"}
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
