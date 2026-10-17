import { afterEach, describe, expect, it } from 'vitest';
import {
  ProviderValidationError,
  createProvider,
  deleteProvider,
  getProvider,
  listProviders,
  maskApiKey,
  normalizeBaseUrl,
  providerMessages,
  updateProvider,
} from '../../src/core/providers.js';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';

let dbs: Db[] = [];
afterEach(() => {
  for (const db of dbs) db.close();
  dbs = [];
});

function boot(): Db {
  const db = openDb(':memory:');
  migrate(db);
  dbs.push(db);
  return db;
}

const INPUT = {
  name: 'DeepSeek',
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: 'sk-test-key-1234',
  model: 'deepseek-chat',
};

describe('providers：Base URL 规范化', () => {
  it('去掉尾部斜杠', () => {
    expect(normalizeBaseUrl('https://api.deepseek.com/v1/')).toBe('https://api.deepseek.com/v1');
  });

  it('误粘贴完整 endpoint 时自动剥掉 /chat/completions', () => {
    expect(normalizeBaseUrl('https://api.deepseek.com/v1/chat/completions')).toBe(
      'https://api.deepseek.com/v1',
    );
  });

  it('保留厂商路径（DashScope / GLM）', () => {
    expect(normalizeBaseUrl('https://dashscope.aliyuncs.com/compatible-mode/v1')).toBe(
      'https://dashscope.aliyuncs.com/compatible-mode/v1',
    );
    expect(normalizeBaseUrl('https://open.bigmodel.cn/api/paas/v4/')).toBe(
      'https://open.bigmodel.cn/api/paas/v4',
    );
  });

  it('拒绝非 http(s)、带凭据、带 query/hash 的地址', () => {
    for (const bad of [
      'ftp://example.com/v1',
      'https://user:pass@example.com/v1',
      'https://example.com/v1?x=1',
      'https://example.com/v1#frag',
      'not a url',
    ]) {
      expect(() => normalizeBaseUrl(bad)).toThrow(ProviderValidationError);
    }
  });
});

describe('providers：CRUD', () => {
  it('创建、列表、更新、删除', () => {
    const db = boot();
    const created = createProvider(db, INPUT);
    expect(created.name).toBe('DeepSeek');
    expect(listProviders(db)).toHaveLength(1);

    const updated = updateProvider(db, created.id, {
      name: 'DS',
      baseUrl: 'https://api.deepseek.com',
      model: 'deepseek-reasoner',
    });
    expect(updated).toMatchObject({
      name: 'DS',
      baseUrl: 'https://api.deepseek.com',
      model: 'deepseek-reasoner',
      apiKey: INPUT.apiKey, // 省略 apiKey = 保持原 Key
    });

    expect(deleteProvider(db, created.id)).toBe(true);
    expect(getProvider(db, created.id)).toBeNull();
  });

  it('同名厂商被拒绝', () => {
    const db = boot();
    createProvider(db, INPUT);
    expect(() => createProvider(db, { ...INPUT, baseUrl: 'https://other.example/v1' })).toThrow(
      ProviderValidationError,
    );
  });

  it('更新时传空 apiKey 视为沿用旧值', () => {
    const db = boot();
    const p = createProvider(db, INPUT);
    const updated = updateProvider(db, p.id, { apiKey: '' })!;
    expect(updated.apiKey).toBe(INPUT.apiKey);
  });

  it('空字段被拒绝', () => {
    const db = boot();
    expect(() => createProvider(db, { ...INPUT, name: '   ' })).toThrow(ProviderValidationError);
    expect(() => createProvider(db, { ...INPUT, model: '' })).toThrow(ProviderValidationError);
    expect(() => createProvider(db, { ...INPUT, apiKey: '' })).toThrow(ProviderValidationError);
  });

  it('Key 只回掩码', () => {
    expect(maskApiKey('sk-test-key-1234')).toBe('••••••••1234');
    expect(maskApiKey('short')).toBe('••••••••');
    expect(maskApiKey('')).toBe('');
  });
});

describe('providers：请求消息组装', () => {
  it('system 放在最前，历史顺序不变', () => {
    expect(
      providerMessages({
        model: 'upstream-model',
        system: '你是助手',
        messages: [
          { role: 'user', content: '在吗' },
          { role: 'assistant', content: '在' },
          { role: 'user', content: '继续' },
        ],
      }),
    ).toEqual([
      { role: 'system', content: '你是助手' },
      { role: 'user', content: '在吗' },
      { role: 'assistant', content: '在' },
      { role: 'user', content: '继续' },
    ]);
  });
});
