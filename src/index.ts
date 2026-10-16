import Fastify from 'fastify';
import { config } from './config.js';
import { openDb } from './db/db.js';
import { migrate } from './db/migrate.js';
import { ensureAdminPassword, initSettings } from './core/settings.js';

const db = openDb(config.dbPath);
migrate(db);
initSettings(db, { defaultTimeoutMs: config.defaultTimeoutMs, modelName: config.modelName });
const adminBootstrap = ensureAdminPassword(db, config.adminPassword);

const app = Fastify({
  logger: true,
  bodyLimit: 10 * 1024 * 1024,
  trustProxy: true,
});

// 调用方请求需要长时间挂起等人工回复，必须禁用 Node 默认的 300 秒请求超时（设计 §4.5）
app.server.requestTimeout = 0;

app.get('/healthz', async () => ({ ok: true }));

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
