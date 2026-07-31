import { useEffect, useState } from "react";
import {
  ApiKeyRow,
  ModelUsageBlock,
  fetchGroups,
  fetchKeys,
  fetchUsageAll,
  fetchUsageByGroup,
  fetchUsageByKey,
} from "../api";
import { ModelUsageCharts } from "../components/ModelUsageCharts";

type Dimension = "name" | "group" | "all";

function today() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

export default function UsagePage() {
  const [dimension, setDimension] = useState<Dimension>("name");
  const [keys, setKeys] = useState<ApiKeyRow[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [apiKey, setApiKey] = useState("");
  const [groupName, setGroupName] = useState("");
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(today());
  const [blocks, setBlocks] = useState<ModelUsageBlock[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setBlocks([]);
    setError("");
    if (dimension === "name") {
      fetchKeys({ page: 1, page_size: 100 })
        .then((res) => {
          const list = res.items || [];
          setKeys(list);
          setApiKey(list.length ? list[0].api_key : "");
        })
        .catch((e) => setError(e.message));
    } else if (dimension === "group") {
      fetchGroups()
        .then((list) => {
          setGroups(list);
          setGroupName(list.length ? list[0] : "");
        })
        .catch((e) => setError(e.message));
    }
  }, [dimension]);

  async function load() {
    if (dimension === "name" && !apiKey) return;
    if (dimension === "group" && !groupName) return;
    setLoading(true);
    setError("");
    try {
      const res =
        dimension === "name"
          ? await fetchUsageByKey(apiKey, from, to)
          : dimension === "group"
            ? await fetchUsageByGroup(groupName, from, to)
            : await fetchUsageAll(from, to);
      setBlocks(res.models || []);
    } catch (e: any) {
      setError(e?.message || "查询失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (dimension === "name" && apiKey) load();
    else if (dimension === "group" && groupName) load();
    else if (dimension === "all") load();
  }, [dimension, apiKey, groupName]);

  const canQuery =
    dimension === "all" || (dimension === "name" ? !!apiKey : !!groupName);

  const dimBtn = (value: Dimension, label: string, withBorder = false) => (
    <button
      type="button"
      onClick={() => setDimension(value)}
      className={`px-3 transition ${withBorder ? "border-l border-slate-200" : ""} ${
        dimension === value
          ? "bg-sky-500 text-white"
          : "bg-white text-slate-600 hover:bg-slate-50"
      }`}
    >
      {label}
    </button>
  );

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-800">用量查询</h1>
        <p className="text-sm text-slate-500 mt-1">按模型查看请求量与 Token 趋势</p>
      </div>

      <div className="flex flex-wrap gap-3 items-end rounded-xl border border-slate-200 bg-white p-4">
        <div className="space-y-1">
          <label className="text-xs text-slate-500">维度</label>
          <div className="flex h-9 rounded-lg border border-slate-200 overflow-hidden text-sm">
            {dimBtn("name", "名字")}
            {dimBtn("group", "组别", true)}
            {dimBtn("all", "全量", true)}
          </div>
        </div>

        {dimension === "name" && (
          <div className="space-y-1">
            <label className="text-xs text-slate-500">名字</label>
            <select
              className="h-9 min-w-[180px] px-2 rounded-lg border border-slate-200 text-sm"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            >
              {keys.map((k) => (
                <option key={k.api_key} value={k.api_key}>
                  {k.name}
                </option>
              ))}
            </select>
          </div>
        )}

        {dimension === "group" && (
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
        )}

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
        <button
          onClick={load}
          disabled={loading || !canQuery}
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
