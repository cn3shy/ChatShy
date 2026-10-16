export type Protocol = 'openai' | 'anthropic' | 'console';

export interface ThreadSummary {
  id: string;
  title: string;
  protocol: Protocol;
  model: string;
  keyName: string | null;
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
  thread: {
    id: string;
    title: string;
    protocol: Protocol;
    model: string;
    keyName: string | null;
    createdAt: number;
    updatedAt: number;
  };
  messages: Message[];
  pendingJobs: PendingJob[];
}

export interface KeyInfo {
  id: string;
  name: string;
  keyPrefix: string;
  enabled: boolean;
  timeoutMs: number | null;
  dailyLimit: number | null;
  maxPending: number;
  createdAt: number;
  lastUsedAt: number | null;
}

export interface SettingsInfo {
  defaultTimeoutMs: number;
  modelName: string;
}
