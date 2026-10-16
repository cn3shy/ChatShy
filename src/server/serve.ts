import type { ServerResponse } from 'node:http';
import type { FastifyReply } from 'fastify';
import type { JobQueue, PendingJob } from '../core/jobs.js';

export const SSE_HEADERS: Record<string, string> = {
  'Content-Type': 'text/event-stream; charset=utf-8',
  'Cache-Control': 'no-cache, no-transform',
  Connection: 'keep-alive',
  'X-Accel-Buffering': 'no',
};

export interface NonStreamJson {
  success(content: string): object;
  timeout(): object;
  /** 各协议超时状态码：OpenAI 504 / Anthropic 529 */
  timeoutStatus: number;
}

export interface StreamRenderer {
  /** 连接建立后立即发出的首帧（OpenAI: role delta / Anthropic: message_start） */
  initial: string;
  /** 正文开始前的帧（Anthropic 的 content_block_start）；没有就返回空串 */
  open: string;
  /** 正文的一段（打字机逐帧下发时会被调用多次） */
  delta(text: string): string;
  /** 正文发完后的收尾帧（含协议收尾事件）。传入完整正文，因为 usage 要按全文估算。 */
  finish(fullText: string): string[];
  /** 空档期心跳帧 */
  ping: string;
  /** 超时/被取代时的收尾错误帧 */
  error: string;
  /** 心跳间隔毫秒，默认 15 秒 */
  pingMs?: number;
}

/** 非流式：挂起 HTTP 响应等待操作员回复（设计 §3.4、§4.4） */
export async function serveNonStream(opts: {
  reply: FastifyReply;
  queue: JobQueue;
  job: PendingJob;
  json: NonStreamJson;
  onDelivered(content: string): void;
}): Promise<void> {
  const { reply, queue, job, json } = opts;
  let done = false;
  reply.raw.on('close', () => {
    if (!done) queue.cancel(job.id); // 调用方断开
  });

  const outcome = await job.wait();
  done = true;

  switch (outcome.kind) {
    case 'replied':
      opts.onDelivered(outcome.content);
      reply.send(json.success(outcome.content));
      return;
    case 'timeout':
    case 'superseded':
      reply.code(json.timeoutStatus).send(json.timeout());
      return;
    case 'cancelled':
      return; // 连接已断开，无需（也无法）再写响应
  }
}

/**
 * 打字机的节拍与预算。
 *
 * ⚠️ 这**不是**在模拟网络延迟：整段回复是操作员一次写完的，分片下发纯粹为了观感和真实
 * 大模型一致。代价是调用方要多等一会儿才拿到全文，所以总时长必须封顶——否则一段长回复
 * 会按固定节拍发上几十秒，既难等，也可能撞上客户端自己的请求超时（下面的预算把长回复
 * 自动加速，绝不会超过约 9 秒）。
 */
const TYPEWRITER_TICK_MS = 25;
/** 每一帧吐几个字素：太小则事件碎、太大则不像打字机 */
const TYPEWRITER_GRAPHEMES_PER_FRAME = 2;
/** 帧数上限。长回复靠加大每帧的字素量压帧数，否则光帧数就能把总时长拖爆 */
const TYPEWRITER_MAX_FRAMES = 400;
const TYPEWRITER_BUDGET_MS = 9_000;

/**
 * 把整段文本切成逐帧下发的小片。
 * 按**字素**切而不是按 UTF-16 码元，否则 emoji 和组合字符会被从中间劈开，
 * 客户端可能渲染出半个字符。
 */
export function typewriterChunks(content: string): string[] {
  if (content === '') return [];
  const graphemes = [...new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(content)].map(
    (s) => s.segment,
  );
  const perFrame = Math.max(
    TYPEWRITER_GRAPHEMES_PER_FRAME,
    Math.ceil(graphemes.length / TYPEWRITER_MAX_FRAMES),
  );
  const chunks: string[] = [];
  for (let i = 0; i < graphemes.length; i += perFrame) {
    chunks.push(graphemes.slice(i, i + perFrame).join(''));
  }
  return chunks;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 逐帧吐出正文再收尾。任何一次写失败（调用方断开）都立即停手，不再空转。 */
async function typeOut(
  raw: ServerResponse,
  renderer: StreamRenderer,
  content: string,
  tickMs: number,
): Promise<void> {
  const write = (frame: string): boolean => {
    if (frame === '') return true;
    try {
      raw.write(frame);
      return true;
    } catch {
      return false;
    }
  };

  const chunks = typewriterChunks(content);
  // 帧数已由 typewriterChunks 压在上限内，这里再按预算压一次节拍：长回复自动加速。
  const gap = chunks.length === 0 ? 0 : Math.min(tickMs, Math.floor(TYPEWRITER_BUDGET_MS / chunks.length));

  if (!write(renderer.open)) return;
  // 按"截止时刻"推进，而不是每帧固定 sleep 一段：定时器精度不保证（Windows 默认
  // 15.6ms 一档，sleep(18) 会睡成 31ms），逐帧累加的话总时长会远超预算。盯着时刻表
  // 走则能自我纠正——落后了就少睡甚至不睡，总时长收敛到 frames × gap。
  const startedAt = Date.now();
  for (let i = 0; i < chunks.length; i++) {
    if (!write(renderer.delta(chunks[i]!))) return;
    if (gap > 0) {
      const wait = startedAt + (i + 1) * gap - Date.now();
      if (wait > 0) await sleep(wait);
    }
  }
  for (const frame of renderer.finish(content)) {
    if (!write(frame)) return;
  }
}

/** 流式：立即建 SSE 连接，心跳保活，回复/超时后按协议收尾并关闭 */
export async function serveStream(opts: {
  reply: FastifyReply;
  queue: JobQueue;
  job: PendingJob;
  renderer: StreamRenderer;
  onDelivered(content: string): void;
  /** 打字机节拍（毫秒）；0 = 仍逐帧但不等间隔（测试用，避免拖慢用例） */
  typewriterTickMs?: number;
}): Promise<void> {
  const { reply, queue, job, renderer } = opts;
  reply.hijack();
  const raw = reply.raw;
  raw.writeHead(200, SSE_HEADERS);
  raw.write(renderer.initial);

  let finished = false;
  const pingTimer = setInterval(() => {
    try {
      raw.write(renderer.ping);
    } catch {
      /* 连接已断，close 事件会清理 */
    }
  }, renderer.pingMs ?? 15_000);

  raw.on('close', () => {
    if (!finished) queue.cancel(job.id); // 调用方断开
  });

  const outcome = await job.wait();
  finished = true;
  clearInterval(pingTimer);

  if (outcome.kind === 'replied') {
    // 先落库再吐字：入库判定"回复确实发给了调用方"，与吐字快慢无关
    opts.onDelivered(outcome.content);
    await typeOut(raw, renderer, outcome.content, opts.typewriterTickMs ?? TYPEWRITER_TICK_MS);
  } else if (outcome.kind === 'timeout' || outcome.kind === 'superseded') {
    try {
      raw.write(renderer.error);
    } catch {
      /* ignore */
    }
  }
  try {
    raw.end();
  } catch {
    /* ignore */
  }
}
