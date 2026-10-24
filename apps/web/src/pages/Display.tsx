import { useEffect, useState } from "react";
import { Alert, Button, Space } from "antd";
import { BookOutlined, ReloadOutlined } from "@ant-design/icons";
import { Failure, useData, LoadingBlock, shanghai } from "../components/business/common";
export default function Display({ roomId }: { roomId: string }) {
  const code = useData<any>(`/rooms/${roomId}/display-code`);
  const room = useData<any>(`/rooms/${roomId}`);
  const [today, setToday] = useState(() => shanghai().format("YYYY-MM-DD"));
  useEffect(() => {
    const now = shanghai();
    const timer = setTimeout(() => {
      setToday(shanghai().format("YYYY-MM-DD"));
      code.reload();
    }, now.add(1, "day").startOf("day").diff(now));
    return () => clearTimeout(timer);
  }, [today, code.reload]);
  useEffect(() => {
    if (code.loading) return;
    const timer = setTimeout(code.reload, 60000);
    return () => clearTimeout(timer);
  }, [code.loading, code.reload]);
  return (
    <div className="display-page">
      <BookOutlined aria-hidden="true" />
      <div className="eyebrow">栖学 · 教室签到</div>
      <h1>{room.data?.name || "教室签到屏幕"}</h1>
      <Failure error={room.error} onRetry={room.reload} />
      <Failure error={code.error} onRetry={code.reload} />
      <LoadingBlock loading={code.loading}>
        {code.data && !code.error && code.data.localDate !== today && <Alert
          type="warning" showIcon title="正在获取今天的签到码"
          description="屏幕上的日期已过期，请刷新后再签到。"
        />}
        {code.data && !code.error && code.data.localDate === today && (
          <>
            <p>{code.data.localDate} · 中国标准时间</p>
            <div className="display-code" aria-label={`今日签到码 ${code.data.code}`}>{code.data.code}</div>
            <h2>请在预约开始后 15 分钟内完成签到</h2>
            <p>打开「我的座位预约」→ 选择这次预约 → 点击「立即签到」→ 输入上方签到码。</p>
            <p>仅限本教室当天的预约使用，每天 00:00 更换。页面每分钟自动刷新。</p>
          </>
        )}
      </LoadingBlock>
      <Space wrap>
        <Button aria-label="刷新签到码" icon={<ReloadOutlined aria-hidden="true" />} onClick={code.reload}>刷新签到码</Button>
        <Button href="/">返回工作台</Button>
      </Space>
    </div>
  );
}
