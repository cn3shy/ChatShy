import type { SettingsInfo, ThreadDetail, ThreadSummary } from './types';

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

  reply: (jobId: string, content: string) =>
    req<{ ok: boolean; delivered: boolean }>(`/admin/api/jobs/${jobId}/reply`, { method: 'POST', body: { content } }),
  ignore: (jobId: string) => req<{ ok: boolean }>(`/admin/api/jobs/${jobId}/ignore`, { method: 'POST', body: {} }),

  settings: () => req<SettingsInfo>('/admin/api/settings'),
  patchSettings: (patch: { defaultTimeoutMs?: number; modelName?: string; adminPassword?: string; currentPassword?: string }) =>
    req<{ ok: boolean; defaultTimeoutMs: number; modelName: string }>('/admin/api/settings', { method: 'PATCH', body: patch }),
  /** 重新生成全局调用方 Key；旧 Key 立即失效 */
  regenerateCallerKey: () => req<{ callerKey: string }>('/admin/api/caller-key', { method: 'POST', body: {} }),
};
