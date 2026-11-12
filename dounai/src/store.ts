import { computed, reactive, ref } from 'vue';
import { fetchModel, streamChat } from './api';
import { loadConversations, saveConversations } from './storage';
import type { ChatMessage, Conversation, PayloadMessage } from './types';

interface ChatState {
  conversations: Conversation[];
  currentId: string | null;
  streaming: boolean;
  model: string;
}

/** 每条消息最多重试次数，超过后前端与后端都会拒绝再次生成。 */
export const MAX_RETRIES = 3;

/** 全局轻提示，由 App.vue 渲染为 toast。 */
export const notice = ref('');

let noticeTimer: ReturnType<typeof setTimeout> | null = null;

function showNotice(message: string): void {
  notice.value = message;
  if (noticeTimer !== null) clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { notice.value = ''; }, 2500);
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
  // 必须返回响应式数组中的代理对象：局部持有的原始对象写入不触发 Vue 更新，
  // 首条消息的流式回复会一直空白到重新生成
  return state.conversations[0]!;
}

/** 只发送会话内容；人设提示词由后端 /api/chat 统一注入，前端不持有。 */
function buildPayloadMessages(conversation: Conversation): PayloadMessage[] {
  const messages: PayloadMessage[] = [];
  for (const message of conversation.messages) {
    if (message.error || message.content === '') continue;
    messages.push({ role: message.role, content: message.content });
  }
  return messages;
}

export async function send(text: string): Promise<boolean> {
  const trimmed = text.trim();
  if (trimmed === '' || state.streaming) return false;

  let conversation = currentConversation.value;
  if (conversation === null) conversation = createConversation();

  // 拦截重复内容：不允许与上一条用户消息完全相同
  for (let i = conversation.messages.length - 1; i >= 0; i--) {
    const message = conversation.messages[i]!;
    if (message.role === 'user') {
      if (message.content === trimmed) {
        showNotice('不能重复发送相同的内容');
        return false;
      }
      break;
    }
  }

  const isFirst = conversation.messages.length === 0;
  conversation.messages.push({ role: 'user', content: trimmed });
  if (isFirst) conversation.title = trimmed.replace(/\s+/g, ' ').slice(0, 20);
  persist();

  await generate(conversation);
  return true;
}

async function generate(conversation: Conversation): Promise<void> {
  conversation.messages.push({ role: 'assistant', content: '', reasoning: '' });
  // 必须从 reactive 数组取回代理对象：直接持有原始对象引用的写入
  // 不会触发 Vue 的依赖更新，界面会一直空白到流结束
  const placeholder = conversation.messages[conversation.messages.length - 1]!;

  state.streaming = true;
  abortCtl = new AbortController();
  const controller = abortCtl;

  try {
    const result = await streamChat(buildPayloadMessages(conversation), {
      onDelta(content, reasoning) {
        placeholder.content = content;
        placeholder.reasoning = reasoning;
      },
    }, controller.signal);
    // 敏感词拦截：不展示任何回复，连占位消息一起移除
    if (result === 'blocked') {
      const index = conversation.messages.indexOf(placeholder);
      if (index >= 0) conversation.messages.splice(index, 1);
      state.streaming = false;
      abortCtl = null;
      persist();
      showNotice('内容已拦截，未回复');
      return;
    }
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
  // 剩余的最后一条必为用户消息：在其上累计重试次数并限制上限
  const target = conversation.messages[conversation.messages.length - 1]!;
  if (target.role !== 'user') return;
  if ((target.retries ?? 0) >= MAX_RETRIES) {
    showNotice(`每条消息最多重试 ${MAX_RETRIES} 次`);
    return;
  }
  target.retries = (target.retries ?? 0) + 1;
  persist();
  void generate(conversation);
}

export async function loadModel(): Promise<void> {
  const model = await fetchModel();
  if (model !== '') state.model = model;
}
