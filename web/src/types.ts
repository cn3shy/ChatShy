export type Protocol = 'openai' | 'anthropic' | 'console';

/** 请求来源（取代了旧的多 Key 体系里的 keyName） */
export interface ThreadSource {
  /** UA 识别出的客户端名，如「Claude Code」「Cherry Studio」 */
  sourceClient: string;
  sourceIp: string;
  /** 原始 User-Agent，详情里展示 */
  sourceUa: string;
}

export interface ThreadSummary extends ThreadSource {
  id: string;
  title: string;
  protocol: Protocol;
  model: string;
  createdAt: number;
  updatedAt: number;
  lastMessage: string;
  pendingCount: number;
  pendingJobId: string | null;
  pendingDeadlineAt: number | null;
}

export interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  source: 'api' | 'console';
  jobId: string | null;
  createdAt: number;
  delivered: boolean;
}

export interface PendingJob {
  id: string;
  protocol: string;
  mode: string;
  deadlineAt: number;
}

export interface ThreadDetail {
  thread: ThreadSource & {
    id: string;
    title: string;
    protocol: Protocol;
    model: string;
    createdAt: number;
    updatedAt: number;
  };
  messages: Message[];
  pendingJobs: PendingJob[];
}

export interface SettingsInfo {
  defaultTimeoutMs: number;
  modelName: string;
  /** 全局唯一的调用方 Key，所有调用方共用 */
  callerKey: string;
}
