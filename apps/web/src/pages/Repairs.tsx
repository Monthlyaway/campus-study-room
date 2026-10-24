import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Pagination,
  Select,
  Space,
  Table,
  Tabs,
  Timeline,
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
  statusLabels,
} from "../components/business/common";
import type { RecordData, Page, Room } from "../services/types";
import "./repairs.css";

const repairStates = ["SUBMITTED", "ASSIGNED", "BLOCK_PENDING", "IN_REPAIR", "AWAITING_ACCEPTANCE", "RELEASE_PENDING", "CLOSED", "CANCELLED"];
const nextSteps: Record<string, { title: string; description: string }> = {
  SUBMITTED: { title: "等待调度员安排维修人员", description: "报修已收到。调度员安排人员后，你可以在这里查看维修进展。" },
  ASSIGNED: { title: "等待维修人员开始处理", description: "维修人员已安排。开始维修后，整间教室将暂停使用。" },
  BLOCK_PENDING: { title: "正在暂停教室使用，请等待确认", description: "系统正在处理教室内受影响的预约。确认完成后，维修人员才能继续提交完工。" },
  IN_REPAIR: { title: "正在维修，教室暂停使用", description: "维修人员修复故障后提交处理说明，再由报修人或物业管理员验收。" },
  AWAITING_ACCEPTANCE: { title: "已提交完工，等待现场验收", description: "请核实故障是否解决。验收通过前，教室继续暂停使用；仍有问题可以退回维修。" },
  RELEASE_PENDING: { title: "验收已通过，正在确认教室使用状态", description: "本次维修尚未完成收尾。系统确认后才显示“已完成”；其他维修或预约仍可能影响教室使用。" },
  CLOSED: { title: "本次维修已完成", description: "本次维修造成的暂停使用已解除。教室是否可用还取决于其他维修、预约和开放设置。报修人可以评价本次服务。" },
  CANCELLED: { title: "这次报修已取消", description: "工作人员不会继续处理此工单。如仍有故障，请重新上报。" },
};
const historyLabels: Record<string, string> = {
  "请求封禁教室，等待资源服务确认": "已申请暂停教室使用，等待系统确认",
  "资源服务确认BLOCK": "系统已确认教室暂停使用，可以开始维修",
  "资源服务确认RELEASE": "本次维修造成的暂停使用已解除",
  "手动重试原同步命令": "已请求系统重新确认教室使用状态",
};
const deadline = (record: RecordData) => {
  if (record.status === "SUBMITTED") return { label: "派单截止", at: record.assignDueAt };
  if (["ASSIGNED", "BLOCK_PENDING", "IN_REPAIR"].includes(record.status || "")) return { label: "完工截止", at: record.workDueAt };
  if (record.status === "AWAITING_ACCEPTANCE") return { label: "验收截止", at: record.acceptDueAt };
  return undefined;
};
const taskLabel = (record: RecordData) => {
  const actions = record.allowedActions || [];
  if (actions.includes("assign")) return record.status === "SUBMITTED" ? "安排维修" : "查看 / 改派";
  if (actions.includes("start")) return "去开始维修";
  if (actions.includes("complete")) return "去提交完工";
  if (actions.includes("accept")) return "去验收";
  if (actions.includes("rate")) return "评价维修";
  return "查看进展";
};
const updateLocation = (values: Record<string, string | undefined>) => {
  const url = new URL(window.location.href);
  Object.entries(values).forEach(([key, value]) => value ? url.searchParams.set(key, value) : url.searchParams.delete(key));
  window.history.replaceState(window.history.state, "", `${url.pathname}${url.search}`);
};
export default function Repairs() {
  const { user } = useSession();
  const [view, setView] = useState(() => {
    const requested = new URLSearchParams(window.location.search).get("view");
    const manage = canAny(user, ["repair:read_scope", "repair:assign"]);
    const assigned = can(user, "repair:read_assigned");
    if (requested === "mine" || (requested === "manage" && manage) || (requested === "assigned" && assigned)) return requested;
    return manage ? "manage" : assigned ? "assigned" : "mine";
  });
  const [status, setStatus] = useState<string | undefined>(() => {
    const requested = new URLSearchParams(window.location.search).get("status");
    return requested && repairStates.includes(requested) ? requested : undefined;
  });
  const [page, setPage] = useState(1);
  const [id, setId] = useState<string | undefined>(
    new URLSearchParams(window.location.search).get("id") || undefined
  );
  const openDetail = (nextId?: string) => {
    setId(nextId);
    updateLocation({ id: nextId });
  };
  const [pollEnded, setPollEnded] = useState(false);
  const [pollGeneration, setPollGeneration] = useState(0);
  const started = useRef(Date.now());
  const data = useData<Page<RecordData>>(
    `/repairs?${query({ view, status, page, size: 12 })}`
  );
  const detail = useData<RecordData>(id ? `/repairs/${id}` : null);
  const rooms = useData<Page<Room>>(can(user, "repair:create") ? "/rooms?size=100" : null);
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
      openDetail(result.id);
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
    const remaining = 60000 - (Date.now() - started.current);
    if (remaining <= 0) {
      setPollEnded(true);
      return;
    }
    // Wait for the current detail request before scheduling another. Slow requests
    // must not accumulate every two seconds while the services are recovering.
    if (detail.loading) return;
    const timer = setTimeout(() => {
      if (Date.now() - started.current >= 60000) setPollEnded(true);
      else detail.reload();
    }, Math.min(2000, remaining));
    return () => clearTimeout(timer);
  }, [id, r?.status, detail.loading, detail.reload, pollGeneration]);
  const roomName = (roomId: string) =>
    items<Room>(rooms.data).find((room) => room.id === roomId)?.name || "教室名称暂不可用";
  const roomsPending = rooms.loading || (!rooms.data && !rooms.error);
  const roomsReady = !roomsPending && !rooms.error && items(rooms.data).length > 0;
  const create = () => {
    if (!roomsReady) return;
    action.open({
      title: "上报设施故障",
      description: <p>先选故障所在教室，再描述具体位置和问题。提交后可在“我的报修”查看进展、验收和评价。</p>,
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
          label: "哪里出了问题",
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
    if (name === "assign" && (workers.loading || workers.error || !items(workers.data).length)) return;
    if (name === "assign")
      Object.assign(spec, {
        title: r.status === "SUBMITTED" ? "分配维修人员" : "更换维修人员",
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
        description: <p>{r.status === "SUBMITTED" ? "选择负责本次故障的维修人员。对方会在“我的维修任务”收到这张工单。" : "更换后，原维修人员将不再处理这张工单。完工截止时间不会延长。"}</p>,
        success: "维修人员已安排，可在工单中查看负责人和进展",
      });
    if (name === "start")
      Object.assign(spec, {
        title: "开始维修",
        description: (
          <p>
            开始后，整间教室将暂停使用，受影响的座位预约和已批准教室申请会被取消或中断。系统确认暂停使用后，工单才会进入“维修中”。
          </p>
        ),
        success: "正在暂停教室使用，请等待系统确认",
      });
    if (name === "complete")
      Object.assign(spec, {
        title: "提交完工说明",
        success: "完工说明已提交，请等待报修人或物业管理员验收",
        fields: [
          {
            name: "workNote",
            label: "维修处理情况",
            help: "说明修复了什么、处理结果，以及验收时需要检查的地方。",
            type: "textarea",
            required: true,
            max: 2000,
          },
        ],
        description: (
          <p>提交后会通知报修人验收；物业管理员也可验收。验收通过并完成系统确认前，教室仍暂停使用。</p>
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
            ? "验收已通过，正在确认教室使用状态"
            : "已退回维修，教室继续暂停使用",
        description: <p>{name === "accept" ? "请确认已现场检查，故障已经解决。通过后系统会解除本次维修造成的暂停使用；其他维修的影响仍会保留。" : "请写清仍有问题的位置和现象，便于维修人员继续处理。"}</p>,
        danger: name === "reject",
      });
    if (name === "cancel")
      Object.assign(spec, {
        title: "取消工单",
        description: <p>取消后工作人员将不再处理这张工单，无法恢复。故障已经解决或重复上报时再取消。</p>,
        success: "工单已取消",
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
        title: "重新确认教室使用状态",
        description: <p>让系统立即再确认一次暂停或恢复使用的进展。无需重复创建工单。</p>,
        success: "已安排重试，请稍后刷新查看",
      });
    action.open(spec);
  };
  const labels: Record<string, string> = {
    assign: r?.status === "SUBMITTED" ? "分配维修人员" : "更换维修人员",
    start: "开始维修",
    complete: "提交完工",
    accept: "验收通过",
    reject: "验收不通过",
    cancel: "取消工单",
    rate: "评价维修",
    "retry-sync": "重新确认使用状态",
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
  const viewHelp = view === "manage"
    ? "查看你负责教室的工单。调度员安排维修人员，物业管理员跟进进度并验收。"
    : view === "assigned"
    ? "这里只显示分配给你的任务。开始维修 → 填写完工说明 → 等待独立验收。"
    : "查看你上报的问题。收到完工通知后验收；维修完成后可以评价。";
  const emptyHelp = status
    ? "没有符合这个状态的工单，试试切换到“全部状态”。"
    : view === "manage"
    ? "你负责的教室目前没有工单。有故障上报后，会在这里出现。"
    : view === "assigned"
    ? "还没有分配给你的维修任务。调度员派单后，会在这里出现。"
    : can(user, "repair:create")
    ? "还没有你上报的故障。发现设施问题时，点击“上报故障”。"
    : "这里会保留你上报过的问题。需要上报新故障时，请联系系统管理员开通报修资格。";
  return (
    <>
      <PageHeading
        eyebrow="CARE FOR OUR SPACE"
        title={canAny(user, ["repair:read_scope", "repair:assign", "repair:read_assigned"]) ? "维修工作台" : "设施报修"}
        description="上报问题、跟进维修、确认修复。每一步都能查看进展。"
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
          updateLocation({ view: v });
        }}
        items={tabs}
        tabBarExtraContent={
          <Button icon={<ReloadOutlined />} onClick={data.reload}>
            刷新
          </Button>
        }
      />
      <div className="repair-toolbar">
        <p className="muted">{viewHelp}</p>
        <Select
          aria-label="工单状态"
          placeholder="全部状态"
          allowClear
          value={status}
          options={repairStates.map((value) => ({ value, label: statusLabels[value] }))}
          onChange={(value) => { setStatus(value); setPage(1); updateLocation({ status: value }); }}
        />
      </div>
      <Failure error={data.error} onRetry={data.reload} />
      <div className="mobile-record-cards" aria-busy={data.loading}>
        {data.loading && <p role="status">正在加载工单…</p>}
        {items(data.data).map((record) => (
          <article className="panel mobile-record-card" key={record.id}>
            <div className="record-title">
              <h3>{record.title}</h3>
              <Status value={record.status} />
            </div>
            <p>{record.roomName || roomName(record.roomId)}</p>
            <p className="muted">{record.assignedToName ? `维修人员：${record.assignedToName}` : record.assignedTo ? "维修人员已安排" : "等待安排维修人员"}</p>
            {deadline(record)?.at && <p className="repair-deadline">{deadline(record)?.label}：{dateTime(deadline(record)?.at)}</p>}
            <div className="mobile-card-footer">
              <span>{dateTime(record.submittedAt)}</span>
              <Button onClick={() => openDetail(record.id)}>{taskLabel(record)}</Button>
            </div>
          </article>
        ))}
        {!data.loading && !data.error && items(data.data).length === 0 && (
          <div className="panel">
            <EmptyState
              title="暂无工单"
              description={emptyHelp}
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
                description={emptyHelp}
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
                  onClick={() => openDetail(r.id)}
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
            { title: "维修人员", render: (_, record) => record.assignedToName || (record.assignedTo ? "已安排" : "等待安排") },
            { title: "当前期限", render: (_, record) => {
              const due = deadline(record);
              return due?.at ? <span>{due.label}<br />{dateTime(due.at)}</span> : ["BLOCK_PENDING", "RELEASE_PENDING"].includes(record.status || "") ? "等待系统确认" : "—";
            } },
            {
              title: "操作",
              render: (_, r) => (
                <Button onClick={() => openDetail(r.id)}>{taskLabel(r)}</Button>
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
        onClose={() => openDetail(undefined)}
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
              {nextSteps[r.status || ""] && <Alert
                className="mb-20"
                showIcon
                type={r.status === "CLOSED" ? "success" : "info"}
                title={nextSteps[r.status || ""].title}
                description={nextSteps[r.status || ""].description}
              />}
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
                  ...(deadline(r)?.at ? [{
                    key: "due", label: deadline(r)?.label,
                    children: dateTime(deadline(r)?.at),
                  }] : []),
                  {
                    key: "worker",
                    label: "维修人员",
                    children:
                      r.assignedToName ||
                      (r.assignedTo ? "已安排（历史记录未保存姓名）" : "等待安排维修人员"),
                  },
                ]}
              />
              <h3>故障描述</h3>
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
                      ? "正在暂停教室使用，尚未开始维修"
                      : "正在恢复教室使用，尚未完成收尾"
                  }
                  description={
                    <>
                      {r.sync?.needsAttention && (
                        <p>
                          <strong>需要人工关注：</strong>
                          教室使用状态多次未能确认，请联系物业管理员检查。系统仍会继续重试。
                        </p>
                      )}
                      {pollEnded
                        ? "仍在等待系统确认，可点击右上角“刷新”查看进展。"
                        : "正在自动刷新进展。"}
                      <details>
                      <summary>查看系统确认进度</summary>
                      尝试次数：{r.sync?.attempts || 0} · 最近尝试：
                      {dateTime(r.sync?.lastAttemptAt)}
                      {r.sync?.nextAttemptAt && (
                        <p>下次重试计划：{dateTime(r.sync.nextAttemptAt)}</p>
                      )}
                      {r.sync?.lastErrorCode && (
                        <p>排查用错误代码：{r.sync.lastErrorCode}</p>
                      )}
                      </details>
                    </>
                  }
                />
              )}
              {r.status === "AWAITING_ACCEPTANCE" && (
                <Alert
                  className="mb-20"
                  type="info"
                  showIcon
                  title="维修人员提交的完工说明"
                  description={r.lastWorkNote}
                />
              )}
              {r.rating && (
                <div className="rating-summary">
                  <strong>{"★".repeat(r.rating.score)}</strong>
                  <p>{r.rating.comment || "已提交评价"}</p>
                </div>
              )}
              {r.allowedActions?.includes("assign") && <>
                <Failure error={workers.error} onRetry={workers.reload} />
                {!workers.loading && !workers.error && workers.data && !items(workers.data).length && <Alert
                  className="mb-20" showIcon type="warning" title="暂时没有可分配的维修人员"
                  description="请联系系统管理员开通维修人员账号和维修资格，再重新加载候选人员。"
                  action={<Button onClick={workers.reload}>重新加载</Button>}
                />}
              </>}
              <Space wrap className="mb-20">
                {(r.allowedActions || []).filter((name) => labels[name]).map((name) => (
                  <Button
                    key={name}
                    type={
                      ["start", "complete", "accept", "assign"].includes(name)
                        ? "primary"
                        : "default"
                    }
                    danger={["cancel", "reject"].includes(name)}
                    disabled={
                      name === "assign" && (workers.loading || !!workers.error || !items(workers.data).length)
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
                      {h.reason && <p>{historyLabels[h.reason] || h.reason}</p>}
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
