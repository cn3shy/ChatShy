import { computed, reactive } from 'vue';
import { ApiError, api } from './api';
import { notifyNewRequest, updateTitleBadge } from './notify';
import type { ProviderInfo, ProviderInput, SettingsInfo, ThreadDetail, ThreadSummary } from './types';

export const state = reactive({
  booting: true,
  authed: false,
  loginError: '',
  threads: [] as ThreadSummary[],
  selectedId: null as string | null,
  detail: null as ThreadDetail | null,
  settings: null as SettingsInfo | null,
  providers: [] as ProviderInfo[],
  showSettings: false,
  /** 自动转发失败的最近提示（侧边栏短暂展示） */
  forwardError: '',
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
    await Promise.all([refreshThreads(), refreshSettings(), refreshProviders()]);
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
    await Promise.all([refreshThreads(), refreshSettings(), refreshProviders()]);
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

/**
 * 刷新竞态护栏：自动转发会让 job.created / job.replied 几乎连发，
 * 两次请求的响应可能乱序返回。只接受最后一次发出的请求结果。
 */
let threadsSeq = 0;

export async function refreshThreads(): Promise<void> {
  const seq = ++threadsSeq;
  try {
    const { threads } = await api.threads();
    if (seq !== threadsSeq) return;
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

export async function refreshProviders(): Promise<void> {
  try {
    const { providers } = await api.providers();
    state.providers = providers;
  } catch {
    /* ignore */
  }
}

export async function selectThread(id: string): Promise<void> {
  state.selectedId = id;
  await refreshDetail();
}

let detailSeq = 0;

export async function refreshDetail(): Promise<void> {
  if (state.selectedId === null) {
    state.detail = null;
    return;
  }
  const id = state.selectedId;
  const seq = ++detailSeq;
  try {
    const detail = await api.thread(id);
    if (seq !== detailSeq || state.selectedId !== id) return;
    state.detail = detail;
  } catch (err) {
    if (seq !== detailSeq) return;
    if (err instanceof ApiError && err.status === 404) {
      state.selectedId = null;
      state.detail = null;
    }
  }
}

/** 删除会话（不可恢复）。删掉的正是当前选中会话时，顺手把右侧清空。 */
export async function deleteThread(id: string): Promise<void> {
  await api.deleteThread(id);
  if (state.selectedId === id) {
    state.selectedId = null;
    state.detail = null;
  }
  await refreshThreads();
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

/** 重新生成全局调用方 Key；旧 Key 立即失效，所有调用方都要改配置 */
export async function regenerateKey(): Promise<string> {
  const { callerKey } = await api.regenerateCallerKey();
  if (state.settings !== null) state.settings.callerKey = callerKey;
  return callerKey;
}

function applySettings(s: SettingsInfo): void {
  state.settings = {
    defaultTimeoutMs: s.defaultTimeoutMs,
    modelName: s.modelName,
    callerKey: s.callerKey,
    autoForwardEnabled: s.autoForwardEnabled,
    autoForwardProviderId: s.autoForwardProviderId,
  };
}

/** 把后端错误体翻译成可展示的中文；服务端给了 message 就直接用 */
function apiErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    const body = err.body as { error?: string; message?: string } | undefined;
    if (typeof body?.message === 'string' && body.message !== '') return body.message;
    const messages: Record<string, string> = {
      wrong_current_password: '当前密码不正确',
      password_too_short: '新密码至少 6 位',
      invalid_timeout: '超时必须是正数（毫秒）',
      invalid_model_name: '模型名不能为空',
      invalid_auto_forward: '自动转发设置不合法',
      provider_exists: '已存在同名厂商',
      invalid_name: '厂商名称不能为空',
      invalid_base_url: '接口地址不合法',
      invalid_model: '模型名不能为空',
      invalid_api_key: 'API Key 不能为空',
      provider_in_use: '该厂商正被自动转发使用，请先关闭开关或切换厂商',
      provider_not_found: '厂商不存在，请刷新后重试',
      forward_in_progress: '这个请求正在转发中，请稍候',
      job_ended: '请求已结束（已超时、被取代或已被回复）',
      upstream_error: '上游调用失败',
      not_found: '对象不存在',
    };
    const code = body?.error ?? '';
    if (messages[code] !== undefined) return messages[code]!;
  }
  return fallback;
}

export async function updateSettings(patch: {
  defaultTimeoutMs?: number;
  modelName?: string;
  adminPassword?: string;
  currentPassword?: string;
}): Promise<{ ok: boolean; error?: string }> {
  try {
    const res = await api.patchSettings(patch);
    applySettings(res);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: apiErrorMessage(err, '保存失败') };
  }
}

// ---- 厂商配置与转发 ----

export async function saveProvider(
  input: ProviderInput,
  id?: string,
): Promise<{ ok: boolean; error?: string }> {
  try {
    if (id === undefined) await api.createProvider(input);
    else await api.updateProvider(id, input);
    await Promise.all([refreshProviders(), refreshSettings()]);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: apiErrorMessage(err, '保存失败') };
  }
}

export async function removeProvider(id: string): Promise<{ ok: boolean; error?: string }> {
  try {
    await api.deleteProvider(id);
    await Promise.all([refreshProviders(), refreshSettings()]);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: apiErrorMessage(err, '删除失败') };
  }
}

export async function testProvider(
  id: string,
): Promise<{ ok: boolean; latencyMs?: number; preview?: string; error?: string }> {
  try {
    const res = await api.testProvider(id);
    return { ok: true, latencyMs: res.latencyMs, preview: res.preview };
  } catch (err) {
    return { ok: false, error: apiErrorMessage(err, '测试失败') };
  }
}

/** 用指定厂商直接回复某个挂起请求；失败时 Job 仍在 pending，可人工接管 */
export async function forwardJob(
  jobId: string,
  providerId: string,
): Promise<{ ok: boolean; providerName?: string; error?: string }> {
  try {
    const res = await api.forwardJob(jobId, providerId);
    await Promise.all([refreshDetail(), refreshThreads()]);
    return { ok: true, providerName: res.providerName };
  } catch (err) {
    // 失败原因（forwardError）也会落库，刷新后待回复条能显示
    await Promise.all([refreshDetail(), refreshThreads()]);
    return { ok: false, error: apiErrorMessage(err, '转发失败') };
  }
}

/** 切换自动转发开关/厂商；服务端持久化，操作台关掉也继续生效 */
export async function setAutoForward(
  enabled: boolean,
  providerId?: string,
): Promise<{ ok: boolean; error?: string }> {
  try {
    const res = await api.patchSettings({
      autoForwardEnabled: enabled,
      ...(providerId === undefined ? {} : { autoForwardProviderId: providerId }),
    });
    applySettings(res);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: apiErrorMessage(err, '保存失败') };
  }
}

// ---- SSE 事件 ----

let eventSource: EventSource | null = null;

function connectEvents(): void {
  if (eventSource !== null) return;
  const es = new EventSource('/admin/api/events');
  eventSource = es;
  es.onmessage = (ev: MessageEvent<string>) => {
    let data: { type?: string; threadId?: string; message?: string };
    try {
      data = JSON.parse(ev.data) as { type?: string; threadId?: string; message?: string };
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

let forwardErrorTimer: ReturnType<typeof setTimeout> | null = null;

function showForwardError(message: string): void {
  state.forwardError = message;
  if (forwardErrorTimer !== null) clearTimeout(forwardErrorTimer);
  forwardErrorTimer = setTimeout(() => {
    state.forwardError = '';
  }, 8000);
}

async function handleEvent(data: { type?: string; threadId?: string; message?: string }): Promise<void> {
  if (data.type === 'settings.updated') {
    await refreshSettings();
    return;
  }
  if (data.type === 'providers.updated') {
    await refreshProviders();
    return;
  }

  await refreshThreads();

  if (data.type === 'job.created') {
    // 自动转发的请求通常几百毫秒内就有结果，弹"待回复"通知只会打扰人
    if (!(state.settings?.autoForwardEnabled ?? false)) {
      const t = state.threads.find((x) => x.id === data.threadId);
      notifyNewRequest(
        'ChatShy · 新的待回复请求',
        t !== undefined
          ? `${t.sourceClient || '未知来源'}（${t.sourceIp}）：${t.lastMessage.slice(0, 60)}`
          : '有新的待回复请求',
      );
    }
  }
  if (data.type === 'job.forward_failed') {
    const message = data.message ?? '请求已留在待回复列表，请人工处理';
    notifyNewRequest('ChatShy · 自动转发失败', message);
    showForwardError(message);
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
