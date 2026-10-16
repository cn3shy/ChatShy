import { beforeEach, describe, expect, it } from 'vitest';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';
import { generateKey } from '../../src/core/keys.js';
import { appendMessage, listThreadMessages, resolveThread } from '../../src/core/threads.js';

let db: Db;
let keyId: string;

beforeEach(() => {
  db = openDb(':memory:');
  migrate(db);
  keyId = generateKey(db, 'test').record.id;
});

const U = (content: string) => ({ role: 'user' as const, content });
const A = (content: string) => ({ role: 'assistant' as const, content });

function resolve(messages: { role: 'user' | 'assistant'; content: string }[], system?: string) {
  return resolveThread(db, { keyId, protocol: 'openai', model: 'test-model', system, messages });
}

describe('ThreadManager', () => {
  it('首个请求新建会话，标题取第一条 user 消息（截断 50 字）', () => {
    const long = '这是一条很长很长的消息'.repeat(10);
    const r = resolve([U(long)]);
    expect(r.thread.title).toHaveLength(50);
    expect(r.appended).toHaveLength(1);
    expect(r.appended[0]!.source).toBe('api');
  });

  it('携带历史的后续请求归并进同一会话，只追加新消息', () => {
    const r1 = resolve([U('u1')]);
    appendMessage(db, { threadId: r1.thread.id, role: 'assistant', content: 'a1', source: 'api' });

    const r2 = resolve([U('u1'), A('a1'), U('u2')]);
    expect(r2.thread.id).toBe(r1.thread.id);
    expect(r2.appended.map((m) => m.content)).toEqual(['u2']);
    expect(listThreadMessages(db, r1.thread.id).map((m) => m.content)).toEqual(['u1', 'a1', 'u2']);
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
      "INSERT INTO threads (id, key_id, title, protocol, model, fingerprint, created_at, updated_at) VALUES ('t2', ?, 'p', 'openai', 'm', 'fp2', ?, ?)",
    ).run(keyId, now, now);
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
