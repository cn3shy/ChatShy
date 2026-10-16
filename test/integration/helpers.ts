import fs from 'node:fs';
import type { AddressInfo } from 'node:net';
import os from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import { OperatorHub } from '../../src/core/hub.js';
import { JobQueue } from '../../src/core/jobs.js';
import { generateKey, type GenerateKeyOptions } from '../../src/core/keys.js';
import { initSettings, setAdminPassword } from '../../src/core/settings.js';
import { openDb, type Db } from '../../src/db/db.js';
import { migrate } from '../../src/db/migrate.js';
import { buildServer } from '../../src/server/app.js';
import { SessionStore } from '../../src/server/session.js';

export const ADMIN_PASSWORD = 'test-admin-pass';

export interface TestServer {
  app: FastifyInstance;
  db: Db;
  queue: JobQueue;
  hub: OperatorHub;
  sessions: SessionStore;
  base: string;
  /** 常规 key：超时 60 秒 */
  key: string;
  keyId: string;
  /** 短超时 key：300 毫秒 */
  shortKey: string;
  shortKeyId: string;
  addKey(opts?: GenerateKeyOptions & { name?: string }): { plaintext: string; id: string };
  close(): Promise<void>;
}

export async function startTestServer(): Promise<TestServer> {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'chatshy-test-'));
  const db = openDb(path.join(dir, 'test.db'));
  migrate(db);
  initSettings(db, { defaultTimeoutMs: 60_000, modelName: 'chatshy' });
  setAdminPassword(db, ADMIN_PASSWORD);

  const hub = new OperatorHub();
  const queue = new JobQueue(db, (evt) => hub.broadcast(evt));
  const sessions = new SessionStore();

  const app = await buildServer({ db, queue, hub, sessions, ssePingMs: 80, hubPingMs: 150 }, { logger: false });
  await app.listen({ port: 0, host: '127.0.0.1' });
  const { port } = app.server.address() as AddressInfo;
  const base = `http://127.0.0.1:${port}`;

  const normal = generateKey(db, 'normal', { timeoutMs: 60_000 });
  const short = generateKey(db, 'short', { timeoutMs: 300 });

  return {
    app,
    db,
    queue,
    hub,
    sessions,
    base,
    key: normal.plaintext,
    keyId: normal.record.id,
    shortKey: short.plaintext,
    shortKeyId: short.record.id,
    addKey(opts = {}) {
      const { name, ...keyOpts } = opts;
      const k = generateKey(db, name ?? 'extra', keyOpts);
      return { plaintext: k.plaintext, id: k.record.id };
    },
    async close() {
      await app.close();
      db.close();
      fs.rmSync(dir, { recursive: true, force: true });
    },
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
