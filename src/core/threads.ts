import crypto from 'node:crypto';
import type { Db } from '../db/db.js';
import type { ChatMsg, Protocol } from '../protocol/types.js';
import type { RequestSource } from './source.js';

/**
 * 会话归并（设计 §5.2）。
 *
 * 归并作用域是**请求来源**（source_fingerprint，见 core/source.ts），不是 key。
 * 多个调用方共用同一个全局 Key，若不做来源隔离，不同人的对话会被并成一条：
 * Claude Code 的 system 提示词对所有用户几乎一致，内容指纹兜底极易碰撞。
 *
 * message.source 语义：
 * - 'api'     —— 属于 API 转录本的消息（调用方发来的 user、投递给调用方的操作员回复），参与前缀匹配
 * - 'console' —— 超时后补发的未送达回复，只在操作台存档展示，不参与匹配
 *
 * role='system' 的消息仅用于展示（首次建会话时落一条），不参与匹配。
 */

export type MessageRole = 'user' | 'assistant' | 'system';
export type MessageSource = 'api' | 'console';
/** 'console' 是历史遗留值（旧库里的操作台测试会话），新代码不再产生 */
export type ThreadProtocol = Protocol | 'console';

export interface ThreadRecord {
  id: string;
  title: string;
  protocol: ThreadProtocol;
  model: string;
  fingerprint: string;
  sourceIp: string;
  sourceUa: string;
  sourceClient: string;
  sourceFingerprint: string;
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
  title: string;
  protocol: string;
  model: string;
  fingerprint: string;
  source_ip: string;
  source_ua: string;
  source_client: string;
  source_fingerprint: string;
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
    title: row.title,
    protocol: row.protocol as ThreadProtocol,
    model: row.model,
    fingerprint: row.fingerprint,
    sourceIp: row.source_ip,
    sourceUa: row.source_ua,
    sourceClient: row.source_client,
    sourceFingerprint: row.source_fingerprint,
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
  /** 请求来源；会话归并的作用域 */
  source: RequestSource;
  protocol: Protocol;
  model: string;
  system?: string;
  messages: ChatMsg[];
}

/** 内容指纹：system + 第一条 user 消息，用于前缀匹配失败时的兜底归并 */
export function contentFingerprint(system: string, firstUserContent: string): string {
  return crypto.createHash('sha256').update(`${system}\u0000${firstUserContent}`, 'utf8').digest('hex');
}

/**
 * 把一次 API 请求归并到会话：优先前缀匹配（取最长），其次内容指纹兜底，否则新建。
 * 候选集**只限同一来源**——所有调用方共用一个全局 Key，绝不允许互相认领会话。
 * 返回追加的新消息（客户端已带过的历史不会重复入库）。
 */
export function resolveThread(db: Db, params: ResolveParams): { thread: ThreadRecord; appended: MessageRecord[] } {
  const now = Date.now();
  const firstUser = params.messages.find((m) => m.role === 'user');
  const fingerprint = contentFingerprint(params.system ?? '', firstUser?.content ?? '');

  const threadRows = db
    .prepare('SELECT * FROM threads WHERE source_fingerprint = ? ORDER BY updated_at DESC, rowid DESC')
    .all(params.source.fingerprint) as ThreadRow[];

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

  // 2) 内容指纹兜底：system + 第一条 user 消息一致，归并到本来源内最近活跃的会话
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
    const skip = commonPrefixLength(matchedSeq, params.messages);
    appendedRows = insertApiMessages(db, matched.id, params.messages.slice(skip), now);
    // 来源信息随最新一次请求刷新（同一来源的客户端可能升级版本号）
    db.prepare(
      'UPDATE threads SET updated_at = ?, source_ip = ?, source_ua = ?, source_client = ? WHERE id = ?',
    ).run(now, params.source.ip, params.source.ua, params.source.client, matched.id);
    // 同步内存中的行，保证返回给调用方的记录是刷新后的值
    threadRow = {
      ...matched,
      updated_at: now,
      source_ip: params.source.ip,
      source_ua: params.source.ua,
      source_client: params.source.client,
    };
  } else {
    const id = crypto.randomUUID();
    db.prepare(
      `INSERT INTO threads (id, title, protocol, model, fingerprint,
                            source_ip, source_ua, source_client, source_fingerprint,
                            created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      id,
      titleFrom(firstUser?.content ?? ''),
      params.protocol,
      params.model,
      fingerprint,
      params.source.ip,
      params.source.ua,
      params.source.client,
      params.source.fingerprint,
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

/**
 * 删除会话，连带消息与请求记录（靠外键 ON DELETE CASCADE，见 db.ts 的 foreign_keys=ON）。
 *
 * 调用方必须先拿 JobQueue.failForThread 收尾挂起请求：外键管不到内存里的 Job，
 * 而且那条 HTTP 连接还在等回复——先删行再收尾，reply 落库时线程已经没了。
 * 返回是否真的删掉了一行（false = 本来就不存在）。
 */
export function deleteThread(db: Db, id: string): boolean {
  return db.prepare('DELETE FROM threads WHERE id = ?').run(id).changes > 0;
}

export function getThread(db: Db, id: string): ThreadRecord | null {
  const row = db.prepare('SELECT * FROM threads WHERE id = ?').get(id) as ThreadRow | undefined;
  return row === undefined ? null : threadToRecord(row);
}

/** 完整消息历史（含 system 与未送达的补发回复），按时间升序 */
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
