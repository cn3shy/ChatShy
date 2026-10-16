import { computed, reactive } from 'vue';
import { ApiError, api } from './api';
import { notifyNewRequest, updateTitleBadge } from './notify';
import type { SettingsInfo, ThreadDetail, ThreadSummary } from './types';

export const state = reactive({
  booting: true,
  authed: false,
  loginError: '',
  threads: [] as ThreadSummary[],
  selectedId: null as string | null,
  detail: null as ThreadDetail | null,
  settings: null as SettingsInfo | null,
  showKeys: false,
  showSettings: false,
  /** 每秒推进的时钟，用于倒计时 */
  now: Date.now(),
});

setInterval(() => {
  state.now = Date.now();
}, 1000);

export const pendingTotal = computed(() => state.threads.reduce((n, t) => n + t.pendingCount, 0));

export function remainingSeconds(deadlineAt: number, now: number): number {
  return Math.max(0, Math.ceil((deadlineAt - now) / 1000));
}

export async function bootstrap(): Promise<void> {
  try {
    await api.me();
    state.authed = true;
    await Promise.all([refreshThreads(), refreshSettings()]);
    connectEvents();
  } catch {
    state.authed = false;
  } finally {
    state.booting = false;
  }
}

export async function login(password: string): Promise<void> {
  state.loginError = '';
  try {
    await api.login(password);
    state.authed = true;
    await Promise.all([refreshThreads(), refreshSettings()]);
    connectEvents();
  } catch (err) {
    state.loginError =
      err instanceof ApiError && err.status === 429 ? '尝试次数过多，请 1 分钟后再试' : '密码错误';
  }
}

export async function logout(): Promise<void> {
  try {
    await api.logout();
  } catch {
    /* ignore */
  }
  disconnectEvents();
  state.authed = false;
  state.threads = [];
  state.detail = null;
  state.selectedId = null;
}

export async function refreshThreads(): Promise<void> {
  try {
    const { threads } = await api.threads();
    state.threads = threads;
    updateTitleBadge(pendingTotal.value);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) handleSessionExpired();
  }
}

export async function refreshSettings(): Promise<void> {
  try {
    state.settings = await api.settings();
  } catch {
    /* ignore */
  }
}

export async function selectThread(id: string): Promise<void> {
  state.selectedId = id;
  await refreshDetail();
}

export async function refreshDetail(): Promise<void> {
  if (state.selectedId === null) {
    state.detail = null;
    return;
  }
  try {
    state.detail = await api.thread(state.selectedId);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      state.selectedId = null;
      state.detail = null;
    }
  }
}

/** 回复当前会话最新的挂起请求 */
export async function reply(content: string): Promise<{ delivered: boolean } | null> {
  const job = state.detail?.pendingJobs.at(-1);
  if (job === undefined) return null;
  const res = await api.reply(job.id, content);
  await Promise.all([refreshDetail(), refreshThreads()]);
  return { delivered: res.delivered };
}

export async function ignoreJob(jobId: string): Promise<void> {
  await api.ignore(jobId);
  await Promise.all([refreshDetail(), refreshThreads()]);
}

export async function sendConsoleMessage(role: 'user' | 'assistant', content: string): Promise<void> {
  const id = state.selectedId;
  if (id === null) return;
  await api.postMessage(id, role, content);
  await Promise.all([refreshDetail(), refreshThreads()]);
}

export async function newConsoleThread(): Promise<void> {
  const { thread } = await api.createThread('控制台测试会话');
  await refreshThreads();
  await selectThread(thread.id);
}

export async function updateSettings(patch: {
  defaultTimeoutMs?: number;
  modelName?: string;
  adminPassword?: string;
  currentPassword?: string;
}): Promise<{ ok: boolean; error?: string }> {
  try {
    const res = await api.patchSettings(patch);
    state.settings = { defaultTimeoutMs: res.defaultTimeoutMs, modelName: res.modelName };
    return { ok: true };
  } catch (err) {
    if (err instanceof ApiError) {
      const code = (err.body as { error?: string } | undefined)?.error ?? '';
      const messages: Record<string, string> = {
        wrong_current_password: '当前密码不正确',
        password_too_short: '新密码至少 6 位',
        invalid_timeout: '超时必须是正数（毫秒）',
        invalid_model_name: '模型名不能为空',
      };
      return { ok: false, error: messages[code] ?? '保存失败' };
    }
    return { ok: false, error: '保存失败' };
  }
}

// ---- SSE 事件 ----

let eventSource: EventSource | null = null;

function connectEvents(): void {
  if (eventSource !== null) return;
  const es = new EventSource('/admin/api/events');
  eventSource = es;
  es.onmessage = (ev: MessageEvent<string>) => {
    let data: { type?: string; threadId?: string };
    try {
      data = JSON.parse(ev.data) as { type?: string; threadId?: string };
    } catch {
      return;
    }
    if (data.type === 'hello' || data.type === 'ping') return;
    void handleEvent(data);
  };
  es.onerror = () => {
    // 断线由 EventSource 自动重连；会话过期时重连会一直 401，交给下一次 API 调用兜底
  };
}

function disconnectEvents(): void {
  if (eventSource !== null) {
    eventSource.close();
    eventSource = null;
  }
}

async function handleEvent(data: { type?: string; threadId?: string }): Promise<void> {
  await refreshThreads();
  if (data.type === 'job.created') {
    const t = state.threads.find((x) => x.id === data.threadId);
    notifyNewRequest(
      'ChatShy · 新的待回复请求',
      t !== undefined ? `${t.keyName ?? '控制台'}：${t.lastMessage.slice(0, 60)}` : '有新的待回复请求',
    );
  }
  if (state.selectedId !== null && data.threadId === state.selectedId) {
    await refreshDetail();
  }
}

function handleSessionExpired(): void {
  disconnectEvents();
  state.authed = false;
  state.loginError = '会话已过期，请重新登录';
}
