import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowRight, KeyRound, Loader2 } from "lucide-react";
import { login, setToken } from "../api";

export default function LoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const res = await login(username, password);
      setToken(res.token);
      navigate("/keys");
    } catch (err: any) {
      setError(err?.message || "登录失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="w-full min-h-screen lg:grid lg:grid-cols-2 bg-background">
      <div className="hidden lg:flex flex-col justify-between bg-gradient-to-br from-sky-50 via-cyan-50 to-emerald-50 p-10 text-slate-700 relative overflow-hidden">
        <div className="absolute inset-0 opacity-40 pointer-events-none">
          <div className="absolute inset-[-40%] bg-[radial-gradient(circle_700px_at_30%_20%,#93c5fd,transparent)]" />
          <div className="absolute inset-[-40%] bg-[radial-gradient(circle_900px_at_80%_60%,#a7f3d0,transparent)]" />
        </div>
        <div className="relative z-10 flex items-center gap-2 text-lg font-semibold tracking-tight">
          <div className="p-1.5 bg-slate-50/70 rounded-lg border border-slate-100/70">
            <KeyRound className="w-5 h-5" />
          </div>
          <span>LLM Gateway</span>
        </div>
        <div className="relative z-10 max-w-md space-y-4">
          <p className="text-2xl font-medium leading-relaxed tracking-tight">
            API Key 管理与用量洞察，一站式掌控网关访问与消耗。
          </p>
          <div className="text-sm text-slate-500 flex items-center gap-2">
            <div className="w-8 h-px bg-slate-300" />
            <span>Admin Console</span>
          </div>
        </div>
        <div className="relative z-10 text-xs text-slate-500 font-mono">
          &copy; {new Date().getFullYear()} LLM Gateway
        </div>
      </div>

      <div className="flex items-center justify-center p-8 bg-slate-50">
        <div className="w-full max-w-[360px] mx-auto space-y-8">
          <div className="space-y-2 text-center lg:text-left">
            <h1 className="text-2xl font-bold tracking-tight text-slate-800">欢迎回来</h1>
            <p className="text-sm text-slate-500">请使用管理员账号登录</p>
          </div>
          <form onSubmit={onSubmit} className="space-y-5">
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">账号</label>
              <input
                className="w-full h-11 px-3 rounded-lg border border-slate-200 bg-slate-50/80 text-slate-700 outline-none focus:ring-2 focus:ring-sky-400/40"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoFocus
                disabled={loading}
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium text-slate-700">密码</label>
              <input
                type="password"
                className="w-full h-11 px-3 rounded-lg border border-slate-200 bg-slate-50/80 text-slate-700 tracking-widest outline-none focus:ring-2 focus:ring-sky-400/40"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
            </div>
            {error && (
              <div className="p-3 rounded-md bg-rose-50 text-sm text-rose-600 border border-rose-200/60">
                {error}
              </div>
            )}
            <button
              type="submit"
              disabled={loading}
              className="w-full h-11 rounded-lg bg-sky-500 text-white font-medium hover:bg-sky-600 transition shadow-sm disabled:opacity-60 flex items-center justify-center gap-2"
            >
              {loading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" /> 登录中...
                </>
              ) : (
                <>
                  立即登录 <ArrowRight className="w-4 h-4 opacity-70" />
                </>
              )}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
