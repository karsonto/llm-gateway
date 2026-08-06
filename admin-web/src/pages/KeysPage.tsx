import { FormEvent, useEffect, useState } from "react";
import { Plus, RefreshCw } from "lucide-react";
import { ApiKeyRow, createKey, fetchGroups, fetchKeys, updateKey } from "../api";

const PAGE_SIZE = 20;
const TOKENS_PER_MILLION = 1_000_000;

function formatLimitM(limit: number) {
  if (!limit || limit <= 0) return "不限";
  return `${Math.round(limit / TOKENS_PER_MILLION)}M`;
}

function formatUsedM(used: number | undefined) {
  const n = used || 0;
  return `${(n / TOKENS_PER_MILLION).toFixed(2)}M`;
}

function limitToMillionsInput(limit: number) {
  if (!limit || limit <= 0) return "0";
  return String(Math.round(limit / TOKENS_PER_MILLION));
}

function millionsInputToLimit(raw: string) {
  const trimmed = raw.trim();
  if (!trimmed) return 0;
  const n = Number(trimmed);
  if (!Number.isFinite(n) || n < 0) return 0;
  return Math.floor(n) * TOKENS_PER_MILLION;
}

export default function KeysPage() {
  const [rows, setRows] = useState<ApiKeyRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [qInput, setQInput] = useState("");
  const [q, setQ] = useState("");
  const [group, setGroup] = useState("");
  const [enabledFilter, setEnabledFilter] = useState<"all" | "1" | "0">("all");
  const [groups, setGroups] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [groupName, setGroupName] = useState("default");
  const [customKey, setCustomKey] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [limitMillions, setLimitMillions] = useState("0");
  const [editing, setEditing] = useState<ApiKeyRow | null>(null);
  const [editName, setEditName] = useState("");
  const [editGroupName, setEditGroupName] = useState("default");
  const [editLimitMillions, setEditLimitMillions] = useState("0");

  useEffect(() => {
    fetchGroups()
      .then(setGroups)
      .catch(() => {});
  }, []);

  useEffect(() => {
    const t = window.setTimeout(() => {
      setQ(qInput.trim());
      setPage(1);
    }, 300);
    return () => window.clearTimeout(t);
  }, [qInput]);

  async function load(targetPage = page) {
    setLoading(true);
    setError("");
    try {
      const res = await fetchKeys({
        q: q || undefined,
        group: group || undefined,
        enabled: enabledFilter === "all" ? undefined : enabledFilter === "1",
        page: targetPage,
        page_size: PAGE_SIZE,
      });
      setRows(res.items || []);
      setTotal(res.total || 0);
      setPage(res.page || targetPage);
    } catch (e: any) {
      setError(e?.message || "加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, group, enabledFilter, page]);

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await createKey({
        name: name || "unnamed",
        group_name: groupName || "default",
        api_key: customKey || undefined,
        enabled,
        monthly_token_limit: millionsInputToLimit(limitMillions),
      });
      setOpen(false);
      setName("");
      setGroupName("default");
      setCustomKey("");
      setEnabled(true);
      setLimitMillions("0");
      if (page !== 1) setPage(1);
      else await load(1);
      fetchGroups().then(setGroups).catch(() => {});
    } catch (err: any) {
      setError(err?.message || "创建失败");
    }
  }

  async function toggleEnabled(row: ApiKeyRow) {
    try {
      await updateKey({ api_key: row.api_key, enabled: !row.enabled });
      await load(page);
    } catch (err: any) {
      setError(err?.message || "更新失败");
    }
  }

  function openEdit(row: ApiKeyRow) {
    setEditing(row);
    setEditName(row.name);
    setEditGroupName(row.group_name || "default");
    setEditLimitMillions(limitToMillionsInput(row.monthly_token_limit || 0));
    setError("");
  }

  async function onUpdate(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    setError("");
    try {
      await updateKey({
        api_key: editing.api_key,
        name: editName.trim() || "unnamed",
        group_name: editGroupName.trim() || "default",
        monthly_token_limit: millionsInputToLimit(editLimitMillions),
      });
      setEditing(null);
      await load(page);
      fetchGroups().then(setGroups).catch(() => {});
    } catch (err: any) {
      setError(err?.message || "更新失败");
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-800">API Keys</h1>
          <p className="text-sm text-slate-500 mt-1">创建与管理访问密钥</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => load(page)}
            className="h-9 px-3 rounded-lg border border-slate-200 bg-white text-sm text-slate-600 hover:bg-slate-50 inline-flex items-center gap-1"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${loading ? "animate-spin" : ""}`} />
            刷新
          </button>
          <button
            onClick={() => setOpen(true)}
            className="h-9 px-3 rounded-lg bg-sky-500 text-white text-sm hover:bg-sky-600 inline-flex items-center gap-1"
          >
            <Plus className="w-3.5 h-3.5" /> 新增
          </button>
        </div>
      </div>

      <div className="flex flex-wrap gap-3 items-end rounded-xl border border-slate-200 bg-white p-4">
        <div className="space-y-1 flex-1 min-w-[180px]">
          <label className="text-xs text-slate-500">搜索</label>
          <input
            className="h-9 w-full px-3 rounded-lg border border-slate-200 text-sm"
            value={qInput}
            onChange={(e) => setQInput(e.target.value)}
            placeholder="名称或 API Key"
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-slate-500">组别</label>
          <select
            className="h-9 min-w-[140px] px-2 rounded-lg border border-slate-200 text-sm"
            value={group}
            onChange={(e) => {
              setGroup(e.target.value);
              setPage(1);
            }}
          >
            <option value="">全部</option>
            {groups.map((g) => (
              <option key={g} value={g}>
                {g}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <label className="text-xs text-slate-500">状态</label>
          <select
            className="h-9 min-w-[100px] px-2 rounded-lg border border-slate-200 text-sm"
            value={enabledFilter}
            onChange={(e) => {
              setEnabledFilter(e.target.value as "all" | "1" | "0");
              setPage(1);
            }}
          >
            <option value="all">全部</option>
            <option value="1">启用</option>
            <option value="0">禁用</option>
          </select>
        </div>
      </div>

      {error && (
        <div className="p-3 rounded-md bg-rose-50 text-sm text-rose-600 border border-rose-200/60">
          {error}
        </div>
      )}

      <div className="rounded-xl border border-slate-200 bg-white overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-slate-500 text-left">
            <tr>
              <th className="px-4 py-3 font-medium">API Key</th>
              <th className="px-4 py-3 font-medium">名称</th>
              <th className="px-4 py-3 font-medium">组别</th>
              <th className="px-4 py-3 font-medium">月限额</th>
              <th className="px-4 py-3 font-medium">本月已用</th>
              <th className="px-4 py-3 font-medium">状态</th>
              <th className="px-4 py-3 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-4 py-10 text-center text-slate-400">
                  {loading ? "加载中…" : "暂无 Key，点击右上角新增或调整筛选条件"}
                </td>
              </tr>
            ) : (
              rows.map((row) => {
                const limit = row.monthly_token_limit || 0;
                const used = row.month_used_tokens || 0;
                const over = limit > 0 && used >= limit;
                return (
                  <tr key={row.api_key} className="border-t border-slate-100">
                    <td className="px-4 py-3 font-mono text-xs text-slate-700">{row.api_key}</td>
                    <td className="px-4 py-3">{row.name}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 text-xs">
                        {row.group_name}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-slate-700">{formatLimitM(limit)}</td>
                    <td className={`px-4 py-3 ${over ? "text-rose-600 font-medium" : "text-slate-700"}`}>
                      {formatUsedM(used)}
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={`px-2 py-0.5 rounded-full text-xs ${
                          row.enabled ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-500"
                        }`}
                      >
                        {row.enabled ? "启用" : "禁用"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-3">
                        <button
                          onClick={() => openEdit(row)}
                          className="text-sky-600 hover:underline text-xs"
                        >
                          编辑
                        </button>
                        <button
                          onClick={() => toggleEnabled(row)}
                          className="text-sky-600 hover:underline text-xs"
                        >
                          {row.enabled ? "禁用" : "启用"}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
        <div className="flex items-center justify-between px-4 py-3 border-t border-slate-100 text-sm text-slate-500">
          <span>
            共 {total} 条 · 第 {page}/{totalPages} 页
          </span>
          <div className="flex gap-2">
            <button
              disabled={page <= 1 || loading}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className="h-8 px-3 rounded-lg border border-slate-200 bg-white disabled:opacity-40"
            >
              上一页
            </button>
            <button
              disabled={page >= totalPages || loading}
              onClick={() => setPage((p) => p + 1)}
              className="h-8 px-3 rounded-lg border border-slate-200 bg-white disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        </div>
      </div>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <form
            onSubmit={onCreate}
            className="w-full max-w-md rounded-xl bg-white border border-slate-200 shadow-lg p-5 space-y-4"
          >
            <h2 className="text-lg font-semibold text-slate-800">新增 API Key</h2>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">名称</label>
              <input
                className="w-full h-10 px-3 rounded-lg border border-slate-200"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="alice"
                required
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">组别 group_name</label>
              <input
                className="w-full h-10 px-3 rounded-lg border border-slate-200"
                value={groupName}
                onChange={(e) => setGroupName(e.target.value)}
                placeholder="default"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">自定义 Key（可选，留空自动生成）</label>
              <input
                className="w-full h-10 px-3 rounded-lg border border-slate-200 font-mono text-xs"
                value={customKey}
                onChange={(e) => setCustomKey(e.target.value)}
                placeholder="sk-..."
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">月限额（百万 tokens / 月）</label>
              <input
                type="number"
                min={0}
                step={1}
                className="w-full h-10 px-3 rounded-lg border border-slate-200"
                value={limitMillions}
                onChange={(e) => setLimitMillions(e.target.value)}
                placeholder="0 = 不限"
              />
              <p className="text-xs text-slate-400">空或 0 表示不限；填 10 表示 1000 万 tokens</p>
            </div>
            <label className="flex items-center gap-2 text-sm text-slate-600">
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
              启用
            </label>
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="h-9 px-3 rounded-lg border border-slate-200 text-sm"
              >
                取消
              </button>
              <button type="submit" className="h-9 px-3 rounded-lg bg-sky-500 text-white text-sm">
                创建
              </button>
            </div>
          </form>
        </div>
      )}

      {editing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4">
          <form
            onSubmit={onUpdate}
            className="w-full max-w-md rounded-xl bg-white border border-slate-200 shadow-lg p-5 space-y-4"
          >
            <h2 className="text-lg font-semibold text-slate-800">编辑 API Key</h2>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">API Key</label>
              <div className="w-full h-10 px-3 rounded-lg border border-slate-200 bg-slate-50 font-mono text-xs text-slate-700 flex items-center overflow-x-auto">
                {editing.api_key}
              </div>
            </div>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">名称</label>
              <input
                className="w-full h-10 px-3 rounded-lg border border-slate-200"
                value={editName}
                onChange={(e) => setEditName(e.target.value)}
                placeholder="alice"
                required
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">组别 group_name</label>
              <input
                className="w-full h-10 px-3 rounded-lg border border-slate-200"
                value={editGroupName}
                onChange={(e) => setEditGroupName(e.target.value)}
                placeholder="default"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm text-slate-600">月限额（百万 tokens / 月）</label>
              <input
                type="number"
                min={0}
                step={1}
                className="w-full h-10 px-3 rounded-lg border border-slate-200"
                value={editLimitMillions}
                onChange={(e) => setEditLimitMillions(e.target.value)}
                placeholder="0 = 不限"
              />
              <p className="text-xs text-slate-400">空或 0 表示不限；填 10 表示 1000 万 tokens</p>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => setEditing(null)}
                className="h-9 px-3 rounded-lg border border-slate-200 text-sm"
              >
                取消
              </button>
              <button type="submit" className="h-9 px-3 rounded-lg bg-sky-500 text-white text-sm">
                保存
              </button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
