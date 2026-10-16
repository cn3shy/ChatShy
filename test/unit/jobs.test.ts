import { afterEach, describe, expect, it, vi } from 'vitest';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';
import { generateKey } from '../../src/core/keys.js';
import { resolveThread } from '../../src/core/threads.js';
import { JobQueue, type JobEvent } from '../../src/core/jobs.js';

function setup(): { db: Db; keyId: string; threadId: string; queue: JobQueue; events: JobEvent[] } {
  const db = openDb(':memory:');
  migrate(db);
  const keyId = generateKey(db, 'k').record.id;
  const threadId = resolveThread(db, {
    keyId,
    protocol: 'openai',
    model: 'm',
    messages: [{ role: 'user', content: 'u' }],
  }).thread.id;
  const events: JobEvent[] = [];
  const queue = new JobQueue(db, (e) => events.push(e));
  return { db, keyId, threadId, queue, events };
}

function jobRow(db: Db, id: string): { status: string; reply_content: string | null; replied_at: number | null } {
  return db.prepare('SELECT status, reply_content, replied_at FROM jobs WHERE id = ?').get(id) as {
    status: string;
    reply_content: string | null;
    replied_at: number | null;
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('JobQueue', () => {
  it('正常回复：wait 拿到内容，库状态与事件正确，结局只兑现一次', async () => {
    const { db, keyId, threadId, queue, events } = setup();
    const job = queue.create({ keyId, threadId, protocol: 'openai', mode: 'non_stream', requestJson: '{}', timeoutMs: 60_000 });
    const waitP = job.wait();

    expect(queue.reply(job.id, '你好')).toBe(true);
    expect(await waitP).toEqual({ kind: 'replied', content: '你好' });

    const row = jobRow(db, job.id);
    expect(row.status).toBe('replied');
    expect(row.reply_content).toBe('你好');
    expect(row.replied_at).not.toBeNull();

    // 已完成的 Job 不再接受任何操作
    expect(queue.reply(job.id, 'again')).toBe(false);
    expect(queue.ignore(job.id)).toBe(false);
    expect(queue.cancel(job.id)).toBe(false);

    expect(events.map((e) => e.type)).toEqual(['job.created', 'job.replied']);
  });

  it('超时：到 deadline 自动以 timeout 兑现，库状态同步', async () => {
    vi.useFakeTimers();
    const { db, keyId, threadId, queue, events } = setup();
    const job = queue.create({ keyId, threadId, protocol: 'openai', mode: 'stream', requestJson: '{}', timeoutMs: 1000 });
    const waitP = job.wait();

    await vi.advanceTimersByTimeAsync(999);
    expect(queue.get(job.id)?.status).toBe('pending');
    await vi.advanceTimersByTimeAsync(1);

    expect(await waitP).toEqual({ kind: 'timeout' });
    expect(jobRow(db, job.id).status).toBe('timeout');
    expect(events.map((e) => e.type)).toEqual(['job.created', 'job.timeout']);
  });

  it('忽略：对调用方等价于超时', async () => {
    const { db, keyId, threadId, queue } = setup();
    const job = queue.create({ keyId, threadId, protocol: 'anthropic', mode: 'non_stream', requestJson: '{}', timeoutMs: 60_000 });
    const waitP = job.wait();
    expect(queue.ignore(job.id)).toBe(true);
    expect(await waitP).toEqual({ kind: 'timeout' });
    expect(jobRow(db, job.id).status).toBe('timeout');
  });

  it('调用方断开：cancelled，之后回复不再尝试发送', async () => {
    const { db, keyId, threadId, queue } = setup();
    const job = queue.create({ keyId, threadId, protocol: 'openai', mode: 'stream', requestJson: '{}', timeoutMs: 60_000 });
    const waitP = job.wait();
    expect(queue.cancel(job.id)).toBe(true);
    expect(await waitP).toEqual({ kind: 'cancelled' });
    expect(jobRow(db, job.id).status).toBe('cancelled');
    expect(queue.reply(job.id, 'late')).toBe(false);
  });

  it('同一个会话新请求取代旧挂起请求（客户端重试）', async () => {
    const { db, keyId, threadId, queue, events } = setup();
    const a = queue.create({ keyId, threadId, protocol: 'openai', mode: 'non_stream', requestJson: '{}', timeoutMs: 60_000 });
    const aWait = a.wait();

    const b = queue.create({ keyId, threadId, protocol: 'openai', mode: 'non_stream', requestJson: '{}', timeoutMs: 60_000 });

    expect(await aWait).toEqual({ kind: 'superseded' });
    expect(jobRow(db, a.id).status).toBe('superseded');
    expect(queue.get(b.id)?.status).toBe('pending');

    // 回复仍能正常送达新请求
    expect(queue.reply(b.id, 'ok')).toBe(true);
    expect(await b.wait()).toEqual({ kind: 'replied', content: 'ok' });
    expect(events.map((e) => e.type)).toEqual(['job.created', 'job.superseded', 'job.created', 'job.replied']);
  });

  it('不同会话的挂起请求互不影响', async () => {
    const { db, keyId, threadId, queue } = setup();
    const thread2 = resolveThread(db, {
      keyId,
      protocol: 'openai',
      model: 'm',
      messages: [{ role: 'user', content: '另一个会话' }],
    }).thread.id;

    const a = queue.create({ keyId, threadId, protocol: 'openai', mode: 'non_stream', requestJson: '{}', timeoutMs: 60_000 });
    queue.create({ keyId, threadId: thread2, protocol: 'openai', mode: 'non_stream', requestJson: '{}', timeoutMs: 60_000 });

    expect(queue.get(a.id)?.status).toBe('pending');
    expect(queue.countPendingForKey(keyId)).toBe(2);
    expect(queue.countPendingForThread(threadId)).toBe(1);

    queue.reply(a.id, 'done');
    expect(await a.wait()).toEqual({ kind: 'replied', content: 'done' });
    expect(queue.countPendingForKey(keyId)).toBe(1);
  });
});
