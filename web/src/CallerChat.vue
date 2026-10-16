<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
import './caller.css';
import { renderMarkdown } from './markdown';

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

interface ApiFail {
  status: number;
  message: string;
}

const LS = {
  key: 'chatshy.caller.key',
  history: 'chatshy.caller.history',
};

/**
 * 页面上没有任何设置入口，API Key 只能从管理员分享的链接里来：`/chat?key=sk-chatshy-…`。
 * 读出来后存本地，并把地址栏里的 key 抹掉——否则用户复制地址栏分享给别人时，
 * 会把凭据一起带出去。抹掉后同一台设备靠 localStorage 继续可用。
 */
function takeKeyFromUrl(): void {
  const params = new URLSearchParams(window.location.search);
  const fromUrl = params.get('key');
  if (fromUrl === null || fromUrl.trim() === '') return;
  localStorage.setItem(LS.key, fromUrl.trim());
  params.delete('key');
  const rest = params.toString();
  window.history.replaceState({}, '', window.location.pathname + (rest === '' ? '' : `?${rest}`));
}

takeKeyFromUrl();

const apiKey = ref(localStorage.getItem(LS.key) ?? '');
const messages = reactive<ChatTurn[]>(loadHistory());
const input = ref('');
const sending = ref(false);
const error = ref('');
const listEl = ref<HTMLElement | null>(null);
const taEl = ref<HTMLTextAreaElement | null>(null);
let abortCtrl: AbortController | null = null;

const TIPS = ['你好，介绍一下你自己', '用 Python 写一个快速排序', '帮我把这段话润色得更正式一些'];

const hasKey = computed(() => apiKey.value.trim() !== '');
const canSend = computed(() => !sending.value && input.value.trim() !== '' && hasKey.value);
const isEmpty = computed(() => messages.length === 0);

function loadHistory(): ChatTurn[] {
  try {
    const raw = localStorage.getItem(LS.history);
    if (raw === null) return [];
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((t): t is ChatTurn => {
        const c = t as Partial<ChatTurn>;
        return (c.role === 'user' || c.role === 'assistant') && typeof c.content === 'string';
      })
      .slice(-40);
  } catch {
    return [];
  }
}

function persist(): void {
  localStorage.setItem(LS.history, JSON.stringify(messages.slice(-40)));
}

watch(
  () => messages.length + (messages.at(-1)?.content.length ?? 0),
  async () => {
    await nextTick();
    const el = listEl.value;
    if (el !== null) el.scrollTop = el.scrollHeight;
  },
);

function friendlyError(err: unknown): string {
  if (err !== null && typeof err === 'object' && 'status' in err) {
    const fail = err as ApiFail;
    const base: Record<number, string> = {
      401: 'API Key 已失效，请联系管理员要一条新的链接',
      429: '请求太频繁，稍后再试',
      504: '超时：操作员没有及时回复这次请求',
      529: '超时：操作员没有及时回复这次请求',
    };
    return base[fail.status] ?? `请求失败（HTTP ${fail.status}）：${fail.message}`;
  }
  return `请求失败：${err instanceof Error ? err.message : String(err)}`;
}

async function parseFail(res: Response): Promise<ApiFail> {
  let message = res.statusText;
  try {
    const body = (await res.json()) as { error?: { message?: string } };
    if (body.error?.message !== undefined) message = body.error.message;
  } catch {
    /* 非 JSON 响应 */
  }
  return { status: res.status, message };
}

async function consumeSse(res: Response, onData: (data: string) => void): Promise<void> {
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buf.indexOf('\n\n')) !== -1) {
        const frame = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        const dataLines = frame
          .split('\n')
          .filter((l) => l.startsWith('data:'))
          .map((l) => l.slice(5).trimStart());
        if (dataLines.length > 0) onData(dataLines.join('\n'));
      }
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* ignore */
    }
  }
}

/**
 * 固定走 OpenAI 格式 + 流式。
 * 页面上没有协议/流式的开关了；请求里也不带 model，由服务端回落到操作台配置的模型名。
 */
async function callGateway(payload: ChatTurn[], target: ChatTurn, signal: AbortSignal): Promise<void> {
  const res = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey.value.trim()}` },
    body: JSON.stringify({ messages: payload, stream: true }),
    signal,
  });
  if (!res.ok) throw await parseFail(res);

  await consumeSse(res, (dataStr) => {
    if (dataStr === '[DONE]') return;
    const j = JSON.parse(dataStr) as {
      error?: { message?: string };
      choices?: Array<{ delta?: { content?: string } }>;
    };
    if (j.error !== undefined) throw { status: 504, message: j.error.message ?? '流中断' } satisfies ApiFail;
    const delta = j.choices?.[0]?.delta?.content;
    if (typeof delta === 'string') target.content += delta;
  });
}

async function send(): Promise<void> {
  if (!canSend.value) return;
  const text = input.value;
  input.value = '';
  autoGrow();
  error.value = '';
  messages.push({ role: 'user', content: text });
  const payload = messages.filter((m) => m.content !== '');
  const assistant = reactive<ChatTurn>({ role: 'assistant', content: '' });
  messages.push(assistant);
  sending.value = true;
  abortCtrl = new AbortController();

  try {
    await callGateway(payload, assistant, abortCtrl.signal);
    if (assistant.content === '') {
      messages.pop();
      error.value = '操作员回复为空，请重试';
    }
  } catch (err) {
    if (assistant.content === '') messages.pop();
    if (err instanceof DOMException && err.name === 'AbortError') {
      error.value = '已取消这次请求';
    } else {
      error.value = friendlyError(err);
    }
  } finally {
    sending.value = false;
    abortCtrl = null;
    persist();
  }
}

function stop(): void {
  abortCtrl?.abort();
}

function clearHistory(): void {
  if (messages.length > 0 && !confirm('清空本页对话记录？')) return;
  messages.splice(0, messages.length);
  error.value = '';
  persist();
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    void send();
  }
}

/** 输入框随内容长高（上限由 CSS 的 max-height 兜住） */
function autoGrow(): void {
  const el = taEl.value;
  if (el === null) return;
  el.style.height = 'auto';
  el.style.height = `${el.scrollHeight}px`;
}

function useTip(text: string): void {
  input.value = text;
  void nextTick(() => taEl.value?.focus());
}

document.title = 'ChatShy · 对话';
</script>

<template>
  <div class="db-page">
    <header class="db-top">
      <div class="db-top-left">
        <button class="db-top-btn" title="清空本页对话记录" @click="clearHistory">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6" />
          </svg>
          清空对话
        </button>
        <div class="db-brand"><span class="db-brand-dot"></span>ChatShy</div>
      </div>
    </header>

    <main ref="listEl" class="db-main">
      <div class="db-col">
        <div v-if="isEmpty" class="db-welcome">
          <h1>你好，我是 ChatShy</h1>
          <p>你的消息会发给真人操作员，回复需要等一会儿</p>
          <div class="db-tips">
            <button v-for="t in TIPS" :key="t" class="db-tip" @click="useTip(t)">{{ t }}</button>
          </div>
        </div>

        <div v-for="(m, i) in messages" :key="i" class="db-msg" :class="m.role">
          <div v-if="m.role === 'assistant'" class="db-avatar">C</div>
          <div class="db-bubble">
            <template v-if="m.content !== ''">
              <div class="db-md" v-html="renderMarkdown(m.content)"></div>
            </template>
            <template v-else-if="sending && i === messages.length - 1">
              <span class="db-thinking"><i></i><i></i><i></i></span>
            </template>
          </div>
        </div>

        <p v-if="error !== ''" class="db-error">{{ error }}</p>
      </div>
    </main>

    <div class="db-composer-wrap">
      <p v-if="!hasKey" class="db-nokey">
        这个链接里没有 API Key。请找管理员要一条带 Key 的完整访问链接。
      </p>
      <div class="db-composer">
        <textarea
          ref="taEl"
          v-model="input"
          class="db-ta"
          rows="1"
          :disabled="sending || !hasKey"
          placeholder="给 ChatShy 发消息"
          @keydown="onKeydown"
          @input="autoGrow"
        ></textarea>
        <div class="db-tools">
          <button v-if="!sending" class="db-send" :disabled="!canSend" title="发送" @click="send">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 19V5M5 12l7-7 7 7" />
            </svg>
          </button>
          <button v-else class="db-send stop" title="停止" @click="stop">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <rect x="7" y="7" width="10" height="10" rx="1.5" />
            </svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
