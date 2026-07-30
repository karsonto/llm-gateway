import { FormEvent, useEffect, useState } from "react";
import { Plus, RefreshCw } from "lucide-react";
import { ApiKeyRow, createKey, fetchKeys, updateKey } from "../api";

export default function KeysPage() {
  const [rows, setRows] = useState<ApiKeyRow[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [groupName, setGroupName] = useState("default");
  const [customKey, setCustomKey] = useState("");
  const [enabled, setEnabled] = useState(true);

  async function load() {
    setLoading(true);
    setError("");
    try {
      setRows(await fetchKeys());
    } catch (e: any) {
      setError(e?.message || "加载失败");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    setError("");
    try {
      await createKey({
        name: name || "unnamed",
        group_name: groupName || "default",
        api_key: customKey || undefined,
        enabled,
      });
      setOpen(false);
      setName("");
      setGroupName("default");
      setCustomKey("");
      setEnabled(true);
      await load();
    } catch (err: any) {
      setError(err?.message || "创建失败");
    }
  }

  async function toggleEnabled(row: ApiKeyRow) {
    try {
      await updateKey({ api_key: row.api_key, enabled: !row.enabled });
      await load();
    } catch (err: any) {
      setError(err?.message || "更新失败");
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-800">API Keys</h1>
          <p className="text-sm text-slate-500 mt-1">创建与管理访问密钥</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={load}
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
              <th className="px-4 py-3 font-medium">状态</th>
              <th className="px-4 py-3 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-4 py-10 text-center text-slate-400">
                  暂无 Key，点击右上角新增
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={row.api_key} className="border-t border-slate-100">
                  <td className="px-4 py-3 font-mono text-xs text-slate-700">{row.api_key}</td>
                  <td className="px-4 py-3">{row.name}</td>
                  <td className="px-4 py-3">
                    <span className="px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 text-xs">
                      {row.group_name}
                    </span>
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
                    <button
                      onClick={() => toggleEnabled(row)}
                      className="text-sky-600 hover:underline text-xs"
                    >
                      {row.enabled ? "禁用" : "启用"}
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
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
    </div>
  );
}
