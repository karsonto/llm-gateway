import { useCallback, useEffect, useState } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { Maximize2, Minimize2, RefreshCw } from "lucide-react";
import {
  OverviewQuotaAlert,
  OverviewResponse,
  OverviewTopUser,
  fetchOverview,
} from "../api";

const REFRESH_MS = 30_000;

function formatNum(n: number) {
  return new Intl.NumberFormat("en-US").format(n);
}

function formatTokens(n: number) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return formatNum(n);
}

function shortDate(date: string) {
  const m = date.match(/(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return date;
  return `${Number(m[2])}/${Number(m[3])}`;
}

function shortBucket(bucket: string) {
  const m = bucket.match(/(\d{4})-(\d{2})-(\d{2}) (\d{2})/);
  if (!m) return bucket;
  return `${m[4]}h`;
}

function clockNow() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function KpiCard({
  label,
  value,
  hint,
  tone = "default",
  delay = 0,
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: "default" | "warn" | "danger";
  delay?: number;
}) {
  const toneClass =
    tone === "danger"
      ? "text-[var(--dash-danger)]"
      : tone === "warn"
        ? "text-[var(--dash-warn)]"
        : "text-[var(--dash-accent)]";
  return (
    <div
      className="dash-panel rounded-2xl px-4 py-4 dash-animate"
      style={{ animationDelay: `${delay}ms` }}
    >
      <div className="text-xs uppercase tracking-[0.14em] text-[var(--dash-muted)]">{label}</div>
      <div className={`dash-mono mt-2 text-3xl font-semibold lg:text-4xl xl:text-5xl ${toneClass}`}>
        {value}
      </div>
      {hint ? <div className="mt-1 text-xs text-[var(--dash-muted)]">{hint}</div> : null}
    </div>
  );
}

function TokenTrendChart({ data }: { data: OverviewResponse["token_trend_7d"] }) {
  const series = data.map((p) => ({
    ...p,
    label: shortDate(p.date),
  }));
  return (
    <div className="dash-panel rounded-2xl p-4 h-full dash-animate" style={{ animationDelay: "80ms" }}>
      <h3 className="text-sm font-medium text-[var(--dash-muted)] mb-3">近 7 日 Token</h3>
      <div className="h-52">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={series}>
            <defs>
              <linearGradient id="tokenFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#38bdf8" stopOpacity={0.35} />
                <stop offset="100%" stopColor="#38bdf8" stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="rgba(148,163,184,0.12)" vertical={false} />
            <XAxis dataKey="label" tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis
              tick={{ fill: "#94a3b8", fontSize: 11 }}
              axisLine={false}
              tickLine={false}
              width={48}
              tickFormatter={(v) => formatTokens(Number(v))}
            />
            <Tooltip
              contentStyle={{
                background: "#0f172a",
                border: "1px solid rgba(148,163,184,0.2)",
                borderRadius: 8,
              }}
              labelStyle={{ color: "#e2e8f0" }}
              itemStyle={{ color: "#94a3b8" }}
              formatter={(value: number) => [formatNum(value), "tokens"]}
            />
            <Area
              type="monotone"
              dataKey="total_tokens"
              stroke="#38bdf8"
              strokeWidth={2}
              fill="url(#tokenFill)"
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

function LatencySparkChart({ data }: { data: OverviewResponse["latency_24h"] }) {
  const series = data.map((p) => ({
    ...p,
    label: shortBucket(p.bucket),
  }));
  return (
    <div className="dash-panel rounded-2xl p-4 h-full dash-animate" style={{ animationDelay: "120ms" }}>
      <h3 className="text-sm font-medium text-[var(--dash-muted)] mb-3">近 24h 平均延迟</h3>
      <div className="h-52">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={series}>
            <CartesianGrid stroke="rgba(148,163,184,0.12)" vertical={false} />
            <XAxis dataKey="label" tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis
              tick={{ fill: "#94a3b8", fontSize: 11 }}
              axisLine={false}
              tickLine={false}
              width={48}
              unit="ms"
            />
            <Tooltip
              contentStyle={{
                background: "#0f172a",
                border: "1px solid rgba(148,163,184,0.2)",
                borderRadius: 8,
              }}
              labelStyle={{ color: "#e2e8f0" }}
              itemStyle={{ color: "#94a3b8" }}
              formatter={(value: number) => [`${value} ms`, "avg"]}
            />
            <Line
              type="monotone"
              dataKey="avg_latency_ms"
              stroke="#22d3ee"
              strokeWidth={2}
              dot={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

function TopUsersTable({ rows }: { rows: OverviewTopUser[] }) {
  return (
    <div className="dash-panel rounded-2xl p-4 dash-animate" style={{ animationDelay: "160ms" }}>
      <h3 className="text-sm font-medium text-[var(--dash-muted)] mb-3">今日 Top 用户</h3>
      {rows.length === 0 ? (
        <div className="text-sm text-[var(--dash-muted)] py-8 text-center">暂无数据</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-[var(--dash-muted)] text-left">
              <tr>
                <th className="pb-2 font-medium">#</th>
                <th className="pb-2 font-medium">用户</th>
                <th className="pb-2 font-medium">组别</th>
                <th className="pb-2 font-medium text-right">请求</th>
                <th className="pb-2 font-medium text-right">Token</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.name}-${i}`} className="border-t border-[var(--dash-line)]">
                  <td className="py-2.5 text-[var(--dash-muted)]">{i + 1}</td>
                  <td className="py-2.5 font-medium">{r.name}</td>
                  <td className="py-2.5 text-[var(--dash-muted)]">{r.group_name || "—"}</td>
                  <td className="py-2.5 text-right dash-mono">{formatNum(r.request_count)}</td>
                  <td className="py-2.5 text-right dash-mono text-[var(--dash-accent)]">
                    {formatTokens(r.total_tokens)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function QuotaAlertList({ rows }: { rows: OverviewQuotaAlert[] }) {
  return (
    <div className="dash-panel rounded-2xl p-4 dash-animate" style={{ animationDelay: "200ms" }}>
      <h3 className="text-sm font-medium text-[var(--dash-muted)] mb-3">配额预警</h3>
      {rows.length === 0 ? (
        <div className="text-sm text-[var(--dash-muted)] py-8 text-center">暂无近限或达限用户</div>
      ) : (
        <div className="space-y-3">
          {rows.map((r) => {
            const pct = Math.min(100, Math.round(r.ratio * 100));
            const bar =
              r.status === "exceeded" ? "bg-[var(--dash-danger)]" : "bg-[var(--dash-warn)]";
            return (
              <div key={r.api_key}>
                <div className="flex items-baseline justify-between gap-2 text-sm">
                  <span className="font-medium truncate">{r.name || r.api_key}</span>
                  <span
                    className={`dash-mono text-xs ${
                      r.status === "exceeded" ? "text-[var(--dash-danger)]" : "text-[var(--dash-warn)]"
                    }`}
                  >
                    {pct}%
                  </span>
                </div>
                <div className="mt-1.5 h-1.5 rounded-full bg-slate-800 overflow-hidden">
                  <div className={`h-full rounded-full ${bar}`} style={{ width: `${pct}%` }} />
                </div>
                <div className="mt-1 text-xs text-[var(--dash-muted)] dash-mono">
                  {formatTokens(r.used)} / {formatTokens(r.limit)}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

export default function DashboardPage({
  fullscreen,
  onToggleFullscreen,
}: {
  fullscreen: boolean;
  onToggleFullscreen: () => void;
}) {
  const [data, setData] = useState<OverviewResponse | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [clock, setClock] = useState(clockNow());
  const [updatedAt, setUpdatedAt] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetchOverview();
      setData(res);
      setError("");
      setUpdatedAt(clockNow());
    } catch (e: any) {
      setError(e?.message || "加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
    const refresh = window.setInterval(load, REFRESH_MS);
    const tick = window.setInterval(() => setClock(clockNow()), 1000);
    return () => {
      window.clearInterval(refresh);
      window.clearInterval(tick);
    };
  }, [load]);

  const kpis = data?.kpis;
  const alertTone =
    (kpis?.quota_exceeded_count || 0) > 0
      ? "danger"
      : (kpis?.quota_near_count || 0) > 0
        ? "warn"
        : "default";

  return (
    <div className={`dashboard-wall relative ${fullscreen ? "" : "rounded-2xl overflow-hidden min-h-[calc(100vh-3rem)] p-5"}`}>
      <div className="dash-grid absolute inset-0 pointer-events-none opacity-40" />
      <div className="relative space-y-4">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold tracking-tight lg:text-2xl">LLM Gateway</h1>
            <p className="text-sm text-[var(--dash-muted)] mt-0.5">运营总览</p>
          </div>
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span className="dash-mono text-[var(--dash-muted)]">{clock}</span>
            {updatedAt ? (
              <span className="text-xs text-[var(--dash-muted)]">更新 {updatedAt}</span>
            ) : null}
            {error ? (
              <span className="text-xs text-[var(--dash-danger)]">{error}</span>
            ) : null}
            <button
              type="button"
              onClick={() => load()}
              className="h-9 px-3 rounded-lg border border-[var(--dash-line)] text-[var(--dash-text)] hover:bg-white/5 inline-flex items-center gap-1.5"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin" : ""}`} />
              刷新
            </button>
            <button
              type="button"
              onClick={onToggleFullscreen}
              className="h-9 px-3 rounded-lg border border-[var(--dash-line)] text-[var(--dash-text)] hover:bg-white/5 inline-flex items-center gap-1.5"
            >
              {fullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
              {fullscreen ? "退出全屏" : "全屏"}
            </button>
          </div>
        </header>

        <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
          <KpiCard label="今日请求" value={formatNum(kpis?.today_requests || 0)} delay={0} />
          <KpiCard
            label="今日 Token"
            value={formatTokens(kpis?.today_tokens || 0)}
            hint={formatNum(kpis?.today_tokens || 0)}
            delay={40}
          />
          <KpiCard
            label="本月 Token"
            value={formatTokens(kpis?.month_tokens || 0)}
            hint={formatNum(kpis?.month_tokens || 0)}
            delay={80}
          />
          <KpiCard label="活跃用户" value={formatNum(kpis?.today_active_users || 0)} hint="今日" delay={120} />
          <KpiCard
            label="配额告警"
            value={formatNum((kpis?.quota_near_count || 0) + (kpis?.quota_exceeded_count || 0))}
            hint={`近限 ${kpis?.quota_near_count || 0} · 达限 ${kpis?.quota_exceeded_count || 0}`}
            tone={alertTone}
            delay={160}
          />
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          <TokenTrendChart data={data?.token_trend_7d || []} />
          <LatencySparkChart data={data?.latency_24h || []} />
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
          <TopUsersTable rows={data?.top_users_today || []} />
          <QuotaAlertList rows={data?.quota_alerts || []} />
        </div>
      </div>
    </div>
  );
}
