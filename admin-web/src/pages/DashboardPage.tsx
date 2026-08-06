import { useCallback, useEffect, useState } from "react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { HelpCircle, Maximize2, Minimize2, RefreshCw } from "lucide-react";
import {
  OverviewLatencyPoint,
  OverviewResponse,
  OverviewTokenBreakdown,
  OverviewTopDepartment,
  OverviewTopUser,
  fetchOverview,
} from "../api";
import { METRICS, MetricInfo, metricTitle } from "../metricLabels";

const REFRESH_MS = 30_000;
const PIE_COLORS = { prompt: "#38bdf8", completion: "#34d399" };

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

function LabelWithTip({ label, tip }: { label: string; tip?: string }) {
  return (
    <div className="flex items-center gap-1 text-xs text-slate-500">
      <span className="tracking-wide">{label}</span>
      {tip ? (
        <span className="relative group inline-flex" title={tip}>
          <HelpCircle className="w-3.5 h-3.5 text-slate-300 group-hover:text-slate-500 cursor-help" />
          <span className="pointer-events-none absolute left-1/2 top-full z-20 mt-1.5 w-56 -translate-x-1/2 rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-[11px] leading-relaxed text-slate-600 shadow-lg opacity-0 group-hover:opacity-100 transition">
            {tip}
          </span>
        </span>
      ) : null}
    </div>
  );
}

function KpiCard({
  label,
  value,
  hint,
  tip,
  delay = 0,
}: {
  label: string;
  value: string;
  hint?: string;
  tip?: string;
  delay?: number;
}) {
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm px-4 py-3.5 dash-animate"
      style={{ animationDelay: `${delay}ms` }}
    >
      <LabelWithTip label={label} tip={tip} />
      <div className="dash-mono mt-2 text-2xl font-semibold text-sky-600 lg:text-3xl xl:text-4xl">
        {value}
      </div>
      {hint ? <div className="mt-1 text-xs text-slate-400 truncate">{hint}</div> : null}
    </div>
  );
}

function LatencyKpiCard({
  metric,
  avgMs,
  p50Ms,
  p99Ms,
  delay = 0,
}: {
  metric: MetricInfo;
  avgMs: number;
  p50Ms: number;
  p99Ms: number;
  delay?: number;
}) {
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm px-4 py-3.5 dash-animate"
      style={{ animationDelay: `${delay}ms` }}
    >
      <LabelWithTip label={metricTitle(metric)} tip={metric.desc} />
      <div className="dash-mono mt-2 text-2xl font-semibold text-sky-600 lg:text-3xl">
        {formatNum(Math.round(avgMs || 0))}
        <span className="ml-1 text-sm font-medium text-slate-400">ms</span>
      </div>
      <div className="mt-2 flex gap-3 text-[11px] text-slate-500">
        <span>
          P50 <span className="dash-mono text-slate-700">{formatNum(Math.round(p50Ms || 0))}</span>
        </span>
        <span>
          P99 <span className="dash-mono text-slate-700">{formatNum(Math.round(p99Ms || 0))}</span>
        </span>
      </div>
    </div>
  );
}

function ChartEmpty() {
  return (
    <div className="h-52 flex items-center justify-center text-sm text-slate-400">暂无数据</div>
  );
}

function TokenTrendChart({ data }: { data: OverviewResponse["token_trend_7d"] }) {
  const series = data.map((p) => ({
    ...p,
    label: shortDate(p.date),
  }));
  const hasData = data.some((p) => p.total_tokens > 0 || p.request_count > 0);
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm p-4 h-full dash-animate"
      style={{ animationDelay: "80ms" }}
    >
      <h3 className="text-sm font-medium text-slate-500 mb-3">近 7 日 Token</h3>
      {!hasData ? (
        <ChartEmpty />
      ) : (
        <div className="h-52">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={series}>
              <defs>
                <linearGradient id="tokenFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#0ea5e9" stopOpacity={0.28} />
                  <stop offset="100%" stopColor="#0ea5e9" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid stroke="#e2e8f0" vertical={false} />
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
                  background: "#ffffff",
                  border: "1px solid #e2e8f0",
                  borderRadius: 8,
                  boxShadow: "0 4px 12px rgba(15,23,42,0.08)",
                }}
                formatter={(value: number) => [formatNum(value), "tokens"]}
              />
              <Area
                type="monotone"
                dataKey="total_tokens"
                stroke="#0ea5e9"
                strokeWidth={2}
                fill="url(#tokenFill)"
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

function LatencySparkChart({ data }: { data: OverviewLatencyPoint[] }) {
  const series = data.map((p) => ({
    ...p,
    label: shortBucket(p.bucket),
  }));
  const hasData = data.some((p) => p.request_count > 0 || p.avg_latency_ms > 0 || p.avg_ttft_ms > 0);
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm p-4 h-full dash-animate"
      style={{ animationDelay: "120ms" }}
    >
      <div className="mb-3">
        <h3 className="text-sm font-medium text-slate-500">近 24h 延迟</h3>
        <p className="text-xs text-slate-400 mt-0.5">TTFT / TPOT / ITL / 总耗时（均值）</p>
      </div>
      {!hasData ? (
        <ChartEmpty />
      ) : (
        <div className="h-52">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={series}>
              <CartesianGrid stroke="#e2e8f0" vertical={false} />
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
                  background: "#ffffff",
                  border: "1px solid #e2e8f0",
                  borderRadius: 8,
                  boxShadow: "0 4px 12px rgba(15,23,42,0.08)",
                }}
                formatter={(value: number, name: string) => [`${Number(value).toFixed(0)} ms`, name]}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="avg_ttft_ms" name="TTFT" stroke="#3b82f6" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="avg_tpot_ms" name="TPOT" stroke="#f59e0b" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="avg_itl_ms" name="ITL" stroke="#22c55e" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="avg_latency_ms" name="总耗时" stroke="#64748b" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

function ThroughputSparkChart({ data }: { data: OverviewLatencyPoint[] }) {
  const series = data.map((p) => ({
    ...p,
    label: shortBucket(p.bucket),
  }));
  const hasData = data.some(
    (p) => p.request_tps > 0 || p.output_tps > 0 || p.total_token_tps > 0 || p.request_count > 0
  );
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm p-4 h-full dash-animate"
      style={{ animationDelay: "140ms" }}
    >
      <div className="mb-3">
        <h3 className="text-sm font-medium text-slate-500">近 24h 吞吐</h3>
        <p className="text-xs text-slate-400 mt-0.5">请求 / 输出 Token / 总 Token</p>
      </div>
      {!hasData ? (
        <ChartEmpty />
      ) : (
        <div className="h-52">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={series}>
              <CartesianGrid stroke="#e2e8f0" vertical={false} />
              <XAxis dataKey="label" tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis
                tick={{ fill: "#94a3b8", fontSize: 11 }}
                axisLine={false}
                tickLine={false}
                width={48}
              />
              <Tooltip
                contentStyle={{
                  background: "#ffffff",
                  border: "1px solid #e2e8f0",
                  borderRadius: 8,
                  boxShadow: "0 4px 12px rgba(15,23,42,0.08)",
                }}
                formatter={(value: number, name: string) => [`${Number(value).toFixed(2)} /s`, name]}
              />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Line type="monotone" dataKey="request_tps" name="req/s" stroke="#8b5cf6" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="output_tps" name="输出 tok/s" stroke="#ec4899" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="total_token_tps" name="总 tok/s" stroke="#14b8a6" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

function TopUsersTable({ rows }: { rows: OverviewTopUser[] }) {
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm p-4 dash-animate"
      style={{ animationDelay: "160ms" }}
    >
      <h3 className="text-sm font-medium text-slate-500 mb-3">今日 Top 用户</h3>
      {rows.length === 0 ? (
        <div className="text-sm text-slate-400 py-8 text-center">暂无数据</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-slate-500 text-left">
              <tr>
                <th className="pb-2 font-medium">#</th>
                <th className="pb-2 font-medium">用户</th>
                <th className="pb-2 font-medium">组别</th>
                <th className="pb-2 font-medium">部门</th>
                <th className="pb-2 font-medium text-right">请求</th>
                <th className="pb-2 font-medium text-right">Token</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.name}-${i}`} className="border-t border-slate-100">
                  <td className="py-2.5 text-slate-400">{i + 1}</td>
                  <td className="py-2.5 font-medium text-slate-800">{r.name}</td>
                  <td className="py-2.5 text-slate-500">{r.group_name || "—"}</td>
                  <td className="py-2.5 text-slate-500">{r.department || "FTD"}</td>
                  <td className="py-2.5 text-right dash-mono text-slate-700">{formatNum(r.request_count)}</td>
                  <td className="py-2.5 text-right dash-mono text-sky-600">{formatTokens(r.total_tokens)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function TopDepartmentsTable({ rows }: { rows: OverviewTopDepartment[] }) {
  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm p-4 dash-animate"
      style={{ animationDelay: "180ms" }}
    >
      <h3 className="text-sm font-medium text-slate-500 mb-3">今日部门 Top</h3>
      {rows.length === 0 ? (
        <div className="text-sm text-slate-400 py-8 text-center">暂无数据</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-slate-500 text-left">
              <tr>
                <th className="pb-2 font-medium">#</th>
                <th className="pb-2 font-medium">部门</th>
                <th className="pb-2 font-medium text-right">请求</th>
                <th className="pb-2 font-medium text-right">Token</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={`${r.department}-${i}`} className="border-t border-slate-100">
                  <td className="py-2.5 text-slate-400">{i + 1}</td>
                  <td className="py-2.5 font-medium text-slate-800">{r.department || "FTD"}</td>
                  <td className="py-2.5 text-right dash-mono text-slate-700">{formatNum(r.request_count)}</td>
                  <td className="py-2.5 text-right dash-mono text-sky-600">{formatTokens(r.total_tokens)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function TokenBreakdownPie({ data }: { data: OverviewTokenBreakdown }) {
  const prompt = data?.prompt_tokens || 0;
  const completion = data?.completion_tokens || 0;
  const total = prompt + completion;
  const slices =
    total <= 0
      ? []
      : [
          { name: "Prompt", value: prompt, color: PIE_COLORS.prompt },
          { name: "Completion", value: completion, color: PIE_COLORS.completion },
        ];

  return (
    <div
      className="rounded-xl bg-white border border-slate-200 shadow-sm p-4 dash-animate"
      style={{ animationDelay: "200ms" }}
    >
      <h3 className="text-sm font-medium text-slate-500 mb-3">今日 Token 构成</h3>
      {slices.length === 0 ? (
        <div className="text-sm text-slate-400 py-8 text-center">暂无数据</div>
      ) : (
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <PieChart>
              <Pie
                data={slices}
                dataKey="value"
                nameKey="name"
                cx="50%"
                cy="50%"
                innerRadius={48}
                outerRadius={78}
                paddingAngle={2}
              >
                {slices.map((s) => (
                  <Cell key={s.name} fill={s.color} />
                ))}
              </Pie>
              <Tooltip
                contentStyle={{
                  background: "#ffffff",
                  border: "1px solid #e2e8f0",
                  borderRadius: 8,
                  boxShadow: "0 4px 12px rgba(15,23,42,0.08)",
                }}
                formatter={(value: number, name: string) => [
                  `${formatNum(value)} (${total > 0 ? Math.round((value / total) * 100) : 0}%)`,
                  name,
                ]}
              />
              <Legend />
            </PieChart>
          </ResponsiveContainer>
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
  const latency24h = data?.latency_24h || [];

  return (
    <div
      className={`dashboard-wall relative ${
        fullscreen ? "" : "min-h-[calc(100vh-3rem)]"
      }`}
    >
      <div className="relative space-y-5">
        <header className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-xl font-semibold text-slate-800 lg:text-2xl">LLM Gateway</h1>
            <p className="text-sm text-slate-500 mt-0.5">运营总览</p>
          </div>
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span className="dash-mono text-slate-500">{clock}</span>
            {updatedAt ? <span className="text-xs text-slate-400">更新 {updatedAt}</span> : null}
            {error ? <span className="text-xs text-rose-600">{error}</span> : null}
            <button
              type="button"
              onClick={() => load()}
              className="h-9 px-3 rounded-lg border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 inline-flex items-center gap-1.5"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin" : ""}`} />
              刷新
            </button>
            <button
              type="button"
              onClick={onToggleFullscreen}
              className="h-9 px-3 rounded-lg border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 inline-flex items-center gap-1.5"
            >
              {fullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
              {fullscreen ? "退出全屏" : "全屏"}
            </button>
          </div>
        </header>

        <section className="space-y-2">
          <h2 className="text-xs font-medium uppercase tracking-[0.14em] text-slate-400">用量</h2>
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
            <KpiCard label="本月请求" value={formatNum(kpis?.month_requests || 0)} delay={160} />
          </div>
        </section>

        <section className="space-y-2">
          <h2 className="text-xs font-medium uppercase tracking-[0.14em] text-slate-400">
            近 24h 性能
          </h2>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <LatencyKpiCard
              metric={METRICS.ttft}
              avgMs={kpis?.avg_ttft_ms || 0}
              p50Ms={kpis?.p50_ttft_ms || 0}
              p99Ms={kpis?.p99_ttft_ms || 0}
              delay={0}
            />
            <LatencyKpiCard
              metric={METRICS.tpot}
              avgMs={kpis?.avg_tpot_ms || 0}
              p50Ms={kpis?.p50_tpot_ms || 0}
              p99Ms={kpis?.p99_tpot_ms || 0}
              delay={40}
            />
            <LatencyKpiCard
              metric={METRICS.itl}
              avgMs={kpis?.avg_itl_ms || 0}
              p50Ms={kpis?.p50_itl_ms || 0}
              p99Ms={kpis?.p99_itl_ms || 0}
              delay={80}
            />
            <KpiCard
              label="输出吞吐"
              value={`${Number(kpis?.output_tps || 0).toFixed(1)} tok/s`}
              hint={`请求 ${Number(kpis?.request_tps || 0).toFixed(2)} req/s · 总 ${Number(kpis?.total_token_tps || 0).toFixed(1)} tok/s`}
              tip={`${METRICS.outputTps.desc}；${METRICS.requestTps.desc}；${METRICS.totalTokenTps.desc}`}
              delay={120}
            />
          </div>
        </section>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
          <TokenTrendChart data={data?.token_trend_7d || []} />
          <LatencySparkChart data={latency24h} />
          <ThroughputSparkChart data={latency24h} />
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
          <TopUsersTable rows={data?.top_users_today || []} />
          <TopDepartmentsTable rows={data?.top_departments_today || []} />
          <TokenBreakdownPie
            data={data?.token_breakdown_today || { prompt_tokens: 0, completion_tokens: 0 }}
          />
        </div>
      </div>
    </div>
  );
}
