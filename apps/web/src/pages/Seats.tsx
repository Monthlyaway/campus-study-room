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
  loading,
  maxHours = 4,
}: {
  onSearch: (value: { startAt: string; endAt: string }) => void;
  loading?: boolean;
  maxHours?: number;
}) {
  const [date, setDate] = useState(shanghai().add(1, "day").startOf("day"));
  const [start, setStart] = useState(9);
  const [duration, setDuration] = useState(Math.min(2, maxHours));
  useEffect(() => {
    setDuration((d) => Math.min(d, maxHours));
  }, [maxHours]);
  return (
    <div className="time-filter">
      <label>
        使用日期
        <DatePicker
          value={date}
          onChange={(v) => {
            if (v) setDate(v);
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
          value={start}
          onChange={setStart}
          options={Array.from({ length: 24 }, (_, i) => ({
            label: `${String(i).padStart(2, "0")}:00`,
            value: i,
          }))}
        />
      </label>
      <label>
        专注时长
        <Select
          value={duration}
          onChange={setDuration}
          options={Array.from({ length: maxHours }, (_, i) => ({
            label: `${i + 1} 小时`,
            value: i + 1,
          }))}
        />
      </label>
      <Button
        type="primary"
        icon={<SearchOutlined />}
        loading={loading}
        onClick={() => {
          const s = dayjs.tz(
            `${date.format("YYYY-MM-DD")} ${String(start).padStart(2, "0")}:00`,
            "Asia/Shanghai"
          );
          onSearch({
            startAt: s.format(),
            endAt: s.add(duration, "hour").format(),
          });
        }}
      >
        查找空间
      </Button>
    </div>
  );
}
export default function Seats() {
  const { user } = useSession();
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
        title="找一个好位置"
        description="选择一段时间，把空间留给专注。"
      />
      {repeatSeatId && (
        <Alert
          className="mb-20"
          type="info"
          showIcon
          title="已带入上次使用的教室与座位"
          description="请选择新的使用时间并查询。历史座位会被突出显示，提交时将重新检查可用性。"
        />
      )}
      <section className="focus-banner">
        <div>
          <Tag>自习空间</Tag>
          <h2>今天，也向目标靠近一点。</h2>
          <p>按时抵达，安静学习。你的下一段专注时光，从这里开始。</p>
        </div>
        <div className="focus-lineart" aria-hidden="true">
          <BookOutlined />
          <span>
            MAKE ROOM
            <br />
            FOR IDEAS.
          </span>
        </div>
      </section>
      <section className="panel search-panel">
        <TimeFilter
          maxHours={user.bookingLimits?.reservationMaxHours || 4}
          onSearch={(v) => {
            setTime(v);
            setPage(1);
            setSelected(undefined);
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
              options={items<Room>(rooms.data)
                .filter((r) => !building || r.buildingId === building)
                .map((r) => ({ value: r.id, label: r.name }))}
            />
          </div>
          <label className="power-filter">
            <ThunderboltOutlined /> 仅带插座{" "}
            <Switch
              size="small"
              checked={power}
              onChange={(v) => {
                setPower(v);
                setPage(1);
              }}
            />
          </label>
        </div>
      </section>
      <div className="section-line">
        <h2>
          {time ? "可预约空间" : "为下一次专注做准备"}{" "}
          <span>{result.data ? `${result.data.total} 个座位` : ""}</span>
        </h2>
        <span className="muted">
          <ClockCircleOutlined /> 中国标准时间
        </span>
      </div>
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
                <CheckOutlined /> 最多提前 14 天
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
                  <Tag color="success">{seats.length} 席可约</Tag>
                </div>
                <div className="room-front">前方 / 讲台</div>
                <div className="seat-grid">
                  {seats.map((s) => (
                    <button
                      key={s.seatId || s.id}
                      className={`seat ${
                        selected?.seatId === s.seatId ||
                        repeatSeatId === (s.seatId || s.id)
                          ? "selected"
                          : ""
                      }`}
                      aria-label={`预约 ${s.roomName} ${s.code} 号座位${
                        s.hasPower ? "，带插座" : ""
                      }`}
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
