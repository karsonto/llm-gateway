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
import { LatencyPoint, ModelLatencyBlock } from "../api";
import { METRICS, metricTitle } from "../metricLabels";

function formatNum(n: number) {
  return new Intl.NumberFormat("en-US").format(n);
}

function shortBucket(bucket: string) {
  const m = bucket.match(/(\d{4})-(\d{2})-(\d{2}) (\d{2})/);
  if (!m) return bucket;
  return `${Number(m[2])}/${Number(m[3])} ${m[4]}h`;
}

type ChartLine = {
  dataKey: keyof LatencyPoint | string;
  name: string;
  stroke: string;
};

type ChartPoint = LatencyPoint & { label: string };

function LatencyLineChart({
  title,
  subtitle,
  data,
  lines,
  unit = "ms",
}: {
  title: string;
  subtitle?: string;
  data: ChartPoint[];
  lines: ChartLine[];
  unit?: string;
}) {
  return (
    <div className="rounded-xl bg-white text-slate-800 p-4 border border-slate-200 shadow-sm">
      <h3 className="text-sm font-medium text-slate-600">{title}</h3>
      {subtitle ? <p className="text-xs text-slate-400 mt-0.5 mb-2">{subtitle}</p> : <div className="mb-3" />}
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
              tick={{ fill: "#64748b", fontSize: 11 }}
              axisLine={false}
              tickLine={false}
              width={52}
              unit={unit === "ms" ? "ms" : undefined}
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
              formatter={(value: number, name: string) => [
                `${typeof value === "number" ? Number(value).toFixed(unit === "ms" ? 0 : 2) : value} ${unit}`,
                name,
              ]}
            />
            <Legend />
            {lines.map((line) => (
              <Line
                key={String(line.dataKey)}
                type="monotone"
                dataKey={line.dataKey}
                name={line.name}
                stroke={line.stroke}
                strokeWidth={2}
                dot={false}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
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
        const data: ChartPoint[] = block.series.map((p) => ({
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
            <div className="space-y-3">
              <LatencyLineChart
                title={metricTitle(METRICS.ttft)}
                subtitle={METRICS.ttft.desc}
                data={data}
                lines={[
                  { dataKey: "avg_ttft_ms", name: "平均值", stroke: "#3b82f6" },
                  { dataKey: "p50_ttft_ms", name: "中位数 P50", stroke: "#0ea5e9" },
                  { dataKey: "p99_ttft_ms", name: "P99", stroke: "#6366f1" },
                ]}
              />
              <LatencyLineChart
                title={metricTitle(METRICS.tpot)}
                subtitle={METRICS.tpot.desc}
                data={data}
                lines={[
                  { dataKey: "avg_tpot_ms", name: "平均值", stroke: "#f59e0b" },
                  { dataKey: "p50_tpot_ms", name: "中位数 P50", stroke: "#fb923c" },
                  { dataKey: "p99_tpot_ms", name: "P99", stroke: "#ea580c" },
                ]}
              />
              <LatencyLineChart
                title={metricTitle(METRICS.itl)}
                subtitle={METRICS.itl.desc}
                data={data}
                lines={[
                  { dataKey: "avg_itl_ms", name: "平均值", stroke: "#22c55e" },
                  { dataKey: "p50_itl_ms", name: "中位数 P50", stroke: "#4ade80" },
                  { dataKey: "p99_itl_ms", name: "P99", stroke: "#16a34a" },
                ]}
              />
              <LatencyLineChart
                title={metricTitle(METRICS.latency)}
                subtitle={METRICS.latency.desc}
                data={data}
                lines={[
                  { dataKey: "avg_latency_ms", name: "平均总耗时", stroke: "#64748b" },
                  { dataKey: "latency_max_ms", name: "最大总耗时", stroke: "#ef4444" },
                ]}
              />
              <LatencyLineChart
                title="吞吐"
                subtitle={`${METRICS.requestTps.desc}；${METRICS.outputTps.desc}；${METRICS.totalTokenTps.desc}`}
                data={data}
                unit="/s"
                lines={[
                  { dataKey: "request_tps", name: "请求吞吐 req/s", stroke: "#8b5cf6" },
                  { dataKey: "output_tps", name: "输出 Token tok/s", stroke: "#ec4899" },
                  { dataKey: "total_token_tps", name: "总 Token tok/s", stroke: "#14b8a6" },
                ]}
              />
            </div>
          </section>
        );
      })}
    </div>
  );
}
