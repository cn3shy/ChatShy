import { computed, reactive } from 'vue';
import { fetchModel, streamChat } from './api';
import { loadConversations, saveConversations } from './storage';
import type { ChatMessage, Conversation, PayloadMessage } from './types';

interface ChatState {
  conversations: Conversation[];
  currentId: string | null;
  streaming: boolean;
  model: string;
}

export const state = reactive<ChatState>({
  conversations: loadConversations(),
  currentId: null,
  streaming: false,
  model: '豆奶',
});

export const currentConversation = computed<Conversation | null>(() =>
  state.conversations.find((c) => c.id === state.currentId) ?? null);

let abortCtl: AbortController | null = null;

function persist(): void {
  saveConversations(state.conversations);
}

function uid(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

export function newChat(): void {
  state.currentId = null;
}

export function selectConversation(id: string): void {
  state.currentId = id;
}

export function deleteConversation(id: string): void {
  state.conversations = state.conversations.filter((c) => c.id !== id);
  if (state.currentId === id) state.currentId = null;
  persist();
}

function createConversation(): Conversation {
  const conversation: Conversation = {
    id: uid(),
    title: '新对话',
    createdAt: Date.now(),
    messages: [],
  };
  state.conversations.unshift(conversation);
  state.currentId = conversation.id;
  return conversation;
}

function buildPayloadMessages(conversation: Conversation): PayloadMessage[] {
  const messages: PayloadMessage[] = [];
  for (const message of conversation.messages) {
    if (message.error || message.content === '') continue;
    messages.push({ role: message.role, content: message.content });
  }
  return messages;
}

export async function send(text: string): Promise<void> {
  const trimmed = text.trim();
  if (trimmed === '' || state.streaming) return;

  let conversation = currentConversation.value;
  if (conversation === null) conversation = createConversation();

  const isFirst = conversation.messages.length === 0;
  conversation.messages.push({ role: 'user', content: trimmed });
  if (isFirst) conversation.title = trimmed.replace(/\s+/g, ' ').slice(0, 20);
  persist();

  await generate(conversation);
}

async function generate(conversation: Conversation): Promise<void> {
  const placeholder: ChatMessage = { role: 'assistant', content: '', reasoning: '' };
  conversation.messages.push(placeholder);

  state.streaming = true;
  abortCtl = new AbortController();
  const controller = abortCtl;

  try {
    await streamChat(buildPayloadMessages(conversation), {
      onDelta(content, reasoning) {
        placeholder.content = content;
        placeholder.reasoning = reasoning;
      },
    }, controller.signal);
    finish(conversation, placeholder, null);
  } catch (error) {
    const name = (error as { name?: string }).name;
    if (name === 'AbortError') {
      finish(conversation, placeholder, null);
    } else {
      const message = error instanceof Error ? error.message : String(error);
      finish(conversation, placeholder, `连接失败:${message}`);
    }
  }
}

function finish(conversation: Conversation, placeholder: ChatMessage, errorMessage: string | null): void {
  if (errorMessage !== null) {
    placeholder.error = true;
    placeholder.errorMsg = errorMessage;
    delete placeholder.content;
    delete placeholder.reasoning;
  } else if (!placeholder.content && !placeholder.reasoning) {
    placeholder.error = true;
    placeholder.errorMsg = '上游没有返回任何内容,请检查模型名是否正确。';
    delete placeholder.content;
    delete placeholder.reasoning;
  }
  state.streaming = false;
  abortCtl = null;
  persist();
  void conversation;
}

export function stop(): void {
  abortCtl?.abort();
}

export function regenerate(): void {
  if (state.streaming) return;
  const conversation = currentConversation.value;
  if (conversation === null) return;
  while (conversation.messages.length > 0
    && conversation.messages[conversation.messages.length - 1]!.role === 'assistant') {
    conversation.messages.pop();
  }
  if (conversation.messages.length === 0) {
    persist();
    return;
  }
  persist();
  void generate(conversation);
}

export async function loadModel(): Promise<void> {
  const model = await fetchModel();
  if (model !== '') state.model = model;
}
