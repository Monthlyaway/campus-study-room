import { useEffect, useMemo, useState } from "react";
import {
  Button,
  DatePicker,
  Select,
  Switch,
  Tag,
  Pagination,
  Alert,
} from "antd";
import {
  ArrowRightOutlined,
  CheckOutlined,
  EnvironmentOutlined,
  ThunderboltOutlined,
  SearchOutlined,
  ClockCircleOutlined,
  BookOutlined,
} from "@ant-design/icons";
import { history } from "umi";
import { can } from "../access";
import { items, query } from "../services/api";
import { useSession } from "../services/session";
import {
  PageHeading,
  useData,
  useAction,
  LoadingBlock,
  Failure,
  EmptyState,
  dayjs,
  shanghai,
  slotLabel,
} from "../components/business/common";
import type { Seat, Room, Page } from "../services/types";
export function TimeFilter({
  onSearch,
  onDraftChange,
  searchLabel = "查询可预约座位",
  loading,
  maxHours = 4,
}: {
  onSearch: (value: { startAt: string; endAt: string }) => void;
  onDraftChange?: () => void;
  searchLabel?: string;
  loading?: boolean;
  maxHours?: number;
}) {
  const [date, setDate] = useState(() =>
    shanghai().add(1, "day").startOf("day")
  );
  const [start, setStart] = useState(9);
  const [duration, setDuration] = useState(Math.min(2, maxHours));
  useEffect(() => {
    setDuration((d) => Math.min(d, maxHours));
  }, [maxHours]);
  const startAt = dayjs.tz(
    `${date.format("YYYY-MM-DD")} ${String(start).padStart(2, "0")}:00`,
    "Asia/Shanghai"
  );
  const endAt = startAt.add(duration, "hour");
  const timeError = !startAt.isAfter(shanghai())
    ? "开始时间已过，请选择之后的整点。"
    : endAt.isAfter(shanghai().add(14, "day"))
    ? "结束时间须在未来 14 天内，请提前开始时间或缩短时长。"
    : undefined;
  return (
    <>
      <div className="time-filter">
        <label>
          使用日期
          <DatePicker
            aria-label="使用日期"
            value={date}
            onChange={(v) => {
              if (v) {
                setDate(v);
                onDraftChange?.();
              }
            }}
            allowClear={false}
            disabledDate={(v) =>
              v.format("YYYY-MM-DD") < shanghai().format("YYYY-MM-DD") ||
              v.format("YYYY-MM-DD") >
                shanghai().add(14, "day").format("YYYY-MM-DD")
            }
            format="MM月DD日 ddd"
          />
        </label>
        <label>
          开始时间
          <Select
            aria-label="开始时间"
            value={start}
            onChange={(value) => {
              setStart(value);
              onDraftChange?.();
            }}
            options={Array.from({ length: 24 }, (_, i) => ({
              label: `${String(i).padStart(2, "0")}:00`,
              value: i,
            }))}
          />
        </label>
        <label>
          使用时长
          <Select
            aria-label="使用时长"
            value={duration}
            onChange={(value) => {
              setDuration(value);
              onDraftChange?.();
            }}
            options={Array.from({ length: maxHours }, (_, i) => ({
              label: `${i + 1} 小时`,
              value: i + 1,
            }))}
          />
        </label>
        <Button
          type="primary"
          icon={<SearchOutlined aria-hidden="true" />}
          aria-label={searchLabel}
          loading={loading}
          disabled={!!timeError}
          onClick={() =>
            onSearch({ startAt: startAt.format(), endAt: endAt.format() })
          }
        >
          {searchLabel}
        </Button>
      </div>
      <p className="time-summary" aria-live="polite">
        {timeError ||
          `使用时段：${slotLabel(
            startAt.format(),
            endAt.format()
          )}（中国标准时间）`}
      </p>
    </>
  );
}
export default function Seats() {
  const { user } = useSession();
  const mayReserve = can(user, "reservation:create");
  const [time, setTime] = useState<{ startAt: string; endAt: string }>();
  const [power, setPower] = useState(false);
  const repeatParams = new URLSearchParams(window.location.search);
  const repeatSeatId = repeatParams.get("seatId");
  const [room, setRoom] = useState<string | undefined>(
    repeatParams.get("roomId") || undefined
  );
  const [campus, setCampus] = useState<string>();
  const [building, setBuilding] = useState<string>();
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Seat>();
  const rooms = useData<Page<Room>>("/rooms?size=100");
  const campuses = useData<any>("/campuses");
  const buildings = useData<any>("/buildings");
  const campusBuildingIds = new Set(
    items(buildings.data)
      .filter((b) => !campus || b.campusId === campus)
      .map((b) => b.id)
  );
  const result = useData<Page<Seat>>(
    time
      ? `/availability/seats?${query({
          ...time,
          hasPower: power || undefined,
          roomId: room,
          campusId: campus,
          buildingId: building,
          page,
          size: 60,
        })}`
      : null
  );
  const groups = useMemo(() => {
    const groups: Record<string, Seat[]> = {};
    items<Seat>(result.data).forEach((s) => (groups[s.roomId] ||= []).push(s));
    return Object.values(groups);
  }, [result.data]);
  const action = useAction(() => {
    setSelected(undefined);
    result.reload();
    history.push("/reservations");
  });
  const pick = (seat: Seat) => {
    setSelected(seat);
    action.open({
      title: "确认预约",
      confirmText: "确认预约这个座位",
      path: "/reservations",
      body: { seatId: seat.seatId || seat.id, ...time },
      success: "预约成功，请按时到教室签到",
      description: (
        <div className="booking-confirm">
          <BookOutlined />
          <h3>
            {seat.roomName} · {seat.code} 号座位
          </h3>
          <p>{slotLabel(time?.startAt, time?.endAt)}</p>
          <span>
            {seat.campusName} / {seat.buildingName}
          </span>
          <Alert
            type="info"
            showIcon
            title="开始后 15 分钟内签到"
            description="到教室后输入当日签到码。逾时未签到，系统将释放座位并记录一次未签到。"
          />
        </div>
      ),
    });
  };
  return (
    <>
      <PageHeading
        eyebrow="FIND YOUR FOCUS"
        title="查找座位"
        description="先选使用时间，再点座位号预约。到教室后，在“我的座位预约”输入当天签到码。"
      />
      {!mayReserve && (
        <Alert
          className="mb-20"
          type="info"
          showIcon
          title="你可以查询座位，暂未开通预约资格"
          description="需要预约时，请联系系统管理员为账号开通个人预约权限。"
        />
      )}
      {repeatSeatId && (
        <Alert
          className="mb-20"
          type="info"
          showIcon
          title="已带入上次使用的教室与座位"
          description="请选择新的使用时间并查询。历史座位会被突出显示，提交时将重新检查可用性。"
        />
      )}
      <ol className="task-steps" aria-label="预约步骤">
        <li>
          <b>1</b> 选择使用时段
        </li>
        <li>
          <b>2</b> 点击空闲座位预约
        </li>
        <li>
          <b>3</b> 开始后 15 分钟内签到
        </li>
      </ol>
      <section className="panel search-panel">
        <TimeFilter
          maxHours={user.bookingLimits?.reservationMaxHours || 4}
          onDraftChange={() => {
            setTime(undefined);
            setSelected(undefined);
          }}
          onSearch={(v) => {
            setTime(v);
            setPage(1);
            setSelected(undefined);
            result.reload();
          }}
          loading={result.loading}
        />
        <div className="filter-footer">
          <div className="place-filters">
            <Select
              aria-label="校区"
              placeholder="全部校区"
              allowClear
              value={campus}
              onChange={(v) => {
                setCampus(v);
                setBuilding(undefined);
                setRoom(undefined);
                setPage(1);
              }}
              loading={campuses.loading}
              showSearch
              optionFilterProp="label"
              options={items(campuses.data).map((c) => ({
                value: c.id,
                label: c.name,
              }))}
            />
            <Select
              aria-label="楼宇"
              placeholder="全部楼宇"
              allowClear
              value={building}
              onChange={(v) => {
                setBuilding(v);
                setRoom(undefined);
                setPage(1);
              }}
              loading={buildings.loading}
              showSearch
              optionFilterProp="label"
              options={items(buildings.data)
                .filter((b) => !campus || b.campusId === campus)
                .map((b) => ({ value: b.id, label: b.name }))}
            />
            <Select
              aria-label="教室"
              placeholder="全部教室"
              allowClear
              value={room}
              onChange={(v) => {
                setRoom(v);
                setPage(1);
              }}
              loading={rooms.loading}
              showSearch
              optionFilterProp="label"
              options={[
                ...items<Room>(rooms.data)
                  .filter(
                    (r) =>
                      (!building || r.buildingId === building) &&
                      (!campus || campusBuildingIds.has(r.buildingId))
                  )
                  .map((r) => ({ value: r.id, label: r.name })),
                ...(room && !items<Room>(rooms.data).some((r) => r.id === room)
                  ? [
                      {
                        value: room,
                        label: rooms.loading
                          ? "正在加载上次的教室…"
                          : "上次的教室（当前不可选择）",
                        disabled: true,
                      },
                    ]
                  : []),
              ]}
            />
          </div>
          <label className="power-filter">
            <ThunderboltOutlined /> 仅带插座{" "}
            <Switch
              size="small"
              aria-label="仅显示带插座的座位"
              checked={power}
              onChange={(v) => {
                setPower(v);
                setPage(1);
              }}
            />
          </label>
        </div>
        <p className="muted">
          可选学校共享教室和你所属院系的教室；每次最多{" "}
          {user.bookingLimits?.reservationMaxHours || 4} 小时，结束时间须在未来
          14 天内。
        </p>
        <Failure
          error={campuses.error || buildings.error || rooms.error}
          onRetry={() => {
            campuses.reload();
            buildings.reload();
            rooms.reload();
          }}
        />
      </section>
      <div className="section-line">
        <h2>
          {time ? "选择一个座位" : "先选择使用时间"}{" "}
          <span>{result.data ? `${result.data.total} 个座位` : ""}</span>
        </h2>
        <span className="muted">
          <ClockCircleOutlined /> 中国标准时间
        </span>
      </div>
      {time && (
        <p className="muted">
          本次查询：{slotLabel(time.startAt, time.endAt)}
          。点击座位后确认预约，查询本身不会保留座位。
        </p>
      )}
      <Failure error={result.error} onRetry={result.reload} />
      <LoadingBlock loading={result.loading}>
        {!time ? (
          <div className="search-empty panel">
            <div className="empty-symbol">
              <SearchOutlined />
            </div>
            <h3>你想什么时候来？</h3>
            <p>选择日期、开始时间与时长，查看整个时段内可用的座位。</p>
            <div className="quiet-rules">
              <span>
                <CheckOutlined /> 整点预约
              </span>
              <span>
                <CheckOutlined /> 结束时间在未来 14 天内
              </span>
              <span>
                <CheckOutlined /> 开始后 15 分钟内签到
              </span>
            </div>
          </div>
        ) : groups.length === 0 && !result.loading && !result.error ? (
          <div className="panel">
            <EmptyState
              title="这个时段暂时没有空位"
              description="试试其他时间，或取消插座和教室筛选。"
            />
          </div>
        ) : (
          <div className="room-cards">
            {groups.map((seats) => (
              <section className="panel room-card" key={seats[0].roomId}>
                <div className="room-card-header">
                  <div className="room-icon">
                    <BookOutlined />
                  </div>
                  <div>
                    <h3>{seats[0].roomName || "自习教室"}</h3>
                    <p>
                      <EnvironmentOutlined /> {seats[0].campusName} ·{" "}
                      {seats[0].buildingName}
                    </p>
                  </div>
                  <Tag color="success">本页 {seats.length} 个空位</Tag>
                </div>
                <p className="muted">按座位编号排列，请按现场座位号入座。</p>
                <div className="seat-grid">
                  {seats.map((s) => (
                    <button
                      key={s.seatId || s.id}
                      className={`seat ${
                        (selected &&
                          (selected.seatId || selected.id) ===
                            (s.seatId || s.id)) ||
                        repeatSeatId === (s.seatId || s.id)
                          ? "selected"
                          : ""
                      }`}
                      aria-label={`预约 ${s.roomName} ${s.code} 号座位${
                        s.hasPower ? "，带插座" : ""
                      }`}
                      disabled={!mayReserve}
                      onClick={() => pick(s)}
                    >
                      <span className="seat-back" />
                      <strong>{s.code}</strong>
                      {s.hasPower && (
                        <ThunderboltOutlined className="seat-power" />
                      )}
                    </button>
                  ))}
                </div>
                <div className="room-legend">
                  <span>
                    <i />
                    可预约
                  </span>
                  <span>
                    <ThunderboltOutlined /> 带插座
                  </span>
                  <small>
                    点击座位确认预约 <ArrowRightOutlined />
                  </small>
                </div>
              </section>
            ))}
          </div>
        )}
      </LoadingBlock>
      {result.data && result.data.total > 60 && (
        <Pagination
          current={page}
          pageSize={60}
          total={result.data.total}
          onChange={setPage}
          showSizeChanger={false}
        />
      )}
      {action.dialog}
    </>
  );
}
