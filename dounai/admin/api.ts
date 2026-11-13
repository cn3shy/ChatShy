import type {
  ModelPrice,
  ModelPriceInput,
  ProviderInfo,
  ProviderInput,
  RequestDetail,
  RequestPage,
  SettingsInfo,
  ThreadDetail,
  ThreadSummary,
  UsageResponse,
} from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly body: unknown;

  constructor(status: number, body: unknown) {
    super(`API ${status}`);
    this.status = status;
    this.body = body;
  }
}

async function req<T>(path: string, opts: { method?: string; body?: unknown } = {}): Promise<T> {
  const init: RequestInit = {
    method: opts.method ?? 'GET',
    credentials: 'same-origin',
  };
  if (opts.body !== undefined) {
    init.headers = { 'content-type': 'application/json' };
    init.body = JSON.stringify(opts.body);
  }
  const res = await fetch(path, init);
  let body: unknown = undefined;
  try {
    body = await res.json();
  } catch {
    /* 空响应体 */
  }
  if (!res.ok) throw new ApiError(res.status, body);
  return body as T;
}

export const api = {
  login: (password: string) => req<{ ok: boolean }>('/admin/api/login', { method: 'POST', body: { password } }),
  logout: () => req<{ ok: boolean }>('/admin/api/logout', { method: 'POST', body: {} }),
  me: () => req<{ ok: boolean }>('/admin/api/me'),

  threads: () => req<{ threads: ThreadSummary[] }>('/admin/api/threads'),
  thread: (id: string) => req<ThreadDetail>(`/admin/api/threads/${id}`),
  /** 删除会话；该会话下挂起的请求会按超时收尾，调用方收到协议错误码 */
  deleteThread: (id: string) =>
    req<{ ok: boolean; failedJobs: number }>(`/admin/api/threads/${id}`, { method: 'DELETE' }),
  /** 批量删除会话；单次最多 500 条 */
  deleteThreads: (ids: string[]) =>
    req<{ ok: boolean; deleted: number; failedJobs: number }>('/admin/api/threads', {
      method: 'DELETE',
      body: { ids },
    }),
  /** 拉黑 / 解除拉黑会话；被拉黑会话在网关入口直接收到 403 */
  blacklistThread: (id: string, blacklisted: boolean) =>
    req<{ ok: boolean; blacklisted: boolean }>(`/admin/api/threads/${id}/blacklist`, {
      method: 'POST',
      body: { blacklisted },
    }),

  reply: (jobId: string, content: string) =>
    req<{ ok: boolean; delivered: boolean }>(`/admin/api/jobs/${jobId}/reply`, { method: 'POST', body: { content } }),
  ignore: (jobId: string) => req<{ ok: boolean }>(`/admin/api/jobs/${jobId}/ignore`, { method: 'POST', body: {} }),
  /** 用指定厂商直接回复挂起请求；上游失败会抛 ApiError（502） */
  forwardJob: (jobId: string, providerId: string) =>
    req<{ ok: boolean; delivered: boolean; content: string; providerName: string }>(
      `/admin/api/jobs/${jobId}/forward`,
      { method: 'POST', body: { providerId } },
    ),

  /** 请求监控：分页 + 状态/协议/关键词/时间范围过滤 */
  requests: (params: {
    limit: number;
    offset: number;
    status?: string;
    protocol?: string;
    q?: string;
    from?: number;
    to?: number;
  }) => {
    const qs = new URLSearchParams();
    qs.set('limit', String(params.limit));
    qs.set('offset', String(params.offset));
    if (params.status) qs.set('status', params.status);
    if (params.protocol) qs.set('protocol', params.protocol);
    if (params.q) qs.set('q', params.q);
    if (params.from) qs.set('from', String(params.from));
    if (params.to) qs.set('to', String(params.to));
    return req<RequestPage>(`/admin/api/requests?${qs.toString()}`);
  },
  request: (id: string) => req<RequestDetail>(`/admin/api/requests/${id}`),

  /** 用量统计：汇总 + 按天时序 + 按模型/厂商/来源分组 */
  usage: (params: { from?: number; to?: number } = {}) => {
    const qs = new URLSearchParams();
    if (params.from) qs.set('from', String(params.from));
    if (params.to) qs.set('to', String(params.to));
    const query = qs.toString();
    return req<UsageResponse>(`/admin/api/usage${query ? `?${query}` : ''}`);
  },

  /** 模型单价表（每百万 token） */
  modelPrices: () => req<{ prices: ModelPrice[] }>('/admin/api/model-prices'),
  createModelPrice: (input: ModelPriceInput) =>
    req<{ price: ModelPrice }>('/admin/api/model-prices', { method: 'POST', body: input }),
  updateModelPrice: (id: string, patch: Partial<ModelPriceInput>) =>
    req<{ price: ModelPrice }>(`/admin/api/model-prices/${id}`, { method: 'PATCH', body: patch }),
  deleteModelPrice: (id: string) =>
    req<{ ok: boolean }>(`/admin/api/model-prices/${id}`, { method: 'DELETE' }),

  settings: () => req<SettingsInfo>('/admin/api/settings'),
  patchSettings: (patch: {
    defaultTimeoutMs?: number;
    modelName?: string;
    adminPassword?: string;
    currentPassword?: string;
    autoForwardEnabled?: boolean;
    autoForwardProviderId?: string;
    sensitiveWords?: string;
    sensitiveFilterEnabled?: boolean;
  }) => req<SettingsInfo & { ok: boolean }>('/admin/api/settings', { method: 'PATCH', body: patch }),
  /** 重新生成全局调用方 Key；旧 Key 立即失效 */
  regenerateCallerKey: () => req<{ callerKey: string }>('/admin/api/caller-key', { method: 'POST', body: {} }),

  providers: () => req<{ providers: ProviderInfo[] }>('/admin/api/providers'),
  createProvider: (input: ProviderInput) =>
    req<{ provider: ProviderInfo }>('/admin/api/providers', { method: 'POST', body: input }),
  updateProvider: (id: string, patch: Partial<ProviderInput>) =>
    req<{ provider: ProviderInfo }>(`/admin/api/providers/${id}`, { method: 'PATCH', body: patch }),
  deleteProvider: (id: string) => req<{ ok: boolean }>(`/admin/api/providers/${id}`, { method: 'DELETE' }),
  testProvider: (id: string) =>
    req<{ ok: boolean; latencyMs: number; preview: string }>(`/admin/api/providers/${id}/test`, {
      method: 'POST',
      body: {},
    }),
};
