import { useState } from "react";
import {
  Alert,
  Button,
  DatePicker,
  Drawer,
  Pagination,
  Select,
  Space,
  Tabs,
  Descriptions,
} from "antd";
import {
  ReloadOutlined,
  ArrowRightOutlined,
  CalendarOutlined,
  EnvironmentOutlined,
} from "@ant-design/icons";
import { history } from "umi";
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
  slotLabel,
  Identifier,
  statusLabels,
  shanghai,
} from "../components/business/common";
import type { Page, RecordData } from "../services/types";
function reservationNextStep(record: RecordData) {
  if (record.status === "RESERVED")
    return `签到时间：${dateTime(record.startAt)} 至 ${dateTime(
      shanghai(record.startAt).add(15, "minute").format()
    )} 前。到教室查看当日签到码。`;
  if (record.status === "CHECKED_IN")
    return "已签到，可按预约时段使用座位；结束后自动完成。";
  if (record.cancelReason === "MAINTENANCE" || record.status === "INTERRUPTED")
    return "教室维修影响了本次预约，不计未签到。如需继续自习，请重新选座。";
  if (record.status === "NO_SHOW")
    return "超过签到时间，座位已释放；你仍可重新预约。";
  if (record.status === "CANCELLED")
    return "预约已取消，座位已释放。需要使用时可再次预约。";
  if (record.status === "COMPLETED")
    return "本次使用已结束。再次预约会创建一条新的记录。";
  return "";
}
export default function Reservations() {
  const { user } = useSession();
  const maySearch = can(user, "seat:search");
  const [tab, setTab] = useState("bookings");
  const [status, setStatus] = useState<string>();
  const [range, setRange] = useState<any>();
  const [page, setPage] = useState(1);
  const [id, setId] = useState<string | undefined>(
    new URLSearchParams(window.location.search).get("id") || undefined
  );
  const data = useData<Page<RecordData>>(
    `${tab === "violations" ? "/violations" : "/reservations"}?${query({
      page,
      size: 12,
      status: tab === "bookings" ? status : undefined,
      startFrom:
        tab === "bookings" && range?.[0]
          ? range[0].format("YYYY-MM-DD") + "T00:00:00+08:00"
          : undefined,
      startTo:
        tab === "bookings" && range?.[1]
          ? range[1].add(1, "day").format("YYYY-MM-DD") + "T00:00:00+08:00"
          : undefined,
    })}`
  );
  const detail = useData<RecordData>(id ? `/reservations/${id}` : null);
  const action = useAction(() => {
    data.reload();
    detail.reload();
  });
  const controls = (r: RecordData) => (
    <Space wrap>
      {r.allowedActions?.includes("check-in") && (
        <Button
          type="primary"
          onClick={() =>
            action.open({
              title: "输入教室签到码",
              confirmText: "确认签到",
              path: `/reservations/${r.id}/check-in`,
              body: { expectedVersion: r.version },
              fields: [
                {
                  name: "roomCode",
                  label: "教室当日签到码",
                  required: true,
                  max: 32,
                  placeholder: "输入教室屏幕上的 8 位编码…",
                  help: "请查看当前教室的签到屏幕；无需填写教室编号或座位号。",
                },
              ],
              description: (
                <p>
                  {r.roomName} · {r.seatCode} 号座位
                  <br />
                  {slotLabel(r.startAt, r.endAt)}
                </p>
              ),
              success: "签到成功，祝你学习顺利",
            })
          }
        >
          立即签到
        </Button>
      )}
      {r.allowedActions?.includes("cancel") && (
        <Button
          danger
          onClick={() =>
            action.open({
              title: "取消这次预约？",
              confirmText: "确认取消预约",
              success: "预约已取消，座位已释放",
              path: `/reservations/${r.id}/cancel`,
              body: { expectedVersion: r.version },
              danger: true,
              description: (
                <p>
                  {r.roomName} · {r.seatCode} 号座位
                  <br />
                  {slotLabel(r.startAt, r.endAt)}
                  <br />
                  取消后会释放这个座位，其他同学可以预约。
                </p>
              ),
            })
          }
        >
          取消预约
        </Button>
      )}
      {maySearch && can(user, "reservation:create") && (
        <Button
          onClick={() => {
            history.push(`/seats?roomId=${r.roomId}&seatId=${r.seatId}`);
          }}
        >
          再次预约
        </Button>
      )}
    </Space>
  );
  return (
    <>
      <PageHeading
        eyebrow="MY STUDY PLAN"
        title="我的座位预约"
        description="查看使用时间、到场签到或取消预约。取消须在开始前完成，签到须在开始后的 15 分钟内完成。"
        action={
          maySearch ? (
            <Button type="primary" onClick={() => history.push("/seats")}>
              去选座 <ArrowRightOutlined />
            </Button>
          ) : undefined
        }
      />
      <Tabs
        activeKey={tab}
        onChange={(v) => {
          setTab(v);
          setPage(1);
        }}
        items={[
          { key: "bookings", label: "预约记录" },
          { key: "violations", label: "未签到记录" },
        ]}
      />
      {tab === "bookings" && (
        <div className="list-toolbar">
          <Select
            aria-label="预约状态"
            placeholder="全部状态"
            allowClear
            value={status}
            onChange={(v) => {
              setStatus(v);
              setPage(1);
            }}
            options={[
              "RESERVED",
              "CHECKED_IN",
              "COMPLETED",
              "CANCELLED",
              "NO_SHOW",
              "INTERRUPTED",
            ].map((v) => ({ value: v, label: statusLabels[v] }))}
          />
          <DatePicker.RangePicker
            value={range}
            aria-label="预约开始日期范围"
            placeholder={["开始日期", "结束日期"]}
            onChange={(v) => {
              setRange(v);
              setPage(1);
            }}
          />
          {(status || range) && (
            <Button
              onClick={() => {
                setStatus(undefined);
                setRange(undefined);
                setPage(1);
              }}
            >
              清除筛选
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={data.reload}>
            刷新
          </Button>
        </div>
      )}
      {tab === "violations" && (
        <Alert
          className="mb-20"
          type="info"
          showIcon
          title="仅记录未按时签到的预约"
          description="维修造成的取消或中断不计入未签到。记录用于本人查看，不影响预约资格。"
        />
      )}
      <Failure error={data.error} onRetry={data.reload} />
      <LoadingBlock loading={data.loading}>
        <div className="record-list">
          {items<RecordData>(data.data).map((r) => (
            <article
              className="panel reservation-card"
              data-reservation-id={r.id}
              key={r.id}
            >
              <div className="record-date">
                <CalendarOutlined />
                <strong>
                  {dateTime(r.startAt || r.occurredAt).split(" ")[0]}
                </strong>
                <span>
                  {r.seatCode ? `${r.seatCode} 号座位` : "未签到记录"}
                </span>
              </div>
              <div className="record-body">
                <div className="record-title">
                  <h3>{r.roomName || "预约记录"}</h3>
                  <Status value={r.status || "NO_SHOW"} />
                </div>
                <p>{slotLabel(r.startAt, r.endAt)}</p>
                <span className="muted">
                  <EnvironmentOutlined /> {r.buildingName || "校园自习空间"}
                </span>
                {tab === "bookings" && (
                  <p className="booking-next-step">{reservationNextStep(r)}</p>
                )}
              </div>
              <div className="record-actions">
                <Button onClick={() => setId(r.reservationId || r.id)}>
                  查看详情
                </Button>
                {tab === "bookings" && controls(r)}
              </div>
            </article>
          ))}
        </div>
        {!data.loading && !data.error && items(data.data).length === 0 && (
          <div className="panel">
            <EmptyState
              title={
                tab === "violations"
                  ? "没有未签到记录"
                  : status || range
                  ? "没有符合筛选条件的预约"
                  : "还没有预约记录"
              }
              description={
                tab === "violations"
                  ? "按时签到，一起让空间更好用。"
                  : status || range
                  ? "试试其他状态或日期，也可以清除筛选查看全部记录。"
                  : "点击“查找座位”，选择时间和座位后即可预约。"
              }
              action={
                tab === "bookings" && (status || range) ? (
                  <Button
                    onClick={() => {
                      setStatus(undefined);
                      setRange(undefined);
                      setPage(1);
                    }}
                  >
                    清除筛选
                  </Button>
                ) : tab === "bookings" && maySearch ? (
                  <Button type="primary" onClick={() => history.push("/seats")}>
                    查找座位
                  </Button>
                ) : undefined
              }
            />
          </div>
        )}
      </LoadingBlock>
      <Pagination
        className="pagination"
        current={page}
        pageSize={12}
        total={data.data?.total || 0}
        onChange={setPage}
        hideOnSinglePage
        showSizeChanger={false}
      />
      <Drawer
        title="预约详情"
        open={!!id}
        onClose={() => setId(undefined)}
        size={520}
      >
        <Failure error={detail.error} onRetry={detail.reload} />
        <LoadingBlock loading={detail.loading}>
          {detail.data && (
            <>
              <Status value={detail.data.status} />
              <h2>
                {detail.data.roomName} · {detail.data.seatCode} 号
              </h2>
              <Descriptions
                column={1}
                items={[
                  {
                    key: "id",
                    label: "预约编号",
                    children: <Identifier value={detail.data.id} />,
                  },
                  {
                    key: "time",
                    label: "使用时段",
                    children: slotLabel(detail.data.startAt, detail.data.endAt),
                  },
                  {
                    key: "in",
                    label: "签到时间",
                    children: detail.data.checkedInAt
                      ? dateTime(detail.data.checkedInAt)
                      : "尚未签到",
                  },
                  {
                    key: "reason",
                    label: "取消原因",
                    children:
                      (
                        {
                          USER_CANCEL: "本人取消",
                          MAINTENANCE: "教室维修",
                          ADMIN_CANCEL: "管理取消",
                        } as any
                      )[detail.data.cancelReason] || "—",
                  },
                ]}
              />
              <Alert
                className="mb-20"
                type={detail.data.status === "NO_SHOW" ? "warning" : "info"}
                showIcon
                title={
                  detail.data.status === "RESERVED"
                    ? "下一步：到教室后签到"
                    : "本次预约说明"
                }
                description={reservationNextStep(detail.data)}
              />
              {controls(detail.data)}
            </>
          )}
        </LoadingBlock>
      </Drawer>
      {action.dialog}
    </>
  );
}
