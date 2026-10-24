import { useState } from "react";
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Pagination,
  Space,
  Table,
  Tabs,
  Timeline,
  Tag,
  Select,
} from "antd";
import { PlusOutlined, ReloadOutlined, TeamOutlined } from "@ant-design/icons";
import { can, canAny } from "../access";
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
} from "../components/business/common";
import { TimeFilter } from "./Seats";
import type { RecordData, Page, Room } from "../services/types";
const approvalStage = (record: RecordData) =>
  record.status === "PENDING"
    ? record.currentStep === 2
      ? "等待教室审批"
      : "等待院系审批"
    : "审批已结束";
function applicationNextStep(record: RecordData) {
  if (record.status === "PENDING")
    return `${approvalStage(record)}，教室尚未保留。请在 ${dateTime(
      record.expiresAt
    )} 前完成审批。`;
  if (record.status === "APPROVED")
    return "两级审批已通过，该时段的整间教室已保留。请按批准的时段使用教室。";
  if (record.status === "REJECTED")
    return "申请未通过，请查看下方驳回理由。需要调整时，重新提交一份申请。";
  if (record.status === "EXPIRED")
    return "未在截止前完成审批，本次未保留教室。请重新选择时段申请。";
  if (record.cancelReason === "MAINTENANCE")
    return "教室维修导致申请取消，本次时段不再保留。请重新选择其他教室。";
  if (record.status === "CANCELLED")
    return "申请已取消，本次时段不再保留。需要使用时请重新申请。";
  if (record.status === "COMPLETED")
    return "本次教室使用已结束，审批过程仍可在下方查看。";
  return "";
}
export default function Applications() {
  const { user } = useSession();
  const [view, setView] = useState(
    canAny(user, [
      "room_application:review_dept",
      "room_application:review_space",
    ])
      ? "pending-review"
      : "mine"
  );
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState<string>();
  const [availablePage, setAvailablePage] = useState(1);
  const [id, setId] = useState<string | undefined>(
    new URLSearchParams(window.location.search).get("id") || undefined
  );
  const [creating, setCreating] = useState(false);
  const [time, setTime] = useState<{ startAt: string; endAt: string }>();
  const data = useData<Page<RecordData>>(
    `/room-applications?${query({ view, page, status, size: 12 })}`
  );
  const detail = useData<RecordData>(id ? `/room-applications/${id}` : null);
  const available = useData<Page<Room>>(
    time
      ? `/availability/rooms?${query({
          ...time,
          page: availablePage,
          size: 12,
        })}`
      : null
  );
  const action = useAction((result) => {
    if (creating && result?.id) {
      setId(result.id);
      setView("mine");
      setPage(1);
      setStatus(undefined);
    }
    data.reload();
    detail.reload();
    setCreating(false);
  });
  const create = (room: Room) =>
    action.open({
      title: `申请 ${room.name}`,
      confirmText: "提交教室申请",
      path: "/room-applications",
      body: { roomId: room.id, ...time },
      fields: [
        {
          name: "purposeTitle",
          label: "用途标题",
          placeholder: "例如：课程小组讨论…",
          required: true,
          max: 100,
        },
        {
          name: "purposeDescription",
          label: "用途说明",
          placeholder: "说明活动内容、参加人数和使用需求…",
          type: "textarea",
          required: true,
          max: 2000,
        },
      ],
      description: (
        <Alert
          className="mb-20"
          type="info"
          showIcon
          title={slotLabel(time?.startAt, time?.endAt)}
          description="先由申请人所属院系审批，再由负责这间教室的审批员确认。两级都通过后才会保留教室。"
        />
      ),
      success: "申请已提交，等待院系审批",
    });
  const decide = (r: RecordData, approve: boolean) =>
    action.open({
      title: approve
        ? `通过${r.currentStep === 2 ? "教室" : "院系"}审批？`
        : "驳回申请",
      confirmText: approve
        ? `确认通过${r.currentStep === 2 ? "教室" : "院系"}审批`
        : "确认驳回申请",
      path: `/room-applications/${r.id}/decisions`,
      body: {
        expectedVersion: r.version,
        decision: approve ? "APPROVE" : "REJECT",
      },
      fields: [
        {
          name: "comment",
          label: approve ? "审批意见（选填）" : "驳回理由",
          placeholder: approve
            ? "可补充使用要求…"
            : "说明不能通过的原因，方便申请人调整…",
          type: "textarea",
          required: !approve,
          max: 500,
        },
      ],
      description: approve ? (
        <p>
          {r.purposeTitle} · {r.roomName}
          <br />
          {slotLabel(r.startAt, r.endAt)}
          <br />
          {r.currentStep === 2
            ? "这是最后一步。通过后会为申请人保留该时段的整间教室；如果已被占用，本次审批不会成功。"
            : "通过后转交教室审批员继续确认，教室此时尚未保留。"}
        </p>
      ) : undefined,
      success: approve
        ? r.currentStep === 2
          ? "教室审批已通过，该时段已保留"
          : "院系审批已通过，等待教室审批"
        : "申请已驳回，申请人可查看理由",
      danger: !approve,
    });
  const buttons = (r: RecordData) => (
    <Space wrap>
      {r.allowedActions?.includes("approve") && (
        <Button type="primary" onClick={() => decide(r, true)}>
          通过{r.currentStep === 2 ? "教室" : "院系"}审批
        </Button>
      )}
      {r.allowedActions?.includes("reject") && (
        <Button danger onClick={() => decide(r, false)}>
          驳回
        </Button>
      )}
      {r.allowedActions?.includes("cancel") && (
        <Button
          danger
          onClick={() =>
            action.open({
              title: "撤销申请",
              confirmText: "确认撤销申请",
              success: "申请已撤销",
              path: `/room-applications/${r.id}/cancel`,
              body: { expectedVersion: r.version },
              fields: [
                {
                  name: "reason",
                  label: "撤销原因",
                  type: "textarea",
                  required: r.applicantId !== user.userId,
                  max: 500,
                },
              ],
              danger: true,
              description: (
                <p>
                  已批准的申请撤销后会释放该时段。需要修改内容时，请撤销后重新申请。
                </p>
              ),
            })
          }
        >
          撤销申请
        </Button>
      )}
    </Space>
  );
  const tabs = [
    { key: "mine", label: "我的申请" },
    ...(canAny(user, [
      "room_application:review_dept",
      "room_application:review_space",
    ])
      ? [
          { key: "pending-review", label: "待我审批" },
          { key: "reviewed", label: "我已处理" },
        ]
      : []),
  ];
  const isReviewer = canAny(user, [
    "room_application:review_dept",
    "room_application:review_space",
  ]);
  const emptyTitle =
    view === "pending-review"
      ? "目前没有待你审批的申请"
      : view === "reviewed"
      ? "你还没有处理过申请"
      : status
      ? "没有符合状态的申请"
      : "你还没有提交教室申请";
  const emptyDescription =
    view === "pending-review"
      ? "这里只显示当前轮到你处理、且属于你负责院系或教室的申请。"
      : view === "reviewed"
      ? "你通过或驳回的申请会保留在这里，可随时查看后续结果。"
      : status
      ? "试试其他状态，或选择全部状态。"
      : can(user, "room_application:create")
      ? "点击“申请教室”，先选时段和教室，再填写用途。"
      : "已有申请会保留在这里；如需新申请，请联系管理员开通资格。";
  return (
    <>
      <PageHeading
        eyebrow="ROOM APPLICATIONS"
        title={isReviewer ? "教室申请审批" : "教室申请"}
        description={
          isReviewer
            ? "在“待我审批”处理当前步骤；在“我已处理”回看决定与后续结果。申请须由两位不同审批员依次确认。"
            : "需要整间教室开展活动或集体学习时，在这里申请。院系和教室两级审批都通过后，才会保留时段。"
        }
        action={
          can(user, "room_application:create") ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setTime(undefined);
                setAvailablePage(1);
                setCreating(true);
              }}
            >
              申请教室
            </Button>
          ) : undefined
        }
      />
      {!can(user, "room_application:create") &&
        !canAny(user, [
          "room_application:review_dept",
          "room_application:review_space",
        ]) && (
          <Alert
            type="info"
            showIcon
            className="mb-20"
            title="整教室申请需要单独授权"
            description="已有申请仍可查看。需要申请资格时，请联系学校管理员。"
          />
        )}
      <Tabs
        activeKey={view}
        onChange={(v) => {
          setView(v);
          setStatus(undefined);
          setPage(1);
        }}
        items={tabs}
        tabBarExtraContent={
          <Button icon={<ReloadOutlined />} onClick={data.reload}>
            刷新
          </Button>
        }
      />
      <div className="list-toolbar">
        <p className="muted">
          {view === "pending-review"
            ? "先核对用途、教室和使用时段，再通过或驳回；第一级通过后仍需第二级确认。"
            : view === "reviewed"
            ? "这里保留你的处理记录，列表状态反映申请的最新进展。"
            : "查看审批进度、使用时段与结果。修改已提交内容时，请先撤销再重新申请。"}
        </p>
        {view !== "pending-review" && (
          <Select
            aria-label="申请状态"
            placeholder="全部状态"
            allowClear
            value={status}
            onChange={(value) => {
              setStatus(value);
              setPage(1);
            }}
            options={[
              { value: "PENDING", label: "审批中" },
              { value: "APPROVED", label: "已批准" },
              { value: "REJECTED", label: "未通过" },
              { value: "EXPIRED", label: "已过期" },
              { value: "CANCELLED", label: "已取消" },
              { value: "COMPLETED", label: "已完成" },
            ]}
          />
        )}
      </div>
      <Failure error={data.error} onRetry={data.reload} />
      <div className="mobile-record-cards">
        {items(data.data).map((record) => (
          <article className="panel mobile-record-card" key={record.id}>
            <div className="record-title">
              <h3>{record.purposeTitle}</h3>
              <Status value={record.status} />
            </div>
            <p>
              {record.roomName}
              <br />
              {slotLabel(record.startAt, record.endAt)}
            </p>
            <div className="mobile-card-footer">
              <span>
                {approvalStage(record)}
                {record.status === "PENDING" && (
                  <>
                    <br />
                    截止：{dateTime(record.expiresAt)}
                  </>
                )}
              </span>
              <Button onClick={() => setId(record.id)}>
                {view === "pending-review" ? "查看并处理" : "查看详情"}
              </Button>
            </div>
          </article>
        ))}
        {!data.loading && !data.error && items(data.data).length === 0 && (
          <div className="panel">
            <EmptyState title={emptyTitle} description={emptyDescription} />
          </div>
        )}
      </div>
      <div className="panel table-panel desktop-table">
        <Table
          rowKey="id"
          loading={data.loading}
          dataSource={items(data.data)}
          pagination={false}
          scroll={{ x: 700 }}
          locale={{
            emptyText: (
              <EmptyState title={emptyTitle} description={emptyDescription} />
            ),
          }}
          columns={[
            {
              title: "申请用途",
              dataIndex: "purposeTitle",
              render: (v, r) => (
                <Button
                  type="link"
                  className="table-link"
                  onClick={() => setId(r.id)}
                >
                  {v}
                </Button>
              ),
            },
            { title: "教室", dataIndex: "roomName" },
            {
              title: "使用时段",
              render: (_, r) => slotLabel(r.startAt, r.endAt),
            },
            {
              title: "进度与截止",
              render: (_, r) => (
                <>
                  <Status value={r.status} />
                  {r.status === "PENDING" && (
                    <p className="muted">
                      {approvalStage(r)}
                      <br />
                      {dateTime(r.expiresAt)} 前处理
                    </p>
                  )}
                </>
              ),
            },
            {
              title: "操作",
              render: (_, r) => (
                <Button onClick={() => setId(r.id)}>
                  {view === "pending-review" ? "查看并处理" : "查看详情"}
                </Button>
              ),
            },
          ]}
        />
      </div>
      <Pagination
        className="pagination"
        pageSize={12}
        current={page}
        total={data.data?.total || 0}
        onChange={setPage}
        hideOnSinglePage
        showSizeChanger={false}
      />
      <Drawer
        title="申请教室"
        open={creating}
        onClose={() => setCreating(false)}
        size={720}
      >
        <Alert
          className="mb-20"
          showIcon
          type="info"
          title="申请步骤：选时段 → 选教室 → 填写用途"
          description="提交后先由院系审批，再由教室审批员确认。两级都通过后才会保留教室。"
        />
        <TimeFilter
          searchLabel="查询可申请教室"
          onDraftChange={() => setTime(undefined)}
          onSearch={(value) => {
            setTime(value);
            setAvailablePage(1);
            available.reload();
          }}
          loading={available.loading}
          maxHours={user.bookingLimits?.applicationMaxHours || 8}
        />
        <Failure error={available.error} onRetry={available.reload} />
        {!time && (
          <EmptyState
            title="先选好使用时段"
            description="点击“查询可申请教室”，选择一间教室后填写用途。"
          />
        )}
        {time && (
          <p className="muted">
            查询时段：{slotLabel(time.startAt, time.endAt)}
            。下一步，选择一间教室。
          </p>
        )}
        <LoadingBlock loading={available.loading}>
          <div className="available-rooms">
            {time &&
              items<Room>(available.data).map((r) => (
                <div className="available-room" key={r.id}>
                  <span className="room-icon">
                    <TeamOutlined />
                  </span>
                  <div>
                    <h3>{r.name}</h3>
                    <p>
                      {r.campusName} · {r.buildingName}
                    </p>
                  </div>
                  <Button type="primary" onClick={() => create(r)}>
                    选择教室
                  </Button>
                </div>
              ))}
            {time &&
              !available.loading &&
              items(available.data).length === 0 &&
              !available.error && (
                <EmptyState
                  title="该时段没有可申请的教室"
                  description="请更换时段再试。"
                />
              )}
          </div>
        </LoadingBlock>
        {time && (
          <Pagination
            current={availablePage}
            pageSize={12}
            total={available.data?.total || 0}
            onChange={setAvailablePage}
            hideOnSinglePage
            showSizeChanger={false}
          />
        )}
      </Drawer>
      <Drawer
        title="申请详情"
        open={!!id}
        onClose={() => setId(undefined)}
        size={580}
      >
        <Failure error={detail.error} onRetry={detail.reload} />
        <LoadingBlock loading={detail.loading}>
          {detail.data && (
            <>
              <Status value={detail.data.status} />
              <h2>{detail.data.purposeTitle}</h2>
              <Alert
                className="mb-20"
                showIcon
                type={
                  detail.data.status === "REJECTED" ||
                  detail.data.status === "EXPIRED"
                    ? "warning"
                    : "info"
                }
                title={
                  detail.data.status === "PENDING"
                    ? approvalStage(detail.data)
                    : "申请结果"
                }
                description={applicationNextStep(detail.data)}
              />
              <Descriptions
                column={1}
                items={[
                  {
                    key: "id",
                    label: "申请编号",
                    children: <Identifier value={detail.data.id} />,
                  },
                  {
                    key: "room",
                    label: "教室",
                    children: detail.data.roomName,
                  },
                  {
                    key: "time",
                    label: "使用时段",
                    children: slotLabel(detail.data.startAt, detail.data.endAt),
                  },
                  {
                    key: "expires",
                    label: "最晚审批时间",
                    children: dateTime(detail.data.expiresAt),
                  },
                ]}
              />
              <h3>申请用途</h3>
              <p className="description-text">
                {detail.data.purposeDescription}
              </p>
              <h3>审批进度</h3>
              <Timeline
                items={(detail.data.steps || []).map((s: any) => ({
                  color:
                    s.status === "APPROVED"
                      ? "green"
                      : s.status === "REJECTED"
                      ? "red"
                      : "gray",
                  content: (
                    <div>
                      <strong>
                        {s.stepNo === 1 ? "1. 院系审批" : "2. 教室审批"}
                      </strong>{" "}
                      <Tag
                        color={
                          s.status === "APPROVED"
                            ? "success"
                            : s.status === "REJECTED"
                            ? "error"
                            : "default"
                        }
                      >
                        {s.status === "PENDING"
                          ? detail.data?.status === "PENDING"
                            ? "等待处理"
                            : "未完成"
                          : s.status === "WAITING"
                          ? detail.data?.status === "PENDING"
                            ? "等待院系通过"
                            : "未进行"
                          : s.status === "APPROVED"
                          ? "已通过"
                          : s.status === "REJECTED"
                          ? "已驳回"
                          : s.status}
                      </Tag>
                      {(detail.data?.actions || [])
                        .filter((a: any) => a.stepNo === s.stepNo)
                        .map((a: any, i: number) => (
                          <p key={i}>
                            {a.actorName || a.displayName || "审批人"} ·{" "}
                            {dateTime(a.occurredAt)}
                            <br />
                            {a.comment ||
                              (a.decision === "APPROVE"
                                ? "同意申请"
                                : "驳回申请")}
                          </p>
                        ))}
                    </div>
                  ),
                }))}
              />
              {detail.data.cancelReason === "MAINTENANCE" && (
                <Alert
                  className="mb-20"
                  type="warning"
                  title="因教室维修，申请已取消。"
                />
              )}
              {buttons(detail.data)}
            </>
          )}
        </LoadingBlock>
      </Drawer>
      {action.dialog}
    </>
  );
}
