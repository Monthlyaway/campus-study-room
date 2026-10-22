import { useState } from "react";
import { Button, Form, Input } from "antd";
import {
  ArrowRightOutlined,
  BookOutlined,
  LockOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { clearCsrf, csrf } from "../services/api";
import { postApiV1AuthLogin } from "../services/generated/core/api/auth";
import { Failure } from "../components/business/common";
export default function Login({ onLogin }: { onLogin: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const login = async (v: any) => {
    setBusy(true);
    setError(undefined);
    try {
      await postApiV1AuthLogin(v);
      clearCsrf();
      await csrf();
      onLogin();
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="login-page">
      <section className="login-art">
        <div className="brand light">
          <span className="brand-mark">
            <BookOutlined />
          </span>
          <div>
            栖学<span>CAMPUS SPACE</span>
          </div>
        </div>
        <div className="login-statement">
          <span className="eyebrow">留一方空间，给专注的你</span>
          <h1>
            坐下来。
            <br />
            让想法，
            <br />
            <em>慢慢生长。</em>
          </h1>
          <p>
            从一张自习座位开始，
            <br />
            让每一次校园空间使用都井然有序。
          </p>
        </div>
        <div className="room-illustration" aria-hidden="true">
          <div className="window-frame">
            <i />
            <i />
            <i />
          </div>
          <div className="desk">
            <i />
            <b />
          </div>
          <div className="chair" />
          <div className="plant">
            <i />
            <i />
            <i />
            <b />
          </div>
          <span className="illustration-label">YOUR SPACE TO FOCUS</span>
        </div>
        <div className="login-art-footer">
          校园资源服务平台 <span>01 / FOCUS</span>
        </div>
      </section>
      <section className="login-form-area">
        <div className="login-form">
          <div className="eyebrow">WELCOME BACK</div>
          <h2>欢迎回到栖学</h2>
          <p className="muted">登录校园账号，开启今天的专注时光。</p>
          <Failure error={error} />
          <Form
            layout="vertical"
            onFinish={login}
            disabled={busy}
            requiredMark={false}
          >
            <Form.Item
              name="username"
              label="账号"
              rules={[{ required: true, message: "请输入账号" }]}
            >
              <Input
                prefix={<UserOutlined />}
                size="large"
                placeholder="请输入校园账号"
                autoComplete="username"
                maxLength={64}
              />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: "请输入密码" }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                size="large"
                placeholder="请输入密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <Button
              type="primary"
              size="large"
              htmlType="submit"
              block
              loading={busy}
            >
              登录 <ArrowRightOutlined />
            </Button>
          </Form>
          <div className="login-help">
            账号由学校统一开通。
            <br />
            如需开通账号或重置密码，请联系系统管理员。
          </div>
        </div>
        <footer>
          校园空间，有序共享。<span>Asia/Shanghai · 中国标准时间</span>
        </footer>
      </section>
    </div>
  );
}
