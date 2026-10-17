import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import {
  ProviderError,
  ProviderValidationError,
  callProvider,
  createProvider,
  deleteProvider,
  getProvider,
  listProviders,
  toPublicProvider,
  updateProvider,
  type ProviderPatch,
} from '../../core/providers.js';
import {
  SETTING_DEFAULT_TIMEOUT_MS,
  SETTING_MODEL_NAME,
  getAutoForwardProviderId,
  getCallerKey,
  getDefaultTimeoutMs,
  getModelName,
  isAutoForwardEnabled,
  regenerateCallerKey,
  setAdminPassword,
  setAutoForwardEnabled,
  setAutoForwardProviderId,
  setSetting,
  verifyAdminPassword,
} from '../../core/settings.js';
import { appendMessage, deleteThread, getThread, listThreadMessages } from '../../core/threads.js';
import type { ServerContext } from '../app.js';
import { forwardPendingJob } from '../forwarder.js';
import { SESSION_COOKIE } from '../session.js';
import { SSE_HEADERS } from '../serve.js';

interface ThreadListRow {
  id: string;
  title: string;
  protocol: string;
  model: string;
  created_at: number;
  updated_at: number;
  source_ip: string;
  source_ua: string;
  source_client: string;
  source_fingerprint: string;
  last_content: string | null;
  pending_count: number;
  pending_job_id: string | null;
  pending_deadline: number | null;
}

interface JobRow {
  id: string;
  thread_id: string;
  status: string;
  protocol: string;
  mode: string;
  deadline_at: number;
  via: string | null;
  forward_error: string | null;
  forward_error_at: number | null;
}

function numberOrNull(v: unknown): number | null | undefined {
  if (v === null) return null;
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return Math.floor(v);
  return undefined;
}

/** 当前设置的管理端形状。PATCH 也返回同一形状，前端可以整体替换，不会漏字段。 */
function settingsPayload(ctx: ServerContext): {
  defaultTimeoutMs: number;
  modelName: string;
  callerKey: string;
  autoForwardEnabled: boolean;
  autoForwardProviderId: string;
} {
  return {
    defaultTimeoutMs: getDefaultTimeoutMs(ctx.db),
    modelName: getModelName(ctx.db),
    callerKey: getCallerKey(ctx.db) ?? '',
    autoForwardEnabled: isAutoForwardEnabled(ctx.db),
    autoForwardProviderId: getAutoForwardProviderId(ctx.db),
  };
}

function providerErrorReply(reply: FastifyReply, err: ProviderValidationError): FastifyReply {
  return reply
    .code(err.code === 'provider_exists' ? 409 : 400)
    .send({ error: err.code, message: err.message });
}

export function registerAdminRoutes(app: FastifyInstance, ctx: ServerContext): void {
  const requireAuth = async (req: FastifyRequest, reply: FastifyReply): Promise<void> => {
    const token = req.cookies[SESSION_COOKIE];
    if (typeof token !== 'string' || !ctx.sessions.verify(token)) {
      reply.code(401).send({ error: 'unauthorized' });
    }
  };

  // ---- 登录 ----

  app.post('/admin/api/login', async (req, reply) => {
    const ip = req.ip;
    if (ctx.sessions.isLocked(ip)) return reply.code(429).send({ error: 'too_many_attempts' });
    const body = (req.body ?? {}) as { password?: unknown };
    const password = typeof body.password === 'string' ? body.password : '';
    if (!verifyAdminPassword(ctx.db, password)) {
      ctx.sessions.registerFailure(ip);
      return reply.code(401).send({ error: 'invalid_password' });
    }
    ctx.sessions.resetFailures(ip);
    const token = ctx.sessions.create();
    reply.setCookie(SESSION_COOKIE, token, {
      path: '/',
      httpOnly: true,
      sameSite: 'lax',
      maxAge: 7 * 24 * 3600,
      secure: req.protocol === 'https',
    });
    return { ok: true };
  });

  app.post('/admin/api/logout', { preHandler: requireAuth }, async (req, reply) => {
    const token = req.cookies[SESSION_COOKIE];
    if (typeof token === 'string') ctx.sessions.destroy(token);
    reply.clearCookie(SESSION_COOKIE, { path: '/' });
    return { ok: true };
  });

  app.get('/admin/api/me', { preHandler: requireAuth }, async () => ({ ok: true }));

  // ---- 实时事件（SSE） ----

  app.get('/admin/api/events', { preHandler: requireAuth }, (req, reply) => {
    reply.hijack();
    const raw = reply.raw;
    raw.writeHead(200, SSE_HEADERS);
    raw.write('data: {"type":"hello"}\n\n');
    const remove = ctx.hub.addClient({ write: (chunk) => raw.write(chunk) });
    raw.on('close', remove);
    raw.on('error', remove);
    return reply;
  });

  // ---- 会话列表 / 详情 ----

  app.get('/admin/api/threads', { preHandler: requireAuth }, async () => {
    const rows = ctx.db
      .prepare(
        `SELECT t.id, t.title, t.protocol, t.model, t.created_at, t.updated_at,
                t.source_ip, t.source_ua, t.source_client, t.source_fingerprint,
                (SELECT content FROM messages m WHERE m.thread_id = t.id ORDER BY m.created_at DESC, m.rowid DESC LIMIT 1) AS last_content,
                (SELECT COUNT(*) FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending') AS pending_count,
                (SELECT j.id FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending' ORDER BY j.created_at DESC LIMIT 1) AS pending_job_id,
                (SELECT j.deadline_at FROM jobs j WHERE j.thread_id = t.id AND j.status = 'pending' ORDER BY j.created_at DESC LIMIT 1) AS pending_deadline
         FROM threads t
         ORDER BY (pending_count > 0) DESC, pending_deadline ASC, t.updated_at DESC
         LIMIT 200`,
      )
      .all() as ThreadListRow[];
    return {
      threads: rows.map((r) => ({
        id: r.id,
        title: r.title,
        protocol: r.protocol,
        model: r.model,
        sourceClient: r.source_client,
        sourceIp: r.source_ip,
        sourceUa: r.source_ua,
        createdAt: r.created_at,
        updatedAt: r.updated_at,
        lastMessage: r.last_content ?? '',
        pendingCount: r.pending_count,
        pendingJobId: r.pending_job_id,
        pendingDeadlineAt: r.pending_deadline,
      })),
    };
  });

  app.get('/admin/api/threads/:id', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const thread = getThread(ctx.db, id);
    if (thread === null) return reply.code(404).send({ error: 'not_found' });

    const jobRows = ctx.db
      .prepare('SELECT id, status, via FROM jobs WHERE thread_id = ?')
      .all(id) as { id: string; status: string; via: string | null }[];
    const jobById = new Map(jobRows.map((j) => [j.id, j]));

    const messages = listThreadMessages(ctx.db, id).map((m) => {
      const job = m.jobId === null ? undefined : jobById.get(m.jobId);
      return {
        id: m.id,
        role: m.role,
        content: m.content,
        source: m.source,
        jobId: m.jobId,
        createdAt: m.createdAt,
        delivered: m.jobId === null ? true : job?.status === 'replied',
        // 只有 assistant 消息才继承厂商标记：linkLatestUserMessageToJob 也会把
        // 触发请求的 user 消息关联到同一个 job，不能把它误标成 AI 写的。
        via: m.role === 'assistant' && job?.via !== undefined && job.via !== null ? job.via : '',
      };
    });

    const pendingRows = ctx.db
      .prepare(
        `SELECT id, protocol, mode, deadline_at, forward_error, forward_error_at
         FROM jobs WHERE thread_id = ? AND status = 'pending' ORDER BY created_at ASC`,
      )
      .all(id) as JobRow[];

    return {
      thread: {
        id: thread.id,
        title: thread.title,
        protocol: thread.protocol,
        model: thread.model,
        sourceClient: thread.sourceClient,
        sourceIp: thread.sourceIp,
        sourceUa: thread.sourceUa,
        createdAt: thread.createdAt,
        updatedAt: thread.updatedAt,
      },
      messages,
      pendingJobs: pendingRows.map((j) => ({
        id: j.id,
        protocol: j.protocol,
        mode: j.mode,
        deadlineAt: j.deadline_at,
        forwardError: j.forward_error,
        forwardErrorAt: j.forward_error_at,
      })),
    };
  });

  /**
   * 删除会话（消息与请求记录随外键级联删除，不可恢复）。
   *
   * 顺序关键：先收尾挂起请求，再删行。反过来的话，jobs 行会先被级联清掉，
   * queue.finish 里那句 UPDATE ... WHERE id = ? 就落空了；更要紧的是调用方那头
   * 还挂在 HTTP 上——它必须收到一个协议错误码才算有始有终。
   */
  app.delete('/admin/api/threads/:id', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (getThread(ctx.db, id) === null) return reply.code(404).send({ error: 'not_found' });

    const failed = ctx.queue.failForThread(id);
    deleteThread(ctx.db, id);
    // 让其它开着的操作台把这条会话从列表里去掉（前端对任何非 ping 事件都会重拉列表）
    ctx.hub.broadcast({ type: 'thread.deleted', threadId: id });
    return { ok: true, failedJobs: failed };
  });

  // ---- 回复 / 忽略 ----

  app.post('/admin/api/jobs/:id/reply', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as { content?: unknown };
    const content = typeof body.content === 'string' ? body.content : '';
    if (content.trim() === '') return reply.code(400).send({ error: 'empty_content' });

    const row = ctx.db.prepare('SELECT thread_id, status FROM jobs WHERE id = ?').get(id) as
      | { thread_id: string; status: string }
      | undefined;
    if (row === undefined) return reply.code(404).send({ error: 'not_found' });

    // 已经实时吐字给调用方后不能再切人工：半截正文收不回来，拼接人工内容只会得到坏响应。
    // 等这轮流式结束（通常只剩几秒）再回复，或先「忽略」。
    const liveJob = ctx.queue.get(id);
    if (liveJob !== null && liveJob.hasStreamed()) {
      return reply
        .code(409)
        .send({ error: 'forward_in_progress', message: 'AI 正在流式回复，请等它结束或先忽略这个请求' });
    }

    if (ctx.queue.reply(id, content)) {
      // 正常送达：这里**不**落库。queue.reply() 会唤醒正在等待的 serveNonStream/serveStream，
      // 由它们的 onDelivered 回调统一写入——那才是"回复确实发给了调用方"的判定点。
      // 两边都写会让同一条回复入库两遍，界面上看着就是一条消息变成两行。
      return { ok: true, delivered: true };
    }
    if (row.status === 'replied') return reply.code(409).send({ error: 'already_replied' });
    // 已超时/被取代/断连：只入库展示，标注未送达；source=console 不污染 API 转录本匹配
    appendMessage(ctx.db, { threadId: row.thread_id, role: 'assistant', content, source: 'console', jobId: id });
    return { ok: true, delivered: false };
  });

  app.post('/admin/api/jobs/:id/ignore', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const row = ctx.db.prepare('SELECT id FROM jobs WHERE id = ?').get(id) as { id: string } | undefined;
    if (row === undefined) return reply.code(404).send({ error: 'not_found' });
    ctx.queue.ignore(id);
    return { ok: true };
  });

  /**
   * 手动转发：调用选定的第三方厂商，成功后直接把内容回复给调用方。
   * 上游失败时 Job 保持 pending（操作员仍可人工回复），错误同步返回给操作台。
   */
  app.post('/admin/api/jobs/:id/forward', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as { providerId?: unknown };
    const providerId = typeof body.providerId === 'string' ? body.providerId.trim() : '';
    if (providerId === '') return reply.code(400).send({ error: 'invalid_provider' });

    const job = ctx.queue.get(id);
    if (job === null) {
      const row = ctx.db.prepare('SELECT status FROM jobs WHERE id = ?').get(id) as { status: string } | undefined;
      if (row === undefined) return reply.code(404).send({ error: 'not_found' });
      return reply.code(409).send({ error: 'job_ended', status: row.status });
    }

    const result = await forwardPendingJob(ctx, job, providerId);
    switch (result.kind) {
      case 'replied':
        return { ok: true, delivered: true, content: result.content, providerName: result.providerName };
      case 'in_progress':
        return reply.code(409).send({ error: 'forward_in_progress' });
      case 'provider_not_found':
        return reply.code(404).send({ error: 'provider_not_found' });
      case 'bad_request':
        return reply.code(400).send({ error: 'bad_request', message: result.message });
      case 'job_ended':
        return reply.code(409).send({ error: 'job_ended', status: result.status });
      case 'upstream_error':
        return reply
          .code(502)
          .send({ error: 'upstream_error', message: result.message, provider: result.providerName });
    }
  });

  // ---- AI 厂商配置 ----

  app.get('/admin/api/providers', { preHandler: requireAuth }, async () => ({
    providers: listProviders(ctx.db).map(toPublicProvider),
  }));

  app.post('/admin/api/providers', { preHandler: requireAuth }, async (req, reply) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    try {
      const provider = createProvider(ctx.db, {
        name: typeof body.name === 'string' ? body.name : '',
        baseUrl: typeof body.baseUrl === 'string' ? body.baseUrl : '',
        apiKey: typeof body.apiKey === 'string' ? body.apiKey : '',
        model: typeof body.model === 'string' ? body.model : '',
      });
      ctx.hub.broadcast({ type: 'providers.updated' });
      return { provider: toPublicProvider(provider) };
    } catch (err) {
      if (err instanceof ProviderValidationError) return providerErrorReply(reply, err);
      throw err;
    }
  });

  app.patch('/admin/api/providers/:id', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const body = (req.body ?? {}) as Record<string, unknown>;
    const patch: ProviderPatch = {};
    for (const key of ['name', 'baseUrl', 'apiKey', 'model'] as const) {
      if (body[key] === undefined) continue;
      if (typeof body[key] !== 'string') return reply.code(400).send({ error: `invalid_${key}` });
      patch[key] = body[key] as string;
    }
    try {
      const provider = updateProvider(ctx.db, id, patch);
      if (provider === null) return reply.code(404).send({ error: 'not_found' });
      ctx.hub.broadcast({ type: 'providers.updated' });
      return { provider: toPublicProvider(provider) };
    } catch (err) {
      if (err instanceof ProviderValidationError) return providerErrorReply(reply, err);
      throw err;
    }
  });

  app.delete('/admin/api/providers/:id', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (getProvider(ctx.db, id) === null) return reply.code(404).send({ error: 'not_found' });

    // 正被自动转发使用就不让删：否则每个新请求都会失败，操作员却不知道原因
    if (getAutoForwardProviderId(ctx.db) === id) {
      if (isAutoForwardEnabled(ctx.db)) {
        return reply.code(409).send({
          error: 'provider_in_use',
          message: '该厂商正被自动转发使用，请先关闭自动转发或切换厂商',
        });
      }
      setAutoForwardProviderId(ctx.db, '');
      ctx.hub.broadcast({ type: 'settings.updated' });
    }

    deleteProvider(ctx.db, id);
    ctx.hub.broadcast({ type: 'providers.updated' });
    return { ok: true };
  });

  /** 连通性测试：固定小请求，不占用/影响任何挂起 Job */
  app.post('/admin/api/providers/:id/test', { preHandler: requireAuth }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const provider = getProvider(ctx.db, id);
    if (provider === null) return reply.code(404).send({ error: 'not_found' });

    const startedAt = Date.now();
    try {
      const content = await callProvider(
        provider,
        { model: provider.model, messages: [{ role: 'user', content: '只回复 OK' }] },
        { timeoutMs: 20_000 },
      );
      return { ok: true, latencyMs: Date.now() - startedAt, preview: content.slice(0, 200) };
    } catch (err) {
      if (err instanceof ProviderError) {
        return reply.code(502).send({ error: err.code, message: err.message, provider: provider.name });
      }
      throw err;
    }
  });

  // ---- 全局设置 ----

  app.get('/admin/api/settings', { preHandler: requireAuth }, async () => settingsPayload(ctx));

  /** 重新生成全局调用方 Key；旧 Key 立即失效，所有调用方都要改配置 */
  app.post('/admin/api/caller-key', { preHandler: requireAuth }, async () => ({
    callerKey: regenerateCallerKey(ctx.db),
  }));

  app.patch('/admin/api/settings', { preHandler: requireAuth }, async (req, reply) => {
    const body = (req.body ?? {}) as {
      defaultTimeoutMs?: unknown;
      modelName?: unknown;
      adminPassword?: unknown;
      currentPassword?: unknown;
      autoForwardEnabled?: unknown;
      autoForwardProviderId?: unknown;
    };

    // 先全部校验，再统一应用，避免半程失败留下部分改动
    let newTimeout: string | null = null;
    if (body.defaultTimeoutMs !== undefined) {
      const n = numberOrNull(body.defaultTimeoutMs);
      if (n === null || n === undefined) return reply.code(400).send({ error: 'invalid_timeout' });
      newTimeout = String(n);
    }
    let newModel: string | null = null;
    if (body.modelName !== undefined) {
      if (typeof body.modelName !== 'string' || body.modelName.trim() === '') {
        return reply.code(400).send({ error: 'invalid_model_name' });
      }
      newModel = body.modelName.trim();
    }
    let newPassword: string | null = null;
    if (body.adminPassword !== undefined) {
      if (typeof body.adminPassword !== 'string' || body.adminPassword.length < 6) {
        return reply.code(400).send({ error: 'password_too_short' });
      }
      const current = typeof body.currentPassword === 'string' ? body.currentPassword : '';
      if (!verifyAdminPassword(ctx.db, current)) return reply.code(403).send({ error: 'wrong_current_password' });
      newPassword = body.adminPassword;
    }

    let newAutoEnabled: boolean | null = null;
    if (body.autoForwardEnabled !== undefined) {
      if (typeof body.autoForwardEnabled !== 'boolean') {
        return reply.code(400).send({ error: 'invalid_auto_forward', message: '自动转发开关必须是布尔值' });
      }
      newAutoEnabled = body.autoForwardEnabled;
    }
    let newAutoProvider: string | null = null;
    if (body.autoForwardProviderId !== undefined) {
      if (typeof body.autoForwardProviderId !== 'string') {
        return reply.code(400).send({ error: 'invalid_auto_forward', message: '厂商 id 必须是字符串' });
      }
      newAutoProvider = body.autoForwardProviderId.trim();
    }

    // 合并后的状态必须合法：开启自动转发时，厂商必须存在且非空
    const effectiveEnabled = newAutoEnabled ?? isAutoForwardEnabled(ctx.db);
    const effectiveProvider = newAutoProvider ?? getAutoForwardProviderId(ctx.db);
    if (effectiveEnabled) {
      if (effectiveProvider === '') {
        return reply.code(400).send({ error: 'invalid_auto_forward', message: '开启自动转发前请先选择厂商' });
      }
      if (getProvider(ctx.db, effectiveProvider) === null) {
        return reply.code(400).send({ error: 'invalid_auto_forward', message: '所选厂商不存在' });
      }
    }

    if (newTimeout !== null) setSetting(ctx.db, SETTING_DEFAULT_TIMEOUT_MS, newTimeout);
    if (newModel !== null) setSetting(ctx.db, SETTING_MODEL_NAME, newModel);
    if (newPassword !== null) setAdminPassword(ctx.db, newPassword);
    if (newAutoEnabled !== null) setAutoForwardEnabled(ctx.db, newAutoEnabled);
    if (newAutoProvider !== null) setAutoForwardProviderId(ctx.db, newAutoProvider);

    // 多开的操作台需要同步开关/厂商选择；前端收到后重新拉取
    ctx.hub.broadcast({ type: 'settings.updated' });
    return { ok: true, ...settingsPayload(ctx) };
  });
}
