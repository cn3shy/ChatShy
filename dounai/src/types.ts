export type Role = 'user' | 'assistant';

export interface ChatMessage {
  role: Role;
  content: string;
  reasoning?: string;
  error?: boolean;
  errorMsg?: string;
  /** 用户消息上的重试计数，用于限制每条消息最多重试 MAX_RETRIES 次。 */
  retries?: number;
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  messages: ChatMessage[];
}

export interface PayloadMessage {
  role: Role | 'system';
  content: string;
}
