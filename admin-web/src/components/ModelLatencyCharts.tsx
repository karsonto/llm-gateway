import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { ModelLatencyBlock } from "../api";

function formatNum(n: number) {
  return new Intl.NumberFormat("en-US").format(n);
}

function shortBucket(bucket: string) {
  const m = bucket.match(/(\d{4})-(\d{2})-(\d{2}) (\d{2})/);
  if (!m) return bucket;
  return `${Number(m[2])}/${Number(m[3])} ${m[4]}h`;
}

export function ModelLatencyCharts({ blocks }: { blocks: ModelLatencyBlock[] }) {
  if (!blocks.length) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-slate-400 text-sm">
        暂无延迟数据
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {blocks.map((block) => {
        const data = block.series.map((p) => ({
          ...p,
          label: shortBucket(p.bucket),
        }));
        return (
          <section key={block.model} className="space-y-3">
            <div className="flex items-baseline justify-between">
              <h2 className="text-base font-semibold text-slate-800">{block.model}</h2>
              <span className="text-sm text-slate-500">
                请求 {formatNum(block.request_total)}
              </span>
            </div>
            <div className="rounded-xl bg-white text-slate-800 p-4 border border-slate-200 shadow-sm">
              <div className="h-56">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={data}>
                    <CartesianGrid stroke="#e2e8f0" vertical={false} />
                    <XAxis
                      dataKey="label"
                      tick={{ fill: "#64748b", fontSize: 11 }}
                      axisLine={false}
                      tickLine={false}
                    />
                    <YAxis
                      yAxisId="left"
                      tick={{ fill: "#64748b", fontSize: 11 }}
                      axisLine={false}
                      tickLine={false}
                      width={48}
                      unit="ms"
                    />
                    <YAxis
                      yAxisId="right"
                      orientation="right"
                      tick={{ fill: "#64748b", fontSize: 11 }}
                      axisLine={false}
                      tickLine={false}
                      width={56}
                      unit="ms"
                    />
                    <Tooltip
                      contentStyle={{
                        background: "#ffffff",
                        border: "1px solid #e2e8f0",
                        borderRadius: 8,
                        boxShadow: "0 4px 12px rgba(15,23,42,0.08)",
                      }}
                      labelStyle={{ color: "#334155" }}
                      itemStyle={{ color: "#475569" }}
                      formatter={(value: number, name: string) => [`${value} ms`, name]}
                    />
                    <Legend />
                    <Line
                      yAxisId="left"
                      type="monotone"
                      dataKey="avg_ttft_ms"
                      name="TTFT"
                      stroke="#3b82f6"
                      strokeWidth={2}
                      dot={false}
                    />
                    <Line
                      yAxisId="left"
                      type="monotone"
                      dataKey="avg_latency_ms"
                      name="总耗时"
                      stroke="#f59e0b"
                      strokeWidth={2}
                      dot={false}
                    />
                    <Line
                      yAxisId="left"
                      type="monotone"
                      dataKey="latency_max_ms"
                      name="最大"
                      stroke="#ef4444"
                      strokeWidth={2}
                      dot={false}
                    />
                    <Line
                      yAxisId="right"
                      type="monotone"
                      dataKey="latency_sum_ms"
                      name="总延时"
                      stroke="#22c55e"
                      strokeWidth={2}
                      dot={false}
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            </div>
          </section>
        );
      })}
    </div>
  );
}
