import { createContext, useContext, useEffect, useState } from "react";
import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import { api, getToken, Role, setToken, User } from "./api";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import Inbox from "./pages/Inbox";
import Orders from "./pages/Orders";
import Inventory from "./pages/Inventory";
import Customers from "./pages/Customers";
import Collections from "./pages/Collections";
import Field from "./pages/Field";
import Tally from "./pages/Tally";
import Team from "./pages/Team";

const UserCtx = createContext<User | null>(null);
export const useUser = () => useContext(UserCtx)!;
export const can = (u: User, ...roles: Role[]) => roles.includes(u.role);

type NavItem = { path: string; label: string; roles: Role[]; el: JSX.Element };
const ALL: Role[] = ["OWNER", "ACCOUNTANT", "SALES", "DELIVERY"];
const NAV: NavItem[] = [
  { path: "/", label: "Dashboard", roles: ["OWNER", "ACCOUNTANT", "SALES"], el: <Dashboard /> },
  { path: "/inbox", label: "WhatsApp orders", roles: ["OWNER", "ACCOUNTANT", "SALES"], el: <Inbox /> },
  { path: "/orders", label: "Sales orders", roles: ALL, el: <Orders /> },
  { path: "/inventory", label: "Inventory", roles: ["OWNER", "ACCOUNTANT", "SALES"], el: <Inventory /> },
  { path: "/customers", label: "Customers & credit", roles: ["OWNER", "ACCOUNTANT", "SALES"], el: <Customers /> },
  { path: "/collections", label: "Collections", roles: ["OWNER", "ACCOUNTANT", "SALES"], el: <Collections /> },
  { path: "/field", label: "Field & delivery", roles: ALL, el: <Field /> },
  { path: "/tally", label: "Tally sync", roles: ["OWNER", "ACCOUNTANT"], el: <Tally /> },
  { path: "/team", label: "Team", roles: ["OWNER"], el: <Team /> },
];

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [checking, setChecking] = useState(!!getToken());

  useEffect(() => {
    const out = () => setUser(null);
    window.addEventListener("auth:logout", out);
    if (getToken()) {
      api<User>("/api/me").then(setUser).catch(() => setToken(null)).finally(() => setChecking(false));
    }
    return () => window.removeEventListener("auth:logout", out);
  }, []);

  if (checking) return <div className="skeleton">Loading…</div>;
  if (!user) return <Login onLogin={setUser} />;

  const items = NAV.filter(n => n.roles.includes(user.role));
  const home = items[0].path;
  return (
    <UserCtx.Provider value={user}>
      <div className="app">
        <aside className="rail">
          <div className="brand"><div className="logo" aria-hidden="true">D</div><div><b>DistributorOS</b><small>{user.tenantName}</small></div></div>
          <nav className="nav" aria-label="Modules">
            {items.map(n => <NavLink key={n.path} to={n.path} end={n.path === "/"} className={({ isActive }) => (isActive ? "on" : "")}><span>{n.label}</span></NavLink>)}
          </nav>
          <div className="userbox"><div><b>{user.name}</b>{user.role.toLowerCase()}</div>
            <button className="btn sm" onClick={() => { setToken(null); setUser(null); }}>Sign out</button></div>
        </aside>
        <main className="main">
          <Routes>
            {items.map(n => <Route key={n.path} path={n.path} element={n.el} />)}
            <Route path="*" element={<Navigate to={home} replace />} />
          </Routes>
        </main>
      </div>
    </UserCtx.Provider>
  );
}
