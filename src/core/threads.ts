import crypto from 'node:crypto';
import type { Db } from '../db/db.js';
import type { ChatMsg, Protocol } from '../protocol/types.js';

/**
 * 会话归并（设计 §5.2）。
 *
 * source 语义：
 * - 'api'     —— 属于 API 转录本的消息（调用方发来的 user、投递给调用方的操作员回复），参与前缀匹配
 * - 'console' —— 操作台测试窗口里直接发的消息、超时后补发的未送达回复，不参与匹配
 *
 * role='system' 的消息仅用于展示（首次建会话时落一条），不参与匹配。
 */

export type MessageRole = 'user' | 'assistant' | 'system';
export type MessageSource = 'api' | 'console';
/** 'console' = 操作台测试会话，不绑定任何 key */
export type ThreadProtocol = Protocol | 'console';

export interface ThreadRecord {
  id: string;
  keyId: string | null;
  title: string;
  protocol: ThreadProtocol;
  model: string;
  fingerprint: string;
  createdAt: number;
  updatedAt: number;
}

export interface MessageRecord {
  id: string;
  threadId: string;
  role: MessageRole;
  content: string;
  source: MessageSource;
  jobId: string | null;
  createdAt: number;
}

interface ThreadRow {
  id: string;
  key_id: string | null;
  title: string;
  protocol: string;
  model: string;
  fingerprint: string;
  created_at: number;
  updated_at: number;
}

interface MessageRow {
  id: string;
  thread_id: string;
  role: string;
  content: string;
  source: string;
  job_id: string | null;
  created_at: number;
}

function threadToRecord(row: ThreadRow): ThreadRecord {
  return {
    id: row.id,
    keyId: row.key_id,
    title: row.title,
    protocol: row.protocol as ThreadProtocol,
    model: row.model,
    fingerprint: row.fingerprint,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

function messageToRecord(row: MessageRow): MessageRecord {
  return {
    id: row.id,
    threadId: row.thread_id,
    role: row.role as MessageRole,
    content: row.content,
    source: row.source as MessageSource,
    jobId: row.job_id,
    createdAt: row.created_at,
  };
}

export interface ResolveParams {
  keyId: string;
  protocol: Protocol;
  model: string;
  system?: string;
  messages: ChatMsg[];
}

export function fingerprintOf(system: string, firstUserContent: string): string {
  return crypto.createHash('sha256').update(`${system}\u0000${firstUserContent}`, 'utf8').digest('hex');
}

/**
 * 把一次 API 请求归并到会话：优先前缀匹配（取最长），其次指纹兜底，否则新建。
 * 返回追加的新消息（客户端已带过的历史不会重复入库）。
 */
export function resolveThread(db: Db, params: ResolveParams): { thread: ThreadRecord; appended: MessageRecord[] } {
  const now = Date.now();
  const firstUser = params.messages.find((m) => m.role === 'user');
  const fingerprint = fingerprintOf(params.system ?? '', firstUser?.content ?? '');

  const threadRows = db
    .prepare('SELECT * FROM threads WHERE key_id = ? ORDER BY updated_at DESC, rowid DESC')
    .all(params.keyId) as ThreadRow[];

  let matched: ThreadRow | null = null;
  let matchedSeq: MessageRow[] = [];
  let matchedLen = -1;

  // 1) 前缀匹配：已有 api 历史是本次消息序列的前缀，取最长
  for (const t of threadRows) {
    const seq = apiMessagesOf(db, t.id);
    if (seq.length > params.messages.length) continue;
    if (seq.length > matchedLen && isPrefixOf(seq, params.messages)) {
      matched = t;
      matchedSeq = seq;
      matchedLen = seq.length;
    }
  }

  // 2) 指纹兜底：system + 第一条 user 消息一致，归并到最近活跃的会话
  if (matched === null) {
    const byFp = threadRows.find((t) => t.fingerprint === fingerprint);
    if (byFp !== undefined) {
      matched = byFp;
      matchedSeq = apiMessagesOf(db, byFp.id);
    }
  }

  let threadRow: ThreadRow;
  let appendedRows: MessageRow[];

  if (matched !== null) {
    threadRow = matched;
    const skip = commonPrefixLength(matchedSeq, params.messages);
    appendedRows = insertApiMessages(db, threadRow.id, params.messages.slice(skip), now);
    db.prepare('UPDATE threads SET updated_at = ? WHERE id = ?').run(now, threadRow.id);
  } else {
    const id = crypto.randomUUID();
    db.prepare(
      `INSERT INTO threads (id, key_id, title, protocol, model, fingerprint, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      params.keyId,
      titleFrom(firstUser?.content ?? ''),
      params.protocol,
      params.model,
      fingerprint,
      now,
      now,
    );
    if (params.system !== undefined && params.system !== '') {
      insertRawMessage(db, id, 'system', params.system, 'api', null, now);
    }
    appendedRows = insertApiMessages(db, id, params.messages, now);
    threadRow = db.prepare('SELECT * FROM threads WHERE id = ?').get(id) as ThreadRow;
  }

  return { thread: threadToRecord(threadRow), appended: appendedRows.map(messageToRecord) };
}

export function getThread(db: Db, id: string): ThreadRecord | null {
  const row = db.prepare('SELECT * FROM threads WHERE id = ?').get(id) as ThreadRow | undefined;
  return row === undefined ? null : threadToRecord(row);
}

/** 新建操作台测试会话（无 key，不参与 API 归并） */
export function createConsoleThread(db: Db, title: string): ThreadRecord {
  const id = crypto.randomUUID();
  const now = Date.now();
  db.prepare(
    `INSERT INTO threads (id, key_id, title, protocol, model, fingerprint, created_at, updated_at)
     VALUES (?, NULL, ?, 'console', '', '', ?, ?)`,
  ).run(id, title.slice(0, 50), now, now);
  return getThread(db, id)!;
}

/** 完整消息历史（含 system 与控制台消息），按时间升序 */
export function listThreadMessages(db: Db, threadId: string): MessageRecord[] {
  return (
    db
      .prepare('SELECT * FROM messages WHERE thread_id = ? ORDER BY created_at ASC, rowid ASC')
      .all(threadId) as MessageRow[]
  ).map(messageToRecord);
}

export interface AppendMessageParams {
  threadId: string;
  role: MessageRole;
  content: string;
  source: MessageSource;
  jobId?: string | null;
}

/** 追加一条消息（操作员回复、控制台测试消息等），并推进会话 updated_at */
export function appendMessage(db: Db, params: AppendMessageParams): MessageRecord {
  const now = Date.now();
  const id = insertRawMessage(db, params.threadId, params.role, params.content, params.source, params.jobId ?? null, now);
  db.prepare('UPDATE threads SET updated_at = ? WHERE id = ?').run(now, params.threadId);
  return messageToRecord(db.prepare('SELECT * FROM messages WHERE id = ?').get(id) as MessageRow);
}

/** 把会话里最近一条未关联 job 的 user 消息关联到该 job（展示"这条消息触发了哪个请求"） */
export function linkLatestUserMessageToJob(db: Db, threadId: string, jobId: string): void {
  db.prepare(
    `UPDATE messages SET job_id = ?
     WHERE id = (SELECT id FROM messages WHERE thread_id = ? AND role = 'user' AND job_id IS NULL
                 ORDER BY created_at DESC, rowid DESC LIMIT 1)`,
  ).run(jobId, threadId);
}

// ---- 内部工具 ----

function apiMessagesOf(db: Db, threadId: string): MessageRow[] {
  return db
    .prepare(
      `SELECT * FROM messages WHERE thread_id = ? AND source = 'api' AND role IN ('user', 'assistant')
       ORDER BY created_at ASC, rowid ASC`,
    )
    .all(threadId) as MessageRow[];
}

function isPrefixOf(seq: MessageRow[], messages: ChatMsg[]): boolean {
  return commonPrefixLength(seq, messages) === seq.length;
}

function commonPrefixLength(seq: MessageRow[], messages: ChatMsg[]): number {
  const max = Math.min(seq.length, messages.length);
  let i = 0;
  while (i < max && seq[i]!.role === messages[i]!.role && seq[i]!.content === messages[i]!.content) {
    i++;
  }
  return i;
}

function titleFrom(content: string): string {
  return content.replace(/\s+/g, ' ').trim().slice(0, 50);
}

function insertApiMessages(db: Db, threadId: string, messages: ChatMsg[], at: number): MessageRow[] {
  return messages.map((m) => {
    const id = insertRawMessage(db, threadId, m.role, m.content, 'api', null, at);
    return db.prepare('SELECT * FROM messages WHERE id = ?').get(id) as MessageRow;
  });
}

function insertRawMessage(
  db: Db,
  threadId: string,
  role: MessageRole,
  content: string,
  source: MessageSource,
  jobId: string | null,
  at: number,
): string {
  const id = crypto.randomUUID();
  db.prepare(
    'INSERT INTO messages (id, thread_id, role, content, source, job_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).run(id, threadId, role, content, source, jobId, at);
  return id;
}
