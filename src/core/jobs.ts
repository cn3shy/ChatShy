import crypto from 'node:crypto';
import type { Db } from '../db/db.js';
import type { Protocol } from '../protocol/types.js';

/** Job 状态机：pending → replied | timeout | cancelled | superseded；dropped 仅出现在重启后的库中 */
export type JobStatus = 'pending' | 'replied' | 'timeout' | 'cancelled' | 'superseded' | 'dropped';
export type JobMode = 'stream' | 'non_stream';

export type JobOutcome =
  | { kind: 'replied'; content: string }
  | { kind: 'timeout' }
  | { kind: 'cancelled' }
  | { kind: 'superseded' };

export interface JobEvent {
  type: 'job.created' | 'job.replied' | 'job.timeout' | 'job.cancelled' | 'job.superseded';
  jobId: string;
  threadId: string;
  deadlineAt?: number;
}

export interface CreateJobParams {
  threadId: string;
  protocol: Protocol;
  mode: JobMode;
  requestJson: string;
  timeoutMs: number;
}

export interface PendingJobInit {
  id: string;
  threadId: string;
  protocol: Protocol;
  mode: JobMode;
  deadlineAt: number;
}

export class PendingJob {
  readonly id: string;
  readonly threadId: string;
  readonly protocol: Protocol;
  readonly mode: JobMode;
  readonly deadlineAt: number;
  status: JobStatus = 'pending';
  /** @internal 由 JobQueue 管理 */
  _timer: NodeJS.Timeout | null = null;
  private _resolve!: (outcome: JobOutcome) => void;
  private readonly _promise: Promise<JobOutcome>;

  constructor(init: PendingJobInit) {
    this.id = init.id;
    this.threadId = init.threadId;
    this.protocol = init.protocol;
    this.mode = init.mode;
    this.deadlineAt = init.deadlineAt;
    this._promise = new Promise<JobOutcome>((resolve) => {
      this._resolve = resolve;
    });
  }

  /** 等待最终结局；回答或超时后兑现，恰好一次 */
  wait(): Promise<JobOutcome> {
    return this._promise;
  }

  /** @internal 仅由 JobQueue 调用 */
  _complete(outcome: JobOutcome): void {
    if (this.status !== 'pending') return;
    this.status = outcome.kind === 'replied' ? 'replied' : outcome.kind;
    this._resolve(outcome);
  }
}

/**
 * 挂起请求队列（设计 §5.1）。
 * 内存是挂起 Job 的唯一真相；jobs 表用于审计与重启标记；每个 Job 的结局恰好触发一次，
 * 通过 onEvent 回调把状态变化广播给操作台（server 层接 OperatorHub）。
 */
export class JobQueue {
  private readonly jobs = new Map<string, PendingJob>();

  constructor(
    private readonly db: Db,
    private readonly onEvent: (evt: JobEvent) => void = () => {},
  ) {}

  create(params: CreateJobParams): PendingJob {
    const now = Date.now();
    const id = crypto.randomUUID();
    const deadlineAt = now + params.timeoutMs;

    // 同一会话里更早的挂起请求（客户端超时重试）一律被新请求取代（设计 §3.5）
    for (const existing of [...this.jobs.values()]) {
      if (existing.threadId === params.threadId && existing.status === 'pending') {
        this.finish(existing, { kind: 'superseded' });
      }
    }

    this.db
      .prepare(
        `INSERT INTO jobs (id, thread_id, protocol, mode, status, request_json, reply_content, deadline_at, created_at, replied_at)
         VALUES (?, ?, ?, ?, 'pending', ?, NULL, ?, ?, NULL)`,
      )
      .run(id, params.threadId, params.protocol, params.mode, params.requestJson, deadlineAt, now);

    const job = new PendingJob({
      id,
      threadId: params.threadId,
      protocol: params.protocol,
      mode: params.mode,
      deadlineAt,
    });
    job._timer = setTimeout(() => this.finish(job, { kind: 'timeout' }), params.timeoutMs);
    this.jobs.set(id, job);

    this.onEvent({ type: 'job.created', jobId: id, threadId: params.threadId, deadlineAt });
    return job;
  }

  get(jobId: string): PendingJob | null {
    return this.jobs.get(jobId) ?? null;
  }

  /** 操作员回复：完成该 Job，对调用方兑现内容 */
  reply(jobId: string, content: string): boolean {
    const job = this.jobs.get(jobId);
    if (job === undefined || job.status !== 'pending') return false;
    this.finish(job, { kind: 'replied', content });
    return true;
  }

  /** 操作员忽略：对调用方等价于超时（设计 §4.4） */
  ignore(jobId: string): boolean {
    const job = this.jobs.get(jobId);
    if (job === undefined || job.status !== 'pending') return false;
    this.finish(job, { kind: 'timeout' });
    return true;
  }

  /** 调用方断开连接 */
  cancel(jobId: string): boolean {
    const job = this.jobs.get(jobId);
    if (job === undefined || job.status !== 'pending') return false;
    this.finish(job, { kind: 'cancelled' });
    return true;
  }

  countPendingForThread(threadId: string): number {
    let n = 0;
    for (const job of this.jobs.values()) {
      if (job.threadId === threadId && job.status === 'pending') n++;
    }
    return n;
  }

  /** 当前所有挂起中的 Job（调试与测试用） */
  listPending(): PendingJob[] {
    return [...this.jobs.values()].filter((j) => j.status === 'pending');
  }

  private finish(job: PendingJob, outcome: JobOutcome): void {
    if (job.status !== 'pending') return;
    if (job._timer !== null) {
      clearTimeout(job._timer);
      job._timer = null;
    }
    this.jobs.delete(job.id);
    job._complete(outcome);

    if (outcome.kind === 'replied') {
      this.db
        .prepare("UPDATE jobs SET status = 'replied', reply_content = ?, replied_at = ? WHERE id = ?")
        .run(outcome.content, Date.now(), job.id);
    } else {
      this.db.prepare('UPDATE jobs SET status = ? WHERE id = ?').run(job.status, job.id);
    }

    this.onEvent({ type: `job.${outcome.kind}`, jobId: job.id, threadId: job.threadId });
  }
}
