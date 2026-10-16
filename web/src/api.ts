import type { KeyInfo, SettingsInfo, ThreadDetail, ThreadSummary } from './types';

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
  createThread: (title: string) => req<{ thread: ThreadDetail['thread'] }>('/admin/api/threads', { method: 'POST', body: { title } }),
  postMessage: (threadId: string, role: 'user' | 'assistant', content: string) =>
    req<{ message: unknown }>(`/admin/api/threads/${threadId}/messages`, { method: 'POST', body: { role, content } }),

  reply: (jobId: string, content: string) =>
    req<{ ok: boolean; delivered: boolean }>(`/admin/api/jobs/${jobId}/reply`, { method: 'POST', body: { content } }),
  ignore: (jobId: string) => req<{ ok: boolean }>(`/admin/api/jobs/${jobId}/ignore`, { method: 'POST', body: {} }),

  keys: () => req<{ keys: KeyInfo[] }>('/admin/api/keys'),
  createKey: (body: { name?: string; timeoutMs?: number; dailyLimit?: number; maxPending?: number }) =>
    req<{ key: KeyInfo; plaintext: string }>('/admin/api/keys', { method: 'POST', body }),
  updateKey: (id: string, patch: Partial<Pick<KeyInfo, 'name' | 'enabled' | 'timeoutMs' | 'dailyLimit' | 'maxPending'>>) =>
    req<{ key: KeyInfo }>(`/admin/api/keys/${id}`, { method: 'PATCH', body: patch }),
  deleteKey: (id: string) => req<{ ok: boolean }>(`/admin/api/keys/${id}`, { method: 'DELETE' }),

  settings: () => req<SettingsInfo>('/admin/api/settings'),
  patchSettings: (patch: { defaultTimeoutMs?: number; modelName?: string; adminPassword?: string; currentPassword?: string }) =>
    req<SettingsInfo & { ok: boolean }>('/admin/api/settings', { method: 'PATCH', body: patch }),
};
