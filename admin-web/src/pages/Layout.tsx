import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { KeyRound, BarChart3, LogOut } from "lucide-react";
import { logout, setToken } from "../api";

const nav = [
  { to: "/keys", label: "API Keys", icon: KeyRound },
  { to: "/usage", label: "用量查询", icon: BarChart3 },
];

export default function Layout() {
  const navigate = useNavigate();

  async function onLogout() {
    try {
      await logout();
    } catch {
      // ignore
    }
    setToken(null);
    navigate("/login");
  }

  return (
    <div className="min-h-screen flex bg-slate-50">
      <aside className="w-56 shrink-0 border-r border-slate-200 bg-white flex flex-col">
        <div className="h-14 px-4 flex items-center gap-2 border-b border-slate-100 font-semibold text-slate-800">
          <KeyRound className="w-4 h-4 text-sky-500" />
          Gateway Admin
        </div>
        <nav className="p-3 space-y-1 flex-1">
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex items-center gap-2 px-3 py-2 rounded-lg text-sm transition ${
                  isActive
                    ? "bg-sky-50 text-sky-700 font-medium"
                    : "text-slate-600 hover:bg-slate-50"
                }`
              }
            >
              <item.icon className="w-4 h-4" />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <button
          onClick={onLogout}
          className="m-3 flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-slate-500 hover:bg-slate-50"
        >
          <LogOut className="w-4 h-4" /> 退出登录
        </button>
      </aside>
      <main className="flex-1 overflow-auto">
        <div className="max-w-6xl mx-auto p-6">
          <Outlet />
        </div>
      </main>
    </div>
  );
}
