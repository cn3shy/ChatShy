import fs from 'node:fs';
import type { AddressInfo } from 'node:net';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import { JobQueue } from '../../src/core/jobs.js';
import { OperatorHub } from '../../src/core/hub.js';
import {
  SETTING_CALLER_KEY,
  SETTING_DEFAULT_TIMEOUT_MS,
  initSettings,
  setAdminPassword,
  setSetting,
} from '../../src/core/settings.js';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';
import { buildServer } from '../../src/server/app.js';
import { SessionStore } from '../../src/server/session.js';

export const ADMIN_PASSWORD = 'test-admin-pass';
/** 全局调用方 Key：所有调用方共用，测试里硬编码成确定值 */
export const CALLER_KEY = 'sk-chatshy-test-caller-key';
export const DEFAULT_TIMEOUT_MS = 60_000;

export interface TestServer {
  app: FastifyInstance;
  db: Db;
  queue: JobQueue;
  hub: OperatorHub;
  sessions: SessionStore;
  base: string;
  /** 全局调用方 Key 明文 */
  key: string;
  /** 临时改全局挂起超时，跑完自动还原（取代了旧的 per-key shortKey） */
  withDefaultTimeout<T>(ms: number, fn: () => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

export async function startTestServer(): Promise<TestServer> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'chatshy-test-'));
  const db = openDb(path.join(dir, 'test.db'));
  migrate(db);
  initSettings(db, { defaultTimeoutMs: DEFAULT_TIMEOUT_MS, modelName: 'chatshy' });
  setAdminPassword(db, ADMIN_PASSWORD);
  setSetting(db, SETTING_CALLER_KEY, CALLER_KEY);

  const hub = new OperatorHub();
  const queue = new JobQueue(db, (evt) => hub.broadcast(evt));
  const sessions = new SessionStore();

  const app = await buildServer({ db, queue, hub, sessions, ssePingMs: 80, hubPingMs: 150 }, { logger: false });
  await app.listen({ port: 0, host: '127.0.0.1' });
  const { port } = app.server.address() as AddressInfo;
  const base = `http://127.0.0.1:${port}`;

  return {
    app,
    db,
    queue,
    hub,
    sessions,
    base,
    key: CALLER_KEY,
    async withDefaultTimeout<T>(ms: number, fn: () => Promise<T>): Promise<T> {
      // getDefaultTimeoutMs 是每次请求从 settings 读的，所以临发请求前改即可生效
      setSetting(db, SETTING_DEFAULT_TIMEOUT_MS, String(ms));
      try {
        return await fn();
      } finally {
        setSetting(db, SETTING_DEFAULT_TIMEOUT_MS, String(DEFAULT_TIMEOUT_MS));
      }
    },
    async close() {
      await app.close();
      db.close();
      fs.rmSync(dir, { recursive: true, force: true });
    },
  };
}

export interface CallHeaders {
  [key: string]: string;
}

/**
 * 调用方请求头。ua 用来控制请求来源——会话归并按来源指纹隔离，
 * 测试要构造"不同来源"时必须显式指定，不能依赖 fetch 的默认 UA。
 */
export function callHeaders(opts: { key?: string; ua?: string } = {}): CallHeaders {
  return {
    'content-type': 'application/json',
    authorization: `Bearer ${opts.key ?? CALLER_KEY}`,
    'user-agent': opts.ua ?? 'chatshy-test/1.0',
  };
}

export async function waitFor(cond: () => boolean, timeoutMs = 3000): Promise<void> {
  const start = Date.now();
  while (!cond()) {
    if (Date.now() - start > timeoutMs) throw new Error('waitFor 超时');
    await new Promise((r) => setTimeout(r, 10));
  }
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}
