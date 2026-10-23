import { useEffect } from "react";
import { Button } from "antd";
import { BookOutlined, ReloadOutlined } from "@ant-design/icons";
import { Failure, useData, LoadingBlock } from "../components/business/common";
export default function Display({ roomId }: { roomId: string }) {
  const code = useData<any>(`/rooms/${roomId}/display-code`);
  const room = useData<any>(`/rooms/${roomId}`);
  useEffect(() => {
    const timer = setInterval(code.reload, 60000);
    return () => clearInterval(timer);
  }, [code.reload]);
  return (
    <div className="display-page">
      <BookOutlined />
      <div className="eyebrow">栖学 · 教室签到</div>
      <h1>{room.data?.name || "教室签到屏幕"}</h1>
      <Failure error={code.error} onRetry={code.reload} />
      <LoadingBlock loading={code.loading}>
        {code.data && (
          <>
            <p>{code.data.localDate} · 中国标准时间</p>
            <div className="display-code">{code.data.code}</div>
            <h2>请在预约开始后 15 分钟内完成签到</h2>
            <p>打开「我的预约」，输入上方当日编码。</p>
          </>
        )}
      </LoadingBlock>
      <Button icon={<ReloadOutlined />} onClick={code.reload}>
        刷新编码
      </Button>
    </div>
  );
}
