import { describe, expect, it } from 'vitest';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';
import {
  callerKeyMatches,
  ensureAdminPassword,
  ensureCallerKey,
  getCallerKey,
  getDefaultTimeoutMs,
  getModelName,
  hashPassword,
  initSettings,
  regenerateCallerKey,
  setSetting,
  verifyAdminPassword,
  verifyPassword,
} from '../../src/core/settings.js';

function freshDb(): Db {
  const db = openDb(':memory:');
  migrate(db);
  return db;
}

describe('settings', () => {
  it('initSettings 只播种一次，不覆盖已有值', () => {
    const db = freshDb();
    initSettings(db, { defaultTimeoutMs: 5000, modelName: 'chatshy' });
    initSettings(db, { defaultTimeoutMs: 9999, modelName: 'other' });

    expect(getDefaultTimeoutMs(db)).toBe(5000);
    expect(getModelName(db)).toBe('chatshy');
  });

  it('setSetting 可覆盖；非法超时值回退默认', () => {
    const db = freshDb();
    initSettings(db, { defaultTimeoutMs: 5000, modelName: 'chatshy' });

    setSetting(db, 'default_timeout_ms', '7000');
    expect(getDefaultTimeoutMs(db)).toBe(7000);

    setSetting(db, 'default_timeout_ms', 'not-a-number');
    expect(getDefaultTimeoutMs(db, 1234)).toBe(1234);
  });

  it('密码哈希往返、错误密码、垃圾存储值', () => {
    const hash = hashPassword('hello-world');
    expect(verifyPassword('hello-world', hash)).toBe(true);
    expect(verifyPassword('wrong', hash)).toBe(false);
    expect(verifyPassword('hello-world', 'garbage')).toBe(false);
    expect(verifyPassword('hello-world', 'scrypt$1$2$3$zz$')).toBe(false);
  });

  it('ensureAdminPassword：环境变量播种；已有则不动；无则随机生成一次', () => {
    const db = freshDb();
    const first = ensureAdminPassword(db, 'from-env');
    expect(first.source).toBe('env');
    expect(first.password).toBeNull();
    expect(verifyAdminPassword(db, 'from-env')).toBe(true);

    // 已有哈希后，环境变量不覆盖
    const second = ensureAdminPassword(db, 'another');
    expect(second.source).toBeNull();
    expect(verifyAdminPassword(db, 'another')).toBe(false);
    expect(verifyAdminPassword(db, 'from-env')).toBe(true);

    // 无环境变量时随机生成
    const db2 = freshDb();
    const third = ensureAdminPassword(db2, null);
    expect(third.source).toBe('generated');
    expect(third.password).not.toBeNull();
    expect(verifyAdminPassword(db2, third.password!)).toBe(true);
  });

  it('ensureCallerKey：环境变量播种；已有则不动；无则随机生成', () => {
    const db = freshDb();
    expect(getCallerKey(db)).toBeNull();

    const first = ensureCallerKey(db, 'sk-chatshy-from-env');
    expect(first.source).toBe('env');
    expect(getCallerKey(db)).toBe('sk-chatshy-from-env');

    // 已有值后，环境变量不覆盖
    const second = ensureCallerKey(db, 'another');
    expect(second.source).toBe('existing');
    expect(getCallerKey(db)).toBe('sk-chatshy-from-env');

    const db2 = freshDb();
    const third = ensureCallerKey(db2, null);
    expect(third.source).toBe('generated');
    expect(third.key).toMatch(/^sk-chatshy-/);
    expect(getCallerKey(db2)).toBe(third.key);
  });

  it('regenerateCallerKey 换新值，旧值立即失效', () => {
    const db = freshDb();
    const old = ensureCallerKey(db, null).key!;
    const fresh = regenerateCallerKey(db);

    expect(fresh).not.toBe(old);
    expect(getCallerKey(db)).toBe(fresh);
    expect(callerKeyMatches(fresh, old)).toBe(false);
  });

  it('callerKeyMatches：相等为真，长度不一的输入也不抛异常', () => {
    expect(callerKeyMatches('sk-chatshy-abc', 'sk-chatshy-abc')).toBe(true);
    // 先哈希再比，两边长度恒等，所以不会触发 timingSafeEqual 的长度异常
    expect(callerKeyMatches('sk-chatshy-abc', 'sk-chatshy-abcdefghij')).toBe(false);
    expect(callerKeyMatches('', 'x')).toBe(false);
  });
});
