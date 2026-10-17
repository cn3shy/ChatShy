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
  /** AI 回复的厂商名快照；人工回复为空串。只有 assistant 消息会带这个值。 */
  via: string;
}

export interface PendingJob {
  id: string;
  protocol: string;
  mode: string;
  deadlineAt: number;
  /** 自动/手动转发失败原因；null = 没有失败记录 */
  forwardError: string | null;
  forwardErrorAt: number | null;
}

/** 管理端可见的厂商配置（不含完整 Key） */
export interface ProviderInfo {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  hasApiKey: boolean;
  apiKeyMasked: string;
  createdAt: number;
  updatedAt: number;
}

export interface ProviderInput {
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
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
  /** 自动转发开关（服务端持久化，不依赖操作台在线） */
  autoForwardEnabled: boolean;
  /** 自动转发选定的厂商 id；空串 = 未选择 */
  autoForwardProviderId: string;
}
