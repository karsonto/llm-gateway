import { useEffect, useState } from "react";
import { ApiKeyRow, ModelUsageBlock, fetchKeys, fetchUsageByKey } from "../api";
import { ModelUsageCharts } from "../components/ModelUsageCharts";

function today() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

export default function UsageByKeyPage() {
  const [keys, setKeys] = useState<ApiKeyRow[]>([]);
  const [apiKey, setApiKey] = useState("");
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(today());
  const [blocks, setBlocks] = useState<ModelUsageBlock[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchKeys()
      .then((list) => {
        setKeys(list);
        if (list.length && !apiKey) setApiKey(list[0].api_key);
      })
      .catch((e) => setError(e.message));
  }, []);

  async function load() {
    if (!apiKey) return;
    setLoading(true);
    setError("");
    try {
      const res = await fetchUsageByKey(apiKey, from, to);
      setBlocks(res.models || []);
    } catch (e: any) {
      setError(e?.message || "查询失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (apiKey) load();
  }, [apiKey]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-800">按 Key 用量</h1>
        <p className="text-sm text-slate-500 mt-1">按模型查看请求量与 Token 趋势</p>
      </div>

      <div className="flex flex-wrap gap-3 items-end rounded-xl border border-slate-200 bg-white p-4">
        <div className="space-y-1">
          <label className="text-xs text-slate-500">API Key</label>
          <select
            className="h-9 min-w-[220px] px-2 rounded-lg border border-slate-200 text-sm"
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
          >
            {keys.map((k) => (
              <option key={k.api_key} value={k.api_key}>
                {k.name} ({k.api_key.slice(0, 12)}…)
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <label className="text-xs text-slate-500">From</label>
          <input type="date" className="h-9 px-2 rounded-lg border border-slate-200 text-sm" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-slate-500">To</label>
          <input type="date" className="h-9 px-2 rounded-lg border border-slate-200 text-sm" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <button
          onClick={load}
          disabled={loading || !apiKey}
          className="h-9 px-4 rounded-lg bg-sky-500 text-white text-sm hover:bg-sky-600 disabled:opacity-50"
        >
          {loading ? "查询中…" : "查询"}
        </button>
      </div>

      {error && (
        <div className="p-3 rounded-md bg-rose-50 text-sm text-rose-600 border border-rose-200/60">{error}</div>
      )}

      <ModelUsageCharts blocks={blocks} />
    </div>
  );
}
