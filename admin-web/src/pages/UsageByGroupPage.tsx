import { useEffect, useState } from "react";
import { ModelUsageBlock, fetchGroups, fetchUsageByGroup } from "../api";
import { ModelUsageCharts } from "../components/ModelUsageCharts";

function today() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

export default function UsageByGroupPage() {
  const [groups, setGroups] = useState<string[]>([]);
  const [groupName, setGroupName] = useState("");
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(today());
  const [blocks, setBlocks] = useState<ModelUsageBlock[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchGroups()
      .then((list) => {
        setGroups(list);
        if (list.length && !groupName) setGroupName(list[0]);
      })
      .catch((e) => setError(e.message));
  }, []);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const res = await fetchUsageByGroup(groupName, from, to);
      setBlocks(res.models || []);
    } catch (e: any) {
      setError(e?.message || "查询失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (groupName) load();
  }, [groupName]);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-800">按组别用量</h1>
        <p className="text-sm text-slate-500 mt-1">按组别汇总模型消耗</p>
      </div>

      <div className="flex flex-wrap gap-3 items-end rounded-xl border border-slate-200 bg-white p-4">
        <div className="space-y-1">
          <label className="text-xs text-slate-500">组别</label>
          <select
            className="h-9 min-w-[160px] px-2 rounded-lg border border-slate-200 text-sm"
            value={groupName}
            onChange={(e) => setGroupName(e.target.value)}
          >
            {groups.map((g) => (
              <option key={g} value={g}>
                {g}
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
          disabled={loading || !groupName}
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
