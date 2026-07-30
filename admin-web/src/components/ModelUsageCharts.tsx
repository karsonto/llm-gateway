import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ModelUsageBlock } from "../api";

function formatNum(n: number) {
  return new Intl.NumberFormat("en-US").format(n);
}

function shortDate(d: string) {
  // 2026-07-01 -> 7/1
  const m = d.match(/(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return d;
  return `${Number(m[2])}/${Number(m[3])}`;
}

export function ModelUsageCharts({ blocks }: { blocks: ModelUsageBlock[] }) {
  if (!blocks.length) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-slate-400 text-sm">
        暂无用量数据
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {blocks.map((block) => {
        const data = block.series.map((p) => ({
          ...p,
          label: shortDate(p.date),
        }));
        return (
          <section key={block.model} className="space-y-3">
            <h2 className="text-base font-semibold text-slate-800">{block.model}</h2>
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              <div className="rounded-xl bg-[#1a1d23] text-slate-100 p-4 border border-slate-800">
                <div className="flex items-baseline justify-between mb-3">
                  <span className="text-sm text-slate-300">API requests</span>
                  <span className="text-lg font-semibold tabular-nums">
                    {formatNum(block.request_total)}
                  </span>
                </div>
                <div className="h-48">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={data}>
                      <defs>
                        <linearGradient id={`req-${block.model}`} x1="0" y1="0" x2="0" y2="1">
                          <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.45} />
                          <stop offset="100%" stopColor="#3b82f6" stopOpacity={0.02} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid stroke="#2a2f3a" vertical={false} />
                      <XAxis dataKey="label" tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} />
                      <YAxis tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} width={40} />
                      <Tooltip
                        contentStyle={{ background: "#111827", border: "1px solid #334155", borderRadius: 8 }}
                        labelStyle={{ color: "#e2e8f0" }}
                      />
                      <Area
                        type="monotone"
                        dataKey="requests"
                        stroke="#3b82f6"
                        fill={`url(#req-${block.model})`}
                        strokeWidth={2}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              </div>

              <div className="rounded-xl bg-[#1a1d23] text-slate-100 p-4 border border-slate-800">
                <div className="flex items-baseline justify-between mb-3">
                  <span className="text-sm text-slate-300">Tokens</span>
                  <span className="text-lg font-semibold tabular-nums">
                    {formatNum(block.token_total)}
                  </span>
                </div>
                <div className="h-48">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={data}>
                      <CartesianGrid stroke="#2a2f3a" vertical={false} />
                      <XAxis dataKey="label" tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} />
                      <YAxis tick={{ fill: "#94a3b8", fontSize: 11 }} axisLine={false} tickLine={false} width={48} />
                      <Tooltip
                        contentStyle={{ background: "#111827", border: "1px solid #334155", borderRadius: 8 }}
                        labelStyle={{ color: "#e2e8f0" }}
                      />
                      <Bar dataKey="prompt_tokens" stackId="t" fill="#93c5fd" name="prompt" />
                      <Bar dataKey="completion_tokens" stackId="t" fill="#3b82f6" name="completion" radius={[3, 3, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            </div>
          </section>
        );
      })}
    </div>
  );
}
