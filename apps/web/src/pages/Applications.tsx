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
  const [id, setId] = useState<string | undefined>(
    new URLSearchParams(window.location.search).get("id") || undefined
  );
  const [creating, setCreating] = useState(false);
  const [time, setTime] = useState<{ startAt: string; endAt: string }>();
  const data = useData<Page<RecordData>>(
    `/room-applications?${query({ view, page, size: 12 })}`
  );
  const detail = useData<RecordData>(id ? `/room-applications/${id}` : null);
  const available = useData<Page<Room>>(
    time ? `/availability/rooms?${query({ ...time, size: 100 })}` : null
  );
  const action = useAction(() => {
    data.reload();
    detail.reload();
    setCreating(false);
  });
  const create = (room: Room) =>
    action.open({
      title: `申请 ${room.name}`,
      path: "/room-applications",
      body: { roomId: room.id, ...time },
      fields: [
        { name: "purposeTitle", label: "用途标题", required: true, max: 100 },
        {
          name: "purposeDescription",
          label: "用途说明",
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
          description="提交后需通过院系、空间两级审批。审批通过前不占用教室。"
        />
      ),
      success: "申请已提交，等待第一级审批",
    });
  const decide = (r: RecordData, approve: boolean) =>
    action.open({
      title: approve ? "通过当前审批步骤？" : "驳回申请",
      path: `/room-applications/${r.id}/decisions`,
      body: {
        expectedVersion: r.version,
        decision: approve ? "APPROVE" : "REJECT",
      },
      fields: [
        {
          name: "comment",
          label: approve ? "审批意见（选填）" : "驳回理由",
          type: "textarea",
          required: !approve,
          max: 500,
        },
      ],
      description: approve ? (
        <p>
          {r.currentStep === 2
            ? "通过后将锁定整间教室的申请时段。提交时会再次检查资源冲突。"
            : "本次通过后将进入空间审批，不会提前占用教室。"}
        </p>
      ) : undefined,
      danger: !approve,
    });
  const buttons = (r: RecordData) => (
    <Space wrap>
      {r.allowedActions?.includes("approve") && (
        <Button type="primary" onClick={() => decide(r, true)}>
          通过审批
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
  return (
    <>
      <PageHeading
        eyebrow="ROOM APPLICATIONS"
        title="教室申请"
        description="为讨论、活动与集体学习，申请一间完整的教室。"
        action={
          can(user, "room_application:create") ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setCreating(true)}
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
          setPage(1);
        }}
        items={tabs}
        tabBarExtraContent={
          <Button icon={<ReloadOutlined />} onClick={data.reload}>
            刷新
          </Button>
        }
      />
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
              <span>第 {record.currentStep} 级审批</span>
              <Button onClick={() => setId(record.id)}>
                {view === "pending-review" ? "查看并处理" : "查看详情"}
              </Button>
            </div>
          </article>
        ))}
        {!data.loading && !data.error && items(data.data).length === 0 && (
          <div className="panel">
            <EmptyState title="暂无申请" description="新的申请将出现在这里。" />
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
              <EmptyState
                title="暂无申请"
                description={
                  view === "pending-review"
                    ? "当前没有需要你处理的申请。"
                    : "获得申请资格后，即可提交整教室使用申请。"
                }
              />
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
              title: "状态",
              dataIndex: "status",
              render: (v) => <Status value={v} />,
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
          title="院系审批 → 空间审批"
          description="选择时段，查看当前可以申请的教室。资源以最终审批时的检查为准。"
        />
        <TimeFilter
          onSearch={setTime}
          loading={available.loading}
          maxHours={user.bookingLimits?.applicationMaxHours || 8}
        />
        <Failure error={available.error} onRetry={available.reload} />
        <div className="available-rooms">
          {items<Room>(available.data).map((r) => (
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
                    label: "审批截止",
                    children: dateTime(detail.data.expiresAt),
                  },
                ]}
              />
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
                        {s.stepNo === 1 ? "院系审批" : "空间审批"}
                      </strong>{" "}
                      <Status value={s.status} />
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
