import { useEffect, useRef, useState } from "react";
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
import { PlusOutlined, ReloadOutlined } from "@ant-design/icons";
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
  Identifier,
} from "../components/business/common";
import type { RecordData, Page, Room } from "../services/types";
export default function Repairs() {
  const { user } = useSession();
  const [view, setView] = useState("mine");
  const [page, setPage] = useState(1);
  const [id, setId] = useState<string | undefined>(
    new URLSearchParams(window.location.search).get("id") || undefined
  );
  const [pollEnded, setPollEnded] = useState(false);
  const [pollGeneration, setPollGeneration] = useState(0);
  const started = useRef(Date.now());
  const data = useData<Page<RecordData>>(
    `/repairs?${query({ view, page, size: 12 })}`
  );
  const detail = useData<RecordData>(id ? `/repairs/${id}` : null);
  const rooms = useData<Page<Room>>("/rooms?size=100");
  const r = detail.data;
  const workers = useData<any>(
    r?.allowedActions?.includes("assign")
      ? `/repairs/assignable-workers?roomId=${r.roomId}&size=100`
      : null
  );
  const action = useAction((result) => {
    data.reload();
    detail.reload();
    if (result?.id) {
      setId(result.id);
      started.current = Date.now();
      setPollEnded(false);
      // A retry can retain the same pending status after the previous timer expired.
      setPollGeneration((generation) => generation + 1);
    }
  });
  useEffect(() => {
    started.current = Date.now();
    setPollEnded(false);
  }, [id]);
  useEffect(() => {
    if (!["BLOCK_PENDING", "RELEASE_PENDING"].includes(r?.status || "")) return;
    const timer = setInterval(() => {
      if (Date.now() - started.current >= 60000) {
        clearInterval(timer);
        setPollEnded(true);
      } else detail.reload();
    }, 2000);
    return () => clearInterval(timer);
  }, [id, r?.status, detail.reload, pollGeneration]);
  const roomName = (roomId: string) =>
    items<Room>(rooms.data).find((room) => room.id === roomId)?.name || "教室";
  const roomsPending = rooms.loading || (!rooms.data && !rooms.error);
  const roomsReady = !roomsPending && !rooms.error && items(rooms.data).length > 0;
  const create = () => {
    if (!roomsReady) return;
    action.open({
      title: "上报设施故障",
      path: "/repairs",
      fields: [
        {
          name: "roomId",
          label: "故障教室",
          type: "select",
          required: true,
          options: items<Room>(rooms.data).map((r) => ({
            value: r.id,
            label: `${r.name} · ${r.buildingName || ""}`,
          })),
        },
        {
          name: "title",
          label: "故障标题",
          required: true,
          max: 100,
          help: "例如：靠窗座位插座无法供电",
        },
        {
          name: "description",
          label: "问题描述",
          type: "textarea",
          required: true,
          max: 2000,
          help: "请说明具体位置、故障现象和影响，方便维修人员定位。",
        },
      ],
      success: "报修已提交，我们会尽快安排处理",
    });
  };
  const act = (name: string) => {
    if (!r) return;
    const spec: any = {
      path: `/repairs/${r.id}/${name}`,
      body: { expectedVersion: r.version },
    };
    if (name === "assign")
      Object.assign(spec, {
        title: "分配维修人员",
        fields: [
          {
            name: "assignedTo",
            label: "维修人员",
            type: "select",
            required: true,
            options: items(workers.data).map((w) => ({
              value: w.userId,
              label: w.displayName,
            })),
          },
        ],
        description: <Failure error={workers.error} onRetry={workers.reload} />,
      });
    if (name === "start")
      Object.assign(spec, {
        title: "开始维修",
        description: (
          <p>
            系统将先同步下架整间教室的座位。收到资源确认后，工单才会进入“维修中”。
          </p>
        ),
        success: "已提交下架请求，请等待资源同步",
      });
    if (name === "complete")
      Object.assign(spec, {
        title: "提交完工说明",
        fields: [
          {
            name: "workNote",
            label: "维修处理情况",
            type: "textarea",
            required: true,
            max: 2000,
          },
        ],
        description: (
          <p>提交后等待报修人或物业管理员验收，验收完成前教室仍保持下架。</p>
        ),
      });
    if (name === "accept" || name === "reject")
      Object.assign(spec, {
        title: name === "accept" ? "验收通过" : "验收不通过",
        path: `/repairs/${r.id}/acceptance`,
        body: {
          ...spec.body,
          decision: name === "accept" ? "ACCEPT" : "REJECT",
        },
        fields: [
          {
            name: "comment",
            label:
              name === "accept" ? "验收意见（选填）" : "请说明仍存在的问题",
            type: "textarea",
            required: name === "reject",
            max: 500,
          },
        ],
        success:
          name === "accept"
            ? "验收已通过，正在同步恢复资源"
            : "已退回维修，教室继续保持下架",
        danger: name === "reject",
      });
    if (name === "cancel")
      Object.assign(spec, {
        title: "取消工单",
        fields: [
          {
            name: "reason",
            label: "取消原因",
            type: "textarea",
            required: true,
            max: 500,
          },
        ],
        danger: true,
      });
    if (name === "rate")
      Object.assign(spec, {
        title: "评价这次维修",
        path: `/repairs/${r.id}/rating`,
        fields: [
          {
            name: "score",
            label: "满意度",
            type: "select",
            required: true,
            options: [1, 2, 3, 4, 5].map((n) => ({
              value: n,
              label: `${"★".repeat(n)} · ${n} 分`,
            })),
          },
          {
            name: "comment",
            label: "补充评价（选填）",
            type: "textarea",
            max: 500,
          },
        ],
        description: <p>评价只能提交一次，提交后不能修改。</p>,
      });
    if (name === "retry-sync")
      Object.assign(spec, {
        title: "重试资源同步",
        description: <p>将重试原有命令，不会新建工单或额外封禁。</p>,
        success: "已安排重试，请稍后刷新查看",
      });
    action.open(spec);
  };
  const labels: Record<string, string> = {
    assign: "派单 / 重新派单",
    start: "开始维修",
    complete: "提交完工",
    accept: "验收通过",
    reject: "验收不通过",
    cancel: "取消工单",
    rate: "评价维修",
    "retry-sync": "重试同步",
  };
  const tabs = [
    { key: "mine", label: "我的报修" },
    ...(can(user, "repair:read_assigned")
      ? [{ key: "assigned", label: "我的维修任务" }]
      : []),
    ...(canAny(user, ["repair:read_scope", "repair:assign"])
      ? [{ key: "manage", label: "工单管理" }]
      : []),
  ];
  return (
    <>
      <PageHeading
        eyebrow="CARE FOR OUR SPACE"
        title="设施报修"
        description="让问题被看见，让每一处空间都保持好状态。"
        action={
          can(user, "repair:create") ? (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={create}
              loading={roomsPending}
              disabled={!roomsReady}
              aria-label="上报故障"
              aria-busy={roomsPending}
            >
              上报故障
            </Button>
          ) : undefined
        }
      />
      {can(user, "repair:create") && (
        <>
          <Failure error={rooms.error} onRetry={rooms.reload} />
          {!roomsPending && !rooms.error && rooms.data && !items(rooms.data).length && (
            <Alert
              className="mb-20"
              type="info"
              showIcon
              title="暂无可报修教室"
              description="请联系教室管理员完善教室信息。"
              action={<Button size="small" onClick={rooms.reload}>重新加载教室</Button>}
            />
          )}
        </>
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
              <h3>{record.title}</h3>
              <Status value={record.status} />
            </div>
            <p>{record.roomName || roomName(record.roomId)}</p>
            <div className="mobile-card-footer">
              <span>{dateTime(record.submittedAt)}</span>
              <Button onClick={() => setId(record.id)}>查看 / 处理</Button>
            </div>
          </article>
        ))}
        {!data.loading && !data.error && items(data.data).length === 0 && (
          <div className="panel">
            <EmptyState
              title="暂无工单"
              description="发现设施问题后，可上报并追踪处理进展。"
            />
          </div>
        )}
      </div>
      <div className="panel table-panel desktop-table">
        <Table
          rowKey="id"
          loading={data.loading}
          dataSource={items(data.data)}
          pagination={false}
          scroll={{ x: 650 }}
          locale={{
            emptyText: (
              <EmptyState
                title="暂无工单"
                description="如果发现设施问题，可以在这里上报并追踪处理进展。"
              />
            ),
          }}
          columns={[
            {
              title: "故障问题",
              dataIndex: "title",
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
            {
              title: "教室",
              render: (_, r) => r.roomName || roomName(r.roomId),
            },
            {
              title: "状态",
              dataIndex: "status",
              render: (v) => <Status value={v} />,
            },
            { title: "上报时间", dataIndex: "submittedAt", render: dateTime },
            {
              title: "操作",
              render: (_, r) => (
                <Button onClick={() => setId(r.id)}>查看 / 处理</Button>
              ),
            },
          ]}
        />
      </div>
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
        title="工单详情"
        open={!!id}
        onClose={() => setId(undefined)}
        size={600}
        extra={
          <Button icon={<ReloadOutlined />} onClick={detail.reload}>
            刷新
          </Button>
        }
      >
        <Failure error={detail.error} onRetry={detail.reload} />
        <LoadingBlock loading={detail.loading}>
          {r && (
            <>
              <Status value={r.status} />
              <h2>{r.title}</h2>
              <Descriptions
                column={1}
                items={[
                  {
                    key: "id",
                    label: "工单编号",
                    children: <Identifier value={r.id} />,
                  },
                  {
                    key: "room",
                    label: "教室",
                    children: r.roomName || roomName(r.roomId),
                  },
                  {
                    key: "reported",
                    label: "上报时间",
                    children: dateTime(r.submittedAt),
                  },
                  ...(["CLOSED", "CANCELLED"].includes(r.status || "")
                    ? []
                    : [
                        {
                          key: "due",
                          label: "处理截止",
                          children: dateTime(
                            r.acceptDueAt || r.workDueAt || r.assignDueAt
                          ),
                        },
                      ]),
                  {
                    key: "worker",
                    label: "维修人员",
                    children:
                      r.assignedToName ||
                      (r.assignedTo ? (
                        <Identifier value={r.assignedTo} />
                      ) : (
                        "等待派单"
                      )),
                  },
                ]}
              />
              <p className="description-text">{r.description}</p>
              {["BLOCK_PENDING", "RELEASE_PENDING"].includes(
                r.status || ""
              ) && (
                <Alert
                  className="mb-20"
                  type="warning"
                  showIcon
                  title={
                    r.status === "BLOCK_PENDING"
                      ? "正在同步下架，尚未开始维修"
                      : "正在同步恢复，工单尚未关闭"
                  }
                  description={
                    <>
                      {r.sync?.needsAttention && (
                        <p>
                          <strong>需要人工关注：</strong>
                          资源同步多次失败，请联系工作人员检查。系统仍会继续重试。
                        </p>
                      )}
                      {pollEnded
                        ? "同步仍未确认，可手动刷新。"
                        : "正在自动刷新进展。"}
                      <br />
                      尝试次数：{r.sync?.attempts || 0} · 最近尝试：
                      {dateTime(r.sync?.lastAttemptAt)}
                      {r.sync?.nextAttemptAt && (
                        <p>下次重试计划：{dateTime(r.sync.nextAttemptAt)}</p>
                      )}
                      {r.sync?.lastErrorCode && (
                        <p>最近错误：{r.sync.lastErrorCode}</p>
                      )}
                    </>
                  }
                />
              )}
              {r.status === "AWAITING_ACCEPTANCE" && (
                <Alert
                  className="mb-20"
                  type="info"
                  showIcon
                  title="等待验收，座位仍保持下架"
                  description={r.lastWorkNote}
                />
              )}
              {r.rating && (
                <div className="rating-summary">
                  <strong>{"★".repeat(r.rating.score)}</strong>
                  <p>{r.rating.comment || "已提交评价"}</p>
                </div>
              )}
              <Space wrap className="mb-20">
                {(r.allowedActions || []).map((name) => (
                  <Button
                    key={name}
                    type={
                      ["start", "complete", "accept", "assign"].includes(name)
                        ? "primary"
                        : "default"
                    }
                    danger={["cancel", "reject"].includes(name)}
                    disabled={
                      name === "assign" && (workers.loading || !!workers.error)
                    }
                    onClick={() => act(name)}
                  >
                    {labels[name] || name}
                  </Button>
                ))}
              </Space>
              <h3>处理记录</h3>
              <Timeline
                items={(r.history || []).map((h: any) => ({
                  content: (
                    <div>
                      <Status value={h.toStatus} />
                      <span className="muted">{dateTime(h.createdAt)}</span>
                      {h.reason && <p>{h.reason}</p>}
                    </div>
                  ),
                }))}
              />
            </>
          )}
        </LoadingBlock>
      </Drawer>
      {action.dialog}
    </>
  );
}
