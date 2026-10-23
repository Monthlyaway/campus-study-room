import { lazy, Suspense, useEffect, useState } from "react";
import {
  App as AntApp,
  Avatar,
  Button,
  ConfigProvider,
  Drawer,
  Dropdown,
  Result,
  Spin,
  Tag,
} from "antd";
import zhCN from "antd/locale/zh_CN";
import {
  BookOutlined,
  CalendarOutlined,
  TeamOutlined,
  ToolOutlined,
  BellOutlined,
  UserOutlined,
  SafetyCertificateOutlined,
  ApartmentOutlined,
  SettingOutlined,
  BarChartOutlined,
  AuditOutlined,
  LogoutOutlined,
  MenuOutlined,
  ArrowRightOutlined,
  QuestionCircleOutlined,
} from "@ant-design/icons";
import { Link, history, useLocation } from "umi";
import { can, canAny } from "../access";
import { clearCsrf } from "../services/api";
import {
  getApiV1AuthMe,
  postApiV1AuthLogout,
} from "../services/generated/core/api/auth";
import { SessionContext } from "../services/session";
import type { User } from "../services/types";
import { useData, Failure, shanghai } from "../components/business/common";
import Login from "./Login";
import Seats from "./Seats";
import Reservations from "./Reservations";
import Applications from "./Applications";
import Repairs from "./Repairs";
import Notifications from "./Notifications";
import Display from "./Display";
import { Users, Roles, Spaces, Config, Statistics, Audit } from "./Admin";
import "../global.css";
const nav = [
  {
    path: "/seats",
    label: "查找座位",
    icon: <BookOutlined />,
    permission: "seat:search",
    group: "学习空间",
  },
  {
    path: "/reservations",
    label: "我的预约",
    icon: <CalendarOutlined />,
    group: "学习空间",
  },
  {
    path: "/applications",
    label: "教室申请",
    icon: <TeamOutlined />,
    group: "学习空间",
  },
  {
    path: "/repairs",
    label: "设施报修",
    icon: <ToolOutlined />,
    group: "学习空间",
  },
  {
    path: "/notifications",
    label: "通知中心",
    icon: <BellOutlined />,
    group: "学习空间",
  },
  {
    path: "/admin/statistics",
    label: "使用统计",
    icon: <BarChartOutlined />,
    permission: "statistics:read",
    group: "管理工作台",
  },
  {
    path: "/admin/spaces",
    label: "空间管理",
    icon: <ApartmentOutlined />,
    permission: "room:manage",
    group: "管理工作台",
  },
  {
    path: "/admin/users",
    label: "用户管理",
    icon: <UserOutlined />,
    permission: "user:manage",
    group: "管理工作台",
  },
  {
    path: "/admin/roles",
    label: "角色权限",
    icon: <SafetyCertificateOutlined />,
    permission: "role:manage",
    group: "管理工作台",
  },
  {
    path: "/admin/config",
    label: "系统参数",
    icon: <SettingOutlined />,
    permission: "config:manage",
    group: "管理工作台",
  },
  {
    path: "/admin/audit",
    label: "操作审计",
    icon: <AuditOutlined />,
    permission: "audit:read",
    group: "管理工作台",
  },
];
function Main() {
  const location = useLocation();
  const [user, setUser] = useState<User | null>();
  const [error, setError] = useState<Error>();
  const [mobile, setMobile] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const version = useData<any>("/version");
  const refresh = () => {
    getApiV1AuthMe({})
      .then((v) => {
        setUser(v as User);
        setError(undefined);
      })
      .catch((e) => {
        if (e.status === 401) setUser(null);
        else setError(e);
      });
  };
  useEffect(() => {
    refresh();
    const expired = () => setUser(null);
    const permission = () => refresh();
    window.addEventListener("session-expired", expired);
    window.addEventListener("permission-changed", permission);
    return () => {
      window.removeEventListener("session-expired", expired);
      window.removeEventListener("permission-changed", permission);
    };
  }, []);
  useEffect(() => {
    setMobile(false);
    window.scrollTo(0, 0);
  }, [location.pathname]);
  if (location.pathname === "/login" && !user)
    return (
      <Login
        onLogin={() => {
          refresh();
          history.replace("/");
        }}
      />
    );
  if (error && user === undefined)
    return (
      <Result
        status="warning"
        title="服务暂时不可用"
        subTitle="无法连接校园空间服务，请稍后再试。"
        extra={
          <Button type="primary" onClick={refresh}>
            重新连接
          </Button>
        }
      />
    );
  if (user === undefined)
    return (
      <div className="initial-loading">
        <Spin size="large" />
        <p>正在连接校园空间…</p>
      </div>
    );
  if (!user) return <Login onLogin={refresh} />;
  const homePath = can(user, "seat:search")
    ? "/seats"
    : can(user, "user:manage")
    ? "/admin/users"
    : can(user, "room:manage")
    ? "/admin/spaces"
    : can(user, "statistics:read")
    ? "/admin/statistics"
    : can(user, "room_application:review_dept") ||
      can(user, "room_application:review_space")
    ? "/applications"
    : can(user, "repair:work") || can(user, "repair:assign")
    ? "/repairs"
    : "/reservations";
  const path =
    location.pathname === "/" || location.pathname === "/login"
      ? homePath
      : location.pathname;
  const current = nav.find((n) => n.path === path);
  const visible = nav.filter((n) => !n.permission || can(user, n.permission));
  const defaultPath = homePath;
  const logout = async () => {
    try {
      await postApiV1AuthLogout({});
      clearCsrf();
      setUser(null);
      setError(undefined);
      history.replace("/login");
    } catch (e) {
      setError(e as Error);
    }
  };
  const sidebar = (
    <>
      <Link to={defaultPath} className="brand">
        <span className="brand-mark">
          <BookOutlined />
        </span>
        <div>
          栖学<span>CAMPUS SPACE</span>
        </div>
      </Link>
      <nav aria-label="主导航">
        {["学习空间", "管理工作台"].map((group) => {
          const links = visible.filter((n) => n.group === group);
          return links.length ? (
            <div className="nav-group" key={group}>
              <span className="nav-group-label">{group}</span>
              {links.map((n) => (
                <Link
                  key={n.path}
                  to={n.path}
                  className={`nav-item ${path === n.path ? "active" : ""}`}
                >
                  {n.icon}
                  <span>{n.label}</span>
                  {path === n.path && <i />}
                </Link>
              ))}
            </div>
          ) : null;
        })}
      </nav>
      <div className="sidebar-bottom">
        <div className="sidebar-note">
          <span className="tiny-dot" /> 空间有序，专注无扰。
          <p>
            每一次准时到达，
            <br />
            都是对共享空间的尊重。
          </p>
        </div>
        <button className="version-link" onClick={() => setVersionOpen(true)}>
          版本 {version.data?.releaseId || "查看"} <ArrowRightOutlined />
        </button>
      </div>
    </>
  );
  let page;
  switch (path) {
    case "/seats":
      page = <Seats />;
      break;
    case "/reservations":
      page = <Reservations />;
      break;
    case "/applications":
      page = <Applications />;
      break;
    case "/repairs":
      page = <Repairs />;
      break;
    case "/notifications":
      page = <Notifications />;
      break;
    case "/admin/users":
      page = <Users />;
      break;
    case "/admin/roles":
      page = <Roles />;
      break;
    case "/admin/spaces":
      page = <Spaces />;
      break;
    case "/admin/config":
      page = <Config />;
      break;
    case "/admin/statistics":
      page = <Statistics />;
      break;
    case "/admin/audit":
      page = <Audit />;
      break;
    default:
      page = path.startsWith("/display/") ? (
        <Display roomId={path.split("/")[2]} />
      ) : (
        <Result
          status="404"
          title="没有找到这个页面"
          extra={
            <Button onClick={() => history.push(defaultPath)}>回到首页</Button>
          }
        />
      );
  }
  if (current?.permission && !can(user, current.permission))
    page = (
      <Result
        status="403"
        title="暂无页面访问权限"
        subTitle="请联系管理员确认你的角色与数据范围。"
        extra={
          <Button onClick={() => history.push(defaultPath)}>
            回到我的空间
          </Button>
        }
      />
    );
  if (path.startsWith("/display/"))
    return (
      <SessionContext.Provider value={{ user, refresh }}>
        {can(user, "room:display_code") ? (
          page
        ) : (
          <Result status="403" title="无教室屏幕权限" />
        )}
      </SessionContext.Provider>
    );
  return (
    <SessionContext.Provider value={{ user, refresh }}>
      <div className="app-shell">
        <a className="skip-link" href="#main-content">
          跳转到主要内容
        </a>
        <aside className="desktop-sidebar">{sidebar}</aside>
        <Drawer
          placement="left"
          size={260}
          open={mobile}
          onClose={() => setMobile(false)}
          className="mobile-menu"
          styles={{ body: { padding: 0 } }}
        >
          {sidebar}
        </Drawer>
        <div className="main-shell">
          <header className="topbar">
            <div className="topbar-breadcrumb">
              <Button
                className="mobile-toggle"
                type="text"
                icon={<MenuOutlined />}
                aria-label="打开导航"
                onClick={() => setMobile(true)}
              />
              <span>校园空间</span>
              <i>/</i>
              <strong>{current?.label || "我的空间"}</strong>
            </div>
            <div className="topbar-right">
              <span className="today-label">
                {shanghai().format("M月D日 dddd")}
              </span>
              <Button
                type="text"
                icon={<BellOutlined />}
                aria-label="查看通知"
                onClick={() => history.push("/notifications")}
              />
              <Dropdown
                menu={{
                  items: [
                    {
                      key: "logout",
                      label: "退出登录",
                      icon: <LogoutOutlined />,
                      onClick: logout,
                    },
                  ],
                }}
              >
                <button className="profile-button">
                  <Avatar
                    size={32}
                    style={{ background: "#e8ede6", color: "#173e3c" }}
                  >
                    {user.displayName?.slice(-2)}
                  </Avatar>
                  <span>{user.displayName}</span>
                </button>
              </Dropdown>
            </div>
          </header>
          <main id="main-content" className="main-content">
            <Failure error={error} />
            {page}
          </main>
          <footer className="app-footer">
            <span>栖学 · 让校园空间更好用</span>
            <span>Asia/Shanghai · 中国标准时间</span>
          </footer>
        </div>
      </div>
      <Drawer
        title="当前版本"
        open={versionOpen}
        onClose={() => setVersionOpen(false)}
      >
        <p>整套服务以同一发布版本交付。</p>
        <Failure error={version.error} onRetry={version.reload} />
        <dl className="version-details">
          <dt>发布版本</dt>
          <dd>{version.data?.releaseId || "尚未获取"}</dd>
          <dt>代码版本</dt>
          <dd>
            <code>{version.data?.commit || "尚未获取"}</code>
          </dd>
        </dl>
      </Drawer>
    </SessionContext.Provider>
  );
}
export default function Application() {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: "#20685f",
          colorInfo: "#20685f",
          colorInfoBg: "#edf5f1",
          colorInfoBorder: "#d7e6dd",
          colorSuccessBg: "#edf5ec",
          colorSuccessBorder: "#d8e8d2",
          colorWarningBg: "#fff8e9",
          colorWarningBorder: "#eee0b6",
          colorErrorBg: "#fff1ed",
          colorErrorBorder: "#f0d6cf",
          colorSuccess: "#287b56",
          colorWarning: "#a36d21",
          colorError: "#bd4c43",
          colorText: "#20302f",
          colorTextSecondary: "#71807b",
          colorBorder: "#dce2dc",
          colorBgContainer: "#ffffff",
          borderRadius: 10,
          fontSize: 14,
          fontFamily:
            'Inter, -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif',
          controlHeight: 38,
        },
        components: {
          Button: { primaryShadow: "none" },
          Table: { headerBg: "#f6f7f3", headerColor: "#71807b" },
          Tabs: { inkBarColor: "#20685f" },
          Input: { activeShadow: "0 0 0 3px rgba(32,104,95,.08)" },
          Select: { activeOutlineColor: "rgba(32,104,95,.08)" },
        },
      }}
    >
      <AntApp>
        <Main />
      </AntApp>
    </ConfigProvider>
  );
}
