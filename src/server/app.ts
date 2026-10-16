import fs from 'node:fs';
import path from 'node:path';
import cookie from '@fastify/cookie';
import cors from '@fastify/cors';
import fastifyStatic from '@fastify/static';
import Fastify, { type FastifyInstance } from 'fastify';
import type { OperatorHub } from '../core/hub.js';
import type { JobQueue } from '../core/jobs.js';
import { CALLER_HEADER } from '../core/source.js';
import type { Db } from '../db/db.js';
import { registerAdminRoutes } from './routes/admin.js';
import { registerAnthropicRoutes } from './routes/anthropic.js';
import { registerOpenAIRoutes } from './routes/openai.js';
import { SessionStore } from './session.js';

export interface ServerContext {
  db: Db;
  queue: JobQueue;
  hub: OperatorHub;
  sessions: SessionStore;
  /** 调用方 SSE 空档期心跳间隔（毫秒），默认 15 秒；测试可调小 */
  ssePingMs?: number;
  /** 管理端 SSE ping 间隔（毫秒），默认 20 秒 */
  hubPingMs?: number;
  /**
   * 流式回复的打字机节拍（毫秒），默认 25。0 = 仍逐帧下发但不等待间隔。
   * 这是**内部件**，不是设置页里的调用方配置项；测试把它调成 0/1 以免每个用例都多等几秒。
   */
  typewriterTickMs?: number;
}

export async function buildServer(ctx: ServerContext, opts: { logger?: boolean } = {}): Promise<FastifyInstance> {
  const app = Fastify({
    logger: opts.logger ?? false,
    bodyLimit: 10 * 1024 * 1024,
    trustProxy: true,
    // 关闭时强制断开所有连接：挂起请求随重启丢弃（设计 §3.5），同时避免 close() 被长连接拖住
    forceCloseConnections: true,
  });

  // 挂起请求需要长时间等待人工回复，禁用 Node 默认的 300 秒请求超时（设计 §4.5）
  app.server.requestTimeout = 0;

  // 宽容解析：带 application/json 头但空 body 的请求（如无参 POST）视为 {}
  app.addContentTypeParser('application/json', { parseAs: 'string' }, (req, body, done) => {
    const text = typeof body === 'string' ? body : body.toString('utf8');
    if (text === '') return done(null, {});
    try {
      done(null, JSON.parse(text));
    } catch (err) {
      done(err as Error, undefined);
    }
  });

  await app.register(cors, {
    origin: '*',
    methods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
    // x-chatshy-caller 是调用方自报身份的头（见 core/source.ts），浏览器里的调用方要能带上它
    allowedHeaders: ['Content-Type', 'Authorization', 'x-api-key', 'anthropic-version', CALLER_HEADER],
  });
  await app.register(cookie);

  app.get('/healthz', async () => ({ ok: true }));

  // 客户端连通性探测（Claude Code 风格的 HEAD/GET /api/hello）；HEAD 由 Fastify 自动挂到 GET 上
  for (const url of ['/api/hello', '/v1/api/hello']) {
    app.get(url, async () => ({ ok: true }));
  }

  // Claude Code 遥测路径：返回 404 会触发客户端重试风暴（退避重试 ≤8 次并落盘），一律 200 吞掉
  for (const method of ['POST', 'PUT'] as const) {
    app.route({ method, url: '/api/event_logging/*', handler: async () => ({ ok: true }) });
  }

  registerOpenAIRoutes(app, ctx);
  registerAnthropicRoutes(app, ctx);
  registerAdminRoutes(app, ctx);

  // 操作台前端静态资源：存在构建产物时才托管（开发/测试环境可跳过）
  const webDist = path.resolve(process.cwd(), 'web', 'dist');
  if (fs.existsSync(path.join(webDist, 'index.html'))) {
    await app.register(fastifyStatic, { root: webDist });
    app.setNotFoundHandler((req, reply) => {
      const url = req.raw.url ?? '';
      const isApi = url.startsWith('/v1/') || url.startsWith('/admin/') || url === '/healthz';
      if (!isApi && req.method === 'GET' && (req.headers.accept ?? '').includes('text/html')) {
        return reply.sendFile('index.html'); // SPA 回退
      }
      return reply.code(404).send({ error: 'not_found' });
    });
  }

  ctx.hub.startPing(ctx.hubPingMs ?? 20_000);
  app.addHook('onClose', async () => {
    ctx.hub.stopPing();
  });

  return app;
}
