import type { Conversation } from './types';

/** 与旧版豆奶共用同一个 key，升级后历史会话原样保留。 */
const LS_CONVS = 'dounai.conversations.v1';

export function loadConversations(): Conversation[] {
  try {
    const raw = JSON.parse(localStorage.getItem(LS_CONVS) || '[]');
    return Array.isArray(raw) ? (raw as Conversation[]) : [];
  } catch {
    return [];
  }
}

export function saveConversations(conversations: Conversation[]): void {
  localStorage.setItem(LS_CONVS, JSON.stringify(conversations));
}
