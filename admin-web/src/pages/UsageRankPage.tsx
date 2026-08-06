import { useEffect, useState } from "react";
import {
  UsageDepartmentRank,
  UsageGroupRank,
  UsageNameRank,
  fetchUsageRank,
} from "../api";

type Scope = "5" | "10" | "20" | "all";

function today() {
  return new Date().toISOString().slice(0, 10);
}

function daysAgo(n: number) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

function formatNum(n: number) {
  return (n ?? 0).toLocaleString();
}

function scopeLabel(scope: Scope) {
  if (scope === "all") return "全量";
  return `Top ${scope}`;
}

function scopeLimit(scope: Scope) {
  if (scope === "all") return 0;
  return Number(scope);
}

export default function UsageRankPage() {
  const [from, setFrom] = useState(daysAgo(29));
  const [to, setTo] = useState(today());
  const [scope, setScope] = useState<Scope>("10");
  const [byName, setByName] = useState<UsageNameRank[]>([]);
  const [byGroup, setByGroup] = useState<UsageGroupRank[]>([]);
  const [byDepartment, setByDepartment] = useState<UsageDepartmentRank[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  async function load(targetScope = scope) {
    setLoading(true);
    setError("");
    try {
      const res = await fetchUsageRank(from, to, scopeLimit(targetScope));
      setByName(res.by_name || []);
      setByGroup(res.by_group || []);
      setByDepartment(res.by_department || []);
    } catch (e: any) {
      setError(e?.message || "查询失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope]);

  const label = scopeLabel(scope);

  const scopeBtn = (value: Scope, text: string, withBorder = false) => (
    <button
      type="button"
      onClick={() => setScope(value)}
      className={`px-3 transition ${withBorder ? "border-l border-slate-200" : ""} ${
        scope === value
          ? "bg-sky-500 text-white"
          : "bg-white text-slate-600 hover:bg-slate-50"
      }`}
    >
      {text}
    </button>
  );

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-slate-800">用量排名</h1>
        <p className="text-sm text-slate-500 mt-1">
          按时间范围统计 Token 用量 {label}（人员 / 组别 / 部门）
        </p>
      </div>

      <div className="flex flex-wrap gap-3 items-end rounded-xl border border-slate-200 bg-white p-4">
        <div className="space-y-1">
          <label className="text-xs text-slate-500">范围</label>
          <div className="flex h-9 rounded-lg border border-slate-200 overflow-hidden text-sm">
            {scopeBtn("5", "Top 5")}
            {scopeBtn("10", "Top 10", true)}
            {scopeBtn("20", "Top 20", true)}
            {scopeBtn("all", "全量", true)}
          </div>
        </div>
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
          onClick={() => load()}
          disabled={loading}
          className="h-9 px-4 rounded-lg bg-sky-500 text-white text-sm hover:bg-sky-600 disabled:opacity-50"
        >
          {loading ? "查询中…" : "查询"}
        </button>
      </div>

      {error && (
        <div className="p-3 rounded-md bg-rose-50 text-sm text-rose-600 border border-rose-200/60">{error}</div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <RankTable
          title={`人员 ${label}`}
          empty="暂无人员用量数据"
          loading={loading}
          headers={["#", "名称", "组别", "部门", "请求数", "Total Tokens"]}
          rows={byName.map((r) => [
            String(r.rank),
            r.name,
            r.group_name,
            r.department || "FTD",
            formatNum(r.request_count),
            formatNum(r.total_tokens),
          ])}
        />
        <RankTable
          title={`组别 ${label}`}
          empty="暂无组别用量数据"
          loading={loading}
          headers={["#", "组别", "请求数", "Total Tokens"]}
          rows={byGroup.map((r) => [
            String(r.rank),
            r.group_name,
            formatNum(r.request_count),
            formatNum(r.total_tokens),
          ])}
        />
        <RankTable
          title={`部门 ${label}`}
          empty="暂无部门用量数据"
          loading={loading}
          headers={["#", "部门", "请求数", "Total Tokens"]}
          rows={byDepartment.map((r) => [
            String(r.rank),
            r.department,
            formatNum(r.request_count),
            formatNum(r.total_tokens),
          ])}
        />
      </div>
    </div>
  );
}

function RankTable({
  title,
  empty,
  loading,
  headers,
  rows,
}: {
  title: string;
  empty: string;
  loading: boolean;
  headers: string[];
  rows: string[][];
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
      <div className="px-4 py-3 border-b border-slate-100 text-sm font-medium text-slate-700">
        {title}
      </div>
      <table className="w-full text-sm">
        <thead className="bg-slate-50 text-slate-500 text-left">
          <tr>
            {headers.map((h) => (
              <th key={h} className="px-3 py-2 font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={headers.length} className="px-3 py-8 text-center text-slate-400">
                {loading ? "加载中…" : empty}
              </td>
            </tr>
          ) : (
            rows.map((row, i) => (
              <tr key={i} className="border-t border-slate-100">
                {row.map((cell, j) => (
                  <td key={j} className="px-3 py-2.5 text-slate-700">
                    {cell}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
