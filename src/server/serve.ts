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
  /** 回复就绪后的帧序列（含协议收尾事件） */
  content(content: string): string[];
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

/** 流式：立即建 SSE 连接，心跳保活，回复/超时后按协议收尾并关闭 */
export async function serveStream(opts: {
  reply: FastifyReply;
  queue: JobQueue;
  job: PendingJob;
  renderer: StreamRenderer;
  onDelivered(content: string): void;
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
    opts.onDelivered(outcome.content);
    for (const frame of renderer.content(outcome.content)) {
      try {
        raw.write(frame);
      } catch {
        break;
      }
    }
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
