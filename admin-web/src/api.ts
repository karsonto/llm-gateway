export type ApiKeyRow = {
  api_key: string;
  name: string;
  group_name: string;
  enabled: boolean;
  monthly_token_limit: number;
  month_used_tokens?: number;
};

export type KeysPageResult = {
  total: number;
  page: number;
  page_size: number;
  items: ApiKeyRow[];
};

export type UsagePoint = {
  date: string;
  requests: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
};

export type ModelUsageBlock = {
  model: string;
  request_total: number;
  token_total: number;
  series: UsagePoint[];
};

export type UsageChartResponse = {
  models: ModelUsageBlock[];
};

const TOKEN_KEY = "admin_token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers || {});
  if (!headers.has("Content-Type") && init.body) {
    headers.set("Content-Type", "application/json");
  }
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const res = await fetch(`/admin/api${path}`, { ...init, headers });
  const text = await res.text();
  let data: any = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch {
    data = { error: { message: text } };
  }
  if (!res.ok) {
    const msg = data?.error?.message || res.statusText || "Request failed";
    throw new Error(msg);
  }
  return data as T;
}

export const login = (username: string, password: string) =>
  api<{ token: string; username: string }>("/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });

export const logout = () => api<{ ok: boolean }>("/logout", { method: "POST" });

export const fetchKeys = (params?: {
  q?: string;
  group?: string;
  enabled?: boolean;
  page?: number;
  page_size?: number;
}) => {
  const q = new URLSearchParams();
  if (params?.q) q.set("q", params.q);
  if (params?.group) q.set("group", params.group);
  if (params?.enabled !== undefined) q.set("enabled", params.enabled ? "1" : "0");
  if (params?.page !== undefined) q.set("page", String(params.page));
  if (params?.page_size !== undefined) q.set("page_size", String(params.page_size));
  const qs = q.toString();
  return api<KeysPageResult>(qs ? `/keys?${qs}` : "/keys");
};

export const createKey = (body: {
  api_key?: string;
  name: string;
  group_name: string;
  enabled: boolean;
  monthly_token_limit?: number;
}) =>
  api<ApiKeyRow>("/keys", {
    method: "POST",
    body: JSON.stringify(body),
  });

export const updateKey = (body: {
  api_key: string;
  name?: string;
  group_name?: string;
  enabled?: boolean;
  monthly_token_limit?: number;
}) =>
  api<ApiKeyRow>("/keys", {
    method: "PATCH",
    body: JSON.stringify(body),
  });

export const fetchGroups = () => api<string[]>("/groups");

export const fetchUsageByKey = (apiKey: string, from?: string, to?: string) => {
  const q = new URLSearchParams({ api_key: apiKey });
  if (from) q.set("from", from);
  if (to) q.set("to", to);
  return api<UsageChartResponse>(`/usage/by-key?${q}`);
};

export const fetchUsageByGroup = (groupName: string, from?: string, to?: string) => {
  const q = new URLSearchParams();
  if (groupName) q.set("group_name", groupName);
  if (from) q.set("from", from);
  if (to) q.set("to", to);
  return api<UsageChartResponse>(`/usage/by-group?${q}`);
};

/** All keys aggregated; reuses by-group without group filter. */
export const fetchUsageAll = (from?: string, to?: string) =>
  fetchUsageByGroup("", from, to);

export type UsageNameRank = {
  rank: number;
  api_key: string;
  name: string;
  group_name: string;
  request_count: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
};

export type UsageGroupRank = {
  rank: number;
  group_name: string;
  request_count: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
};

export type UsageRankResponse = {
  by_name: UsageNameRank[];
  by_group: UsageGroupRank[];
};

export const fetchUsageRank = (from?: string, to?: string, limit = 10) => {
  const q = new URLSearchParams();
  if (from) q.set("from", from);
  if (to) q.set("to", to);
  q.set("limit", String(limit));
  return api<UsageRankResponse>(`/usage/rank?${q}`);
};

export type LatencyPoint = {
  bucket: string;
  request_count: number;
  avg_ttft_ms: number;
  avg_latency_ms: number;
  latency_sum_ms: number;
  latency_max_ms: number;
};

export type ModelLatencyBlock = {
  model: string;
  request_total: number;
  series: LatencyPoint[];
};

export type LatencyChartResponse = {
  models: ModelLatencyBlock[];
};

export const fetchUsageLatency = (from?: string, to?: string, model?: string) => {
  const q = new URLSearchParams();
  if (from) q.set("from", from);
  if (to) q.set("to", to);
  if (model) q.set("model", model);
  const qs = q.toString();
  return api<LatencyChartResponse>(qs ? `/usage/latency?${qs}` : "/usage/latency");
};

export type OverviewKpis = {
  today_requests: number;
  today_tokens: number;
  month_tokens: number;
  today_active_users: number;
  month_requests: number;
};

export type OverviewTrendPoint = {
  date: string;
  total_tokens: number;
  request_count: number;
};

export type OverviewLatencyPoint = {
  bucket: string;
  avg_latency_ms: number;
  request_count: number;
};

export type OverviewTopUser = {
  name: string;
  group_name: string;
  total_tokens: number;
  request_count: number;
};

export type OverviewTokenBreakdown = {
  prompt_tokens: number;
  completion_tokens: number;
};

export type OverviewResponse = {
  generated_at: string;
  kpis: OverviewKpis;
  token_trend_7d: OverviewTrendPoint[];
  latency_24h: OverviewLatencyPoint[];
  top_users_today: OverviewTopUser[];
  token_breakdown_today: OverviewTokenBreakdown;
};

export const fetchOverview = () => api<OverviewResponse>("/overview");
