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

/**
 * WHATWG 规范里的 "bad port" 名单：Node 内置的 fetch（undici）会直接拒绝连接这些端口，
 * 报 `Error: bad port`，连接根本发不出去。
 *
 * 为什么测试要管这个：绑定 0 号端口时由系统挑端口，而本机 Windows 的动态端口范围是
 * 1024–15000，**把 10080 也圈了进去**。系统一旦恰巧把这个端口分给测试服务，该用例里
 * 所有 fetch 都会失败——表现为随机一两个用例变红，且看不出与被测代码有任何关系。
 * 这不是被测代码的问题，换一个端口重来即可。
 */
const FETCH_BLOCKED_PORTS = new Set([
  1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95, 101, 102,
  103, 104, 109, 110, 111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465,
  512, 513, 514, 515, 526, 530, 531, 532, 540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993,
  995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566, 6665, 6666, 6667, 6668,
  6669, 6679, 6697, 10080,
]);

/** 假上游等服务也要挑端口：undici 对 bad port 一视同仁，不只是被测服务本身 */
export function isFetchBlockedPort(port: number): boolean {
  return FETCH_BLOCKED_PORTS.has(port);
}

export function startTestServer(): Promise<TestServer> {
  return startTestServerOnSafePort(0);
}

async function startTestServerOnSafePort(attempt: number): Promise<TestServer> {
  const server = await bootTestServer();
  const port = Number(new URL(server.base).port);
  if (!isFetchBlockedPort(port)) return server;
  // close() 会连同临时目录一起清掉，所以这里直接整个重来
  await server.close();
  if (attempt >= 5) throw new Error(`反复拿到 fetch 拒绝的端口（最后一次是 ${port}）`);
  return startTestServerOnSafePort(attempt + 1);
}

async function bootTestServer(): Promise<TestServer> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'chatshy-test-'));
  const db = openDb(path.join(dir, 'test.db'));
  migrate(db);
  initSettings(db, { defaultTimeoutMs: DEFAULT_TIMEOUT_MS, modelName: 'chatshy' });
  setAdminPassword(db, ADMIN_PASSWORD);
  setSetting(db, SETTING_CALLER_KEY, CALLER_KEY);

  const hub = new OperatorHub();
  const queue = new JobQueue(db, (evt) => hub.broadcast(evt));
  const sessions = new SessionStore();

  // typewriterTickMs=1：正文仍然逐帧下发（覆盖打字机路径），但不按真实节拍等待，
  // 否则每个流式用例都要多等好几秒
  const app = await buildServer(
    { db, queue, hub, sessions, ssePingMs: 80, hubPingMs: 150, typewriterTickMs: 1 },
    { logger: false },
  );
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
