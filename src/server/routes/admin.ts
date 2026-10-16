import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import {
  SETTING_DEFAULT_TIMEOUT_MS,
  SETTING_MODEL_NAME,
  getCallerKey,
  getDefaultTimeoutMs,
  getModelName,
  regenerateCallerKey,
  setAdminPassword,
  setSetting,
  verifyAdminPassword,
} from '../../core/settings.js';
import { appendMessage, getThread, listThreadMessages } from '../../core/threads.js';
import type { ServerContext } from '../app.js';
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
}

function numberOrNull(v: unknown): number | null | undefined {
  if (v === null) return null;
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return Math.floor(v);
  return undefined;
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

    const jobRows = ctx.db.prepare('SELECT id, status FROM jobs WHERE thread_id = ?').all(id) as {
      id: string;
      status: string;
    }[];
    const statusById = new Map(jobRows.map((j) => [j.id, j.status]));

    const messages = listThreadMessages(ctx.db, id).map((m) => ({
      id: m.id,
      role: m.role,
      content: m.content,
      source: m.source,
      jobId: m.jobId,
      createdAt: m.createdAt,
      delivered: m.jobId === null ? true : statusById.get(m.jobId) === 'replied',
    }));

    const pendingRows = ctx.db
      .prepare(
        "SELECT id, protocol, mode, deadline_at FROM jobs WHERE thread_id = ? AND status = 'pending' ORDER BY created_at ASC",
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
      pendingJobs: pendingRows.map((j) => ({ id: j.id, protocol: j.protocol, mode: j.mode, deadlineAt: j.deadline_at })),
    };
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

    if (ctx.queue.reply(id, content)) {
      // 正常送达：属于 API 转录本
      appendMessage(ctx.db, { threadId: row.thread_id, role: 'assistant', content, source: 'api', jobId: id });
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

  // ---- 全局设置 ----

  app.get('/admin/api/settings', { preHandler: requireAuth }, async () => ({
    defaultTimeoutMs: getDefaultTimeoutMs(ctx.db),
    modelName: getModelName(ctx.db),
    callerKey: getCallerKey(ctx.db) ?? '',
  }));

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

    if (newTimeout !== null) setSetting(ctx.db, SETTING_DEFAULT_TIMEOUT_MS, newTimeout);
    if (newModel !== null) setSetting(ctx.db, SETTING_MODEL_NAME, newModel);
    if (newPassword !== null) setAdminPassword(ctx.db, newPassword);

    return {
      ok: true,
      defaultTimeoutMs: getDefaultTimeoutMs(ctx.db),
      modelName: getModelName(ctx.db),
    };
  });
}
