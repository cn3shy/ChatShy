import type { PendingJob } from '../core/jobs.js';
import {
  ProviderError,
  callProvider,
  getProvider,
  type Provider,
} from '../core/providers.js';
import { getAutoForwardProviderId, isAutoForwardEnabled } from '../core/settings.js';
import type { Db } from '../db/db.js';
import { parseAnthropicRequest } from '../protocol/anthropic.js';
import { parseOpenAIRequest } from '../protocol/openai.js';
import type { Prompt } from '../protocol/types.js';
import type { ServerContext } from './app.js';

/**
 * 第三方厂商转发编排。
 *
 * 手动（操作台按钮）与自动（全局开关）共用同一条路径，并共用**按 Job 的单飞**：
 * 同一请求最多只有一次上游调用在途。这也是自动转发与人工回复之间的护栏——
 * 谁先让 queue.reply 成功谁赢，另一边的结果直接丢弃，不会出现两条回复。
 *
 * 单飞表用 WeakMap 以 PendingJob 对象为键：Job 结束后对象自然可回收，测试里
 * 多个服务实例也不会共享状态。
 */

interface InFlight {
  controller: AbortController;
  providerName: string;
  startedAt: number;
}

const inFlight = new WeakMap<PendingJob, InFlight>();

export type ForwardResult =
  | { kind: 'replied'; providerName: string; content: string }
  | { kind: 'in_progress' }
  | { kind: 'provider_not_found' }
  | { kind: 'bad_request'; message: string }
  | { kind: 'job_ended'; providerName: string; status: string }
  | { kind: 'upstream_error'; providerName: string; message: string };

/** 从 jobs.request_json 还原调用方原始请求。比 thread 历史更准确：
 *  - thread 里有 console 补发消息与 system 展示行；
 *  - thread 可能已被后续请求推进。
 */
export function promptFromJob(
  db: Db,
  jobId: string,
): { ok: true; prompt: Prompt } | { ok: false; message: string } {
  const row = db.prepare('SELECT protocol, request_json FROM jobs WHERE id = ?').get(jobId) as
    | { protocol: string; request_json: string }
    | undefined;
  if (row === undefined) return { ok: false, message: '请求记录不存在' };

  let body: unknown;
  try {
    body = JSON.parse(row.request_json);
  } catch {
    return { ok: false, message: '请求记录已损坏，无法还原这次对话' };
  }

  const parsed = row.protocol === 'anthropic' ? parseAnthropicRequest(body) : parseOpenAIRequest(body);
  if (!parsed.ok) return { ok: false, message: `无法还原请求内容：${parsed.message}` };
  return { ok: true, prompt: parsed.value.prompt };
}

/** 失败原因落库。会话可能已被删除（UPDATE 命中 0 行），静默即可。 */
function recordForwardError(db: Db, job: PendingJob, provider: Provider | null, message: string): void {
  try {
    db.prepare('UPDATE jobs SET forward_error = ?, forward_error_at = ?, provider_id = ? WHERE id = ?').run(
      message,
      Date.now(),
      provider?.id ?? null,
      job.id,
    );
  } catch {
    /* 行已被级联删除 */
  }
}

function clearForwardError(db: Db, jobId: string, provider: Provider): void {
  try {
    db.prepare("UPDATE jobs SET forward_error = NULL, forward_error_at = NULL, provider_id = ? WHERE id = ?").run(
      provider.id,
      jobId,
    );
  } catch {
    /* ignore */
  }
}

/**
 * 执行一次转发。调用前必须已完成单飞占位（runForward 内部同步占位，
 * 因此从调用到 fetch 之间没有让第二个转发插队的窗口）。
 */
async function runForward(
  ctx: ServerContext,
  job: PendingJob,
  provider: Provider,
  prompt: Prompt,
  source: 'auto' | 'manual',
): Promise<ForwardResult> {
  if (inFlight.has(job)) return { kind: 'in_progress' };

  const controller = new AbortController();
  inFlight.set(job, { controller, providerName: provider.name, startedAt: Date.now() });

  let jobEnded = false;
  // Job 一旦以任何方式结束（超时/被取代/断连/人工抢先回复/删除会话），
  // 立刻中止上游，避免白烧 token。我们自己的成功回复也会触发这里，此时 fetch 已完成，无副作用。
  void job.wait().then(() => {
    jobEnded = true;
    controller.abort();
  });

  try {
    const content = await callProvider(provider, prompt, { signal: controller.signal });
    if (jobEnded || job.status !== 'pending') {
      return { kind: 'job_ended', providerName: provider.name, status: job.status };
    }
    // 记录审计字段要在 reply 之前：respond 之后 Job 可能立刻被清理
    try {
      ctx.db.prepare('UPDATE jobs SET provider_id = ? WHERE id = ?').run(provider.id, job.id);
    } catch {
      /* ignore */
    }
    if (ctx.queue.reply(job.id, content, provider.name)) {
      clearForwardError(ctx.db, job.id, provider);
      return { kind: 'replied', providerName: provider.name, content };
    }
    // 上游返回前 Job 刚好结束：丢弃结果，不落库
    return { kind: 'job_ended', providerName: provider.name, status: job.status };
  } catch (err) {
    // 因 Job 结束而中止，不算上游故障
    if (jobEnded || job.status !== 'pending') {
      return { kind: 'job_ended', providerName: provider.name, status: job.status };
    }
    if (err instanceof ProviderError && err.code === 'upstream_aborted') {
      return { kind: 'job_ended', providerName: provider.name, status: job.status };
    }
    const message = err instanceof ProviderError ? err.message : '转发失败：未知错误';
    recordForwardError(ctx.db, job, provider, message);
    if (source === 'auto') {
      // 自动模式保持 pending，操作台据此知道"为什么这条请求还挂着"
      ctx.hub.broadcast({
        type: 'job.forward_failed',
        jobId: job.id,
        threadId: job.threadId,
        message: message.length <= 200 ? message : `${message.slice(0, 200)}…`,
      });
    }
    return { kind: 'upstream_error', providerName: provider.name, message };
  } finally {
    inFlight.delete(job);
  }
}

/** 操作台手动转发。调用前调用方应确认 job 仍在队列里。 */
export async function forwardPendingJob(
  ctx: ServerContext,
  job: PendingJob,
  providerId: string,
): Promise<ForwardResult> {
  if (inFlight.has(job)) return { kind: 'in_progress' };
  const provider = getProvider(ctx.db, providerId);
  if (provider === null) return { kind: 'provider_not_found' };

  const prompt = promptFromJob(ctx.db, job.id);
  if (!prompt.ok) return { kind: 'bad_request', message: prompt.message };

  return runForward(ctx, job, provider, prompt.prompt, 'manual');
}

/**
 * 自动转发入口：服务端在 Job 创建后调用，不依赖操作台在线。
 * 任何失败都只记录/广播，Job 保持 pending 等人工或超时。
 */
export function maybeAutoForward(ctx: ServerContext, job: PendingJob, prompt: Prompt): void {
  let providerId: string;
  let enabled: boolean;
  try {
    enabled = isAutoForwardEnabled(ctx.db);
    providerId = getAutoForwardProviderId(ctx.db);
  } catch {
    return;
  }
  if (!enabled) return;

  const provider = providerId === '' ? null : getProvider(ctx.db, providerId);
  if (provider === null) {
    const message = '自动转发的厂商配置不存在，请在控制台重新选择';
    recordForwardError(ctx.db, job, null, message);
    ctx.hub.broadcast({ type: 'job.forward_failed', jobId: job.id, threadId: job.threadId, message });
    return;
  }

  void runForward(ctx, job, provider, prompt, 'auto').catch(() => {
    /* runForward 内部已兜底；这里只防御未预期的异常，避免 unhandled rejection */
  });
}
