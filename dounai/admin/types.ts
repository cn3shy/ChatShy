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
  /** 已拉黑：网关入口直接拒绝该会话的新请求 */
  blacklisted: boolean;
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
    blacklisted: boolean;
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
  /** 敏感词原文（每行一个）；空串 = 使用后端内置词库 */
  sensitiveWords: string;
  /** 敏感词过滤开关；仅作用于豆奶公开入口 */
  sensitiveFilterEnabled: boolean;
}

/** 请求监控列表项（不含完整请求体，完整内容走详情接口） */
export interface RequestRecord {
  id: string;
  threadId: string;
  threadTitle: string;
  protocol: Protocol;
  mode: string;
  status: string;
  model: string;
  createdAt: number;
  repliedAt: number | null;
  via: string;
  providerName: string;
  sourceIp: string;
  sourceUa: string;
  sourceClient: string;
  requestPreview: string;
  replyPreview: string;
  forwardError: string | null;
}

/** 请求详情：包含完整请求 JSON 与回复内容 */
export interface RequestDetail extends RequestRecord {
  requestJson: string;
  replyContent: string | null;
  forwardErrorAt: number | null;
}

export interface RequestPage {
  total: number;
  limit: number;
  offset: number;
  requests: RequestRecord[];
}
