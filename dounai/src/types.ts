export type Role = 'user' | 'assistant';

export interface ChatMessage {
  role: Role;
  content: string;
  reasoning?: string;
  error?: boolean;
  errorMsg?: string;
}

export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  messages: ChatMessage[];
}

export interface PayloadMessage {
  role: Role;
  content: string;
}
