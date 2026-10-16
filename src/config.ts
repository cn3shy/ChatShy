import fs from 'node:fs';
import path from 'node:path';

export interface Config {
  port: number;
  dbPath: string;
  adminPassword: string | null;
  defaultTimeoutMs: number;
  modelName: string;
}

/** 极简 .env 加载：KEY=VALUE 行、# 注释、值可选引号；不覆盖已存在的环境变量 */
function loadDotEnv(file: string): void {
  let text: string;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return; // 没有 .env 就纯用环境变量
  }
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

loadDotEnv(path.resolve(process.cwd(), '.env'));

function intEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === '') return fallback;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : fallback;
}

function strEnv(name: string): string | null {
  const raw = process.env[name];
  return raw === undefined || raw === '' ? null : raw;
}

export const config: Config = {
  port: intEnv('PORT', 8787),
  dbPath: strEnv('DB_PATH')
    ? path.resolve(process.cwd(), strEnv('DB_PATH')!)
    : path.resolve(process.cwd(), 'data', 'chatshy.db'),
  adminPassword: strEnv('ADMIN_PASSWORD'),
  defaultTimeoutMs: intEnv('DEFAULT_TIMEOUT_MS', 180_000),
  modelName: strEnv('MODEL_NAME')?.trim() || 'chatshy',
};
