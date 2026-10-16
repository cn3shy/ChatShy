import { beforeEach, describe, expect, it } from 'vitest';
import type { RequestSource } from '../../src/core/source.js';
import { appendMessage, listThreadMessages, resolveThread } from '../../src/core/threads.js';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';

let db: Db;

beforeEach(() => {
  db = openDb(':memory:');
  migrate(db);
});

/** 测试用的来源对象。fingerprint 就是会话归并的作用域。 */
const src = (fingerprint: string, over: Partial<RequestSource> = {}): RequestSource => ({
  ip: '127.0.0.1',
  ua: 'test/1.0',
  caller: '',
  client: '测试客户端',
  fingerprint,
  ...over,
});

const SOURCE = src('fp-1');
const OTHER = src('fp-2', { ip: '10.0.0.1', ua: 'other/2.0', client: '另一个客户端' });

const U = (content: string) => ({ role: 'user' as const, content });
const A = (content: string) => ({ role: 'assistant' as const, content });

function resolve(
  messages: { role: 'user' | 'assistant'; content: string }[],
  system?: string,
  source: RequestSource = SOURCE,
) {
  return resolveThread(db, { source, protocol: 'openai', model: 'test-model', system, messages });
}

describe('ThreadManager', () => {
  it('首个请求新建会话，标题取第一条 user 消息（截断 50 字）', () => {
    const long = '这是一条很长很长的消息'.repeat(10);
    const r = resolve([U(long)]);
    expect(r.thread.title).toHaveLength(50);
    expect(r.appended).toHaveLength(1);
    expect(r.appended[0]!.source).toBe('api');
  });

  it('新建会话时记下来源信息', () => {
    const r = resolve([U('hi')]);
    expect(r.thread.sourceIp).toBe('127.0.0.1');
    expect(r.thread.sourceUa).toBe('test/1.0');
    expect(r.thread.sourceClient).toBe('测试客户端');
    expect(r.thread.sourceFingerprint).toBe('fp-1');
  });

  it('携带历史的后续请求归并进同一会话，只追加新消息', () => {
    const r1 = resolve([U('u1')]);
    appendMessage(db, { threadId: r1.thread.id, role: 'assistant', content: 'a1', source: 'api' });

    const r2 = resolve([U('u1'), A('a1'), U('u2')]);
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(r2.appended.map((m) => m.content)).toEqual(['u2']);
    expect(listThreadMessages(db, r1.thread.id).map((m) => m.content)).toEqual(['u1', 'a1', 'u2']);
  });

  it('归并时刷新来源信息（客户端升级换 UA 后展示要跟上）', () => {
    const r1 = resolve([U('u1')]);
    const r2 = resolve([U('u1'), A('a1'), U('u2')], undefined, src('fp-1', { ua: 'test/2.0' }));
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(r2.thread.sourceUa).toBe('test/2.0');
    // 指纹本身不变，否则会话会被切段
    expect(r2.thread.sourceFingerprint).toBe('fp-1');
  });

  it('重复发送同一历史：归并且不重复入库', () => {
    const r1 = resolve([U('u1')]);
    const r2 = resolve([U('u1')]);
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(r2.appended).toHaveLength(0);
    expect(listThreadMessages(db, r1.thread.id)).toHaveLength(1);
  });

  it('不同开场白的请求各自成会话，不串线', () => {
    const r1 = resolve([U('会话一')]);
    const r2 = resolve([U('会话二')]);
    expect(r2.thread.id).not.toBe(r1.thread.id);
  });

  /**
   * 本次重构的核心回归点：所有调用方共用一个全局 Key，会话归并必须按来源隔离。
   * 隔离失效时，两个人用同一条开场白（或 Claude Code 那种对所有用户几乎一致的
   * system 提示词）会被并进同一条会话，操作员的回复直接串台。
   */
  it('不同来源即使消息完全相同，也不会被并成一条会话', () => {
    const r1 = resolve([U('你好')], 'You are Claude Code, Anthropic official CLI.');
    const r2 = resolve([U('你好')], 'You are Claude Code, Anthropic official CLI.', OTHER);

    expect(r2.thread.id).not.toBe(r1.thread.id);
    expect(listThreadMessages(db, r1.thread.id)).toHaveLength(2); // system + user
    expect(listThreadMessages(db, r2.thread.id)).toHaveLength(2);
  });

  it('不同来源的历史长得一样时也不会互相认领', () => {
    const r1 = resolve([U('u1')]);
    appendMessage(db, { threadId: r1.thread.id, role: 'assistant', content: 'a1', source: 'api' });

    const r2 = resolve([U('u1'), A('a1'), U('u2')], undefined, OTHER);
    expect(r2.thread.id).not.toBe(r1.thread.id);
  });

  it('前缀不匹配但指纹相同（历史被改动）：归并到该会话并只追加分歧后的消息', () => {
    const r1 = resolve([U('共同开头')]);
    appendMessage(db, { threadId: r1.thread.id, role: 'assistant', content: '原回复', source: 'api' });

    const r2 = resolve([U('共同开头'), A('被改过的回复'), U('继续')]);
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(r2.appended.map((m) => m.content)).toEqual(['被改过的回复', '继续']);
  });

  it('控制台消息不参与前缀匹配', () => {
    const r1 = resolve([U('u1')]);
    appendMessage(db, { threadId: r1.thread.id, role: 'user', content: '控制台乱入', source: 'console' });
    appendMessage(db, { threadId: r1.thread.id, role: 'assistant', content: '控制台回复', source: 'console' });

    const r2 = resolve([U('u1'), A('a1'), U('u2')]);
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(r2.appended.map((m) => m.content)).toEqual(['a1', 'u2']);
  });

  it('多个前缀命中时取最长匹配', () => {
    resolve([U('p')]); // 会话 A，序列 [p]
    const now = Date.now();
    db.prepare(
      `INSERT INTO threads (id, title, protocol, model, fingerprint, source_fingerprint, created_at, updated_at)
       VALUES ('t2', 'p', 'openai', 'm', 'fp2', ?, ?, ?)`,
    ).run(SOURCE.fingerprint, now, now);
    db.prepare(
      "INSERT INTO messages (id, thread_id, role, content, source, created_at) VALUES ('m1', 't2', 'user', 'p', 'api', ?)",
    ).run(now);
    db.prepare(
      "INSERT INTO messages (id, thread_id, role, content, source, created_at) VALUES ('m2', 't2', 'assistant', 'q', 'api', ?)",
    ).run(now + 1);

    const r = resolve([U('p'), A('q'), U('r')]);
    expect(r.thread.id).toBe('t2');
    expect(r.appended.map((m) => m.content)).toEqual(['r']);
  });

  it('system 只落库展示一次，且不参与匹配', () => {
    const r1 = resolve([U('hi')], 'You are helpful.');
    expect(listThreadMessages(db, r1.thread.id).map((m) => m.role)).toEqual(['system', 'user']);

    const r2 = resolve([U('hi'), A('a1'), U('next')], 'You are helpful.');
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(listThreadMessages(db, r1.thread.id).filter((m) => m.role === 'system')).toHaveLength(1);
  });
});
