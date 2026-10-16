import fs from 'node:fs';
import path from 'node:path';
import Database from 'better-sqlite3';

export type Db = InstanceType<typeof Database>;

export function openDb(dbPath: string): Db {
  if (dbPath !== ':memory:') {
    fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  }
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.pragma('foreign_keys = ON');
  return db;
}
