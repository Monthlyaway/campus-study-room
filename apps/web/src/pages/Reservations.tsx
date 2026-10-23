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
} from "../components/business/common";
import type { Page, RecordData } from "../services/types";
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
      status,
      startFrom: range?.[0]
        ? range[0].format("YYYY-MM-DD") + "T00:00:00+08:00"
        : undefined,
      startTo: range?.[1]
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
              path: `/reservations/${r.id}/check-in`,
              body: { expectedVersion: r.version },
              fields: [
                {
                  name: "roomCode",
                  label: "教室当日签到码",
                  required: true,
                  max: 32,
                  help: "请查看教室屏幕。签到窗口为开始后 15 分钟内。",
                },
              ],
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
              path: `/reservations/${r.id}/cancel`,
              body: { expectedVersion: r.version },
              danger: true,
              description: <p>取消后会释放这个座位，其他同学可以预约。</p>,
            })
          }
        >
          取消预约
        </Button>
      )}
      {maySearch && (
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
        title="我的预约"
        description="每一段专注都有安排，每一次使用都有记录。"
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
            onChange={(v) => {
              setRange(v);
              setPage(1);
            }}
          />
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
            <article className="panel reservation-card" key={r.id}>
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
                  <EnvironmentOutlined /> {r.buildingName || "校园自习空间"} ·{" "}
                  <Identifier value={r.id} />
                </span>
                {r.cancelReason === "MAINTENANCE" && (
                  <p className="maintenance-note">
                    因教室维修取消，本次不计未签到。
                  </p>
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
              title={tab === "violations" ? "没有未签到记录" : "还没有预约记录"}
              description={
                tab === "violations"
                  ? "按时签到，一起让空间更好用。"
                  : "挑一个喜欢的位置，开始下一段专注时光。"
              }
              action={
                tab === "bookings" && maySearch ? (
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
                    children: dateTime(detail.data.checkedInAt),
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
              {detail.data.status === "RESERVED" && (
                <Alert
                  className="mb-20"
                  type="info"
                  title="签到窗口：预约开始后 15 分钟内"
                  description="在教室查看当日编码，输入后完成签到。"
                />
              )}
              {controls(detail.data)}
            </>
          )}
        </LoadingBlock>
      </Drawer>
      {action.dialog}
    </>
  );
}
