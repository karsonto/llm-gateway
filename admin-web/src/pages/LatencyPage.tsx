import { useEffect, useState } from "react";
import { LatencyChartResponse, ModelLatencyBlock, fetchUsageLatency } from "../api";
import { ModelLatencyCharts } from "../components/ModelLatencyCharts";

function today() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

export default function LatencyPage() {
  const [from, setFrom] = useState(daysAgo(6));
  const [to, setTo] = useState(today());
  const [model, setModel] = useState("");
  const [models, setModels] = useState<string[]>([]);
  const [blocks, setBlocks] = useState<ModelLatencyBlock[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const res: LatencyChartResponse = await fetchUsageLatency(
        from,
        to,
        model || undefined
      );
      const list = res.models || [];
      setBlocks(list);
      if (!model) {
        setModels(list.map((b) => b.model));
      }
    } catch (e: any) {
      setError(e?.message || "查询失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-800">响应延迟</h1>
        <p className="text-sm text-slate-500 mt-1">
          按小时统计各模型平均 TTFT/总耗时、最大响应与时段总延时
        </p>
      </div>

      <div className="flex flex-wrap gap-3 items-end rounded-xl border border-slate-200 bg-white p-4">
        <div className="space-y-1">
          <label className="text-xs text-slate-500">From</label>
          <input
            type="date"
            className="h-9 px-2 rounded-lg border border-slate-200 text-sm"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-slate-500">To</label>
          <input
            type="date"
            className="h-9 px-2 rounded-lg border border-slate-200 text-sm"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-slate-500">模型</label>
          <select
            className="h-9 min-w-[180px] px-2 rounded-lg border border-slate-200 text-sm"
            value={model}
            onChange={(e) => setModel(e.target.value)}
          >
            <option value="">全部</option>
            {models.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </div>
        <button
          onClick={load}
          disabled={loading}
          className="h-9 px-4 rounded-lg bg-sky-500 text-white text-sm hover:bg-sky-600 disabled:opacity-50"
        >
          {loading ? "查询中…" : "查询"}
        </button>
      </div>

      {error && (
        <div className="p-3 rounded-md bg-rose-50 text-sm text-rose-600 border border-rose-200/60">
          {error}
        </div>
      )}

      <ModelLatencyCharts blocks={blocks} />
    </div>
  );
}
