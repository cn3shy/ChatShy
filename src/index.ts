import { config } from './config.js';
import { OperatorHub } from './core/hub.js';
import { JobQueue } from './core/jobs.js';
import { ensureAdminPassword, ensureCallerKey, initSettings } from './core/settings.js';
import { openDb } from './db/db.js';
import { migrate } from './db/migrate.js';
import { buildServer } from './server/app.js';
import { SessionStore } from './server/session.js';

const db = openDb(config.dbPath);
migrate(db);
initSettings(db, { defaultTimeoutMs: config.defaultTimeoutMs, modelName: config.modelName });
const adminBootstrap = ensureAdminPassword(db, config.adminPassword);
const keyBootstrap = ensureCallerKey(db, config.callerKey);

const hub = new OperatorHub();
const queue = new JobQueue(db, (evt) => hub.broadcast(evt));
const sessions = new SessionStore();

const app = await buildServer({ db, queue, hub, sessions }, { logger: true });

try {
  await app.listen({ port: config.port, host: '0.0.0.0' });
} catch (err) {
  app.log.error(err);
  process.exit(1);
}

if (adminBootstrap.source === 'generated') {
  console.log('');
  console.log('════════════════════════════════════════════════');
  console.log('  ChatShy 管理员初始密码（仅本次生成时显示）：');
  console.log(`    ${adminBootstrap.password}`);
  console.log('  请尽快登录操作台修改。');
  console.log('════════════════════════════════════════════════');
  console.log('');
} else if (adminBootstrap.source === 'env') {
  console.log('[chatshy] 已使用 ADMIN_PASSWORD 环境变量初始化管理员密码');
}

if (keyBootstrap.source === 'generated') {
  console.log('');
  console.log('════════════════════════════════════════════════');
  console.log('  ChatShy 调用方 Key（所有调用方共用，仅本次生成时打印）：');
  console.log(`    ${keyBootstrap.key}`);
  console.log('  也可随时在操作台「设置」页查看或重新生成。');
  console.log('════════════════════════════════════════════════');
  console.log('');
} else if (keyBootstrap.source === 'env') {
  console.log('[chatshy] 已使用 CALLER_KEY 环境变量初始化调用方 Key');
}
