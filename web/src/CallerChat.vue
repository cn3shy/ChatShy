<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
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
  protocol: 'chatshy.caller.protocol',
  stream: 'chatshy.caller.stream',
  history: 'chatshy.caller.history',
};

const apiKey = ref(localStorage.getItem(LS.key) ?? '');
// 不设模型名：请求里干脆不带 model 字段，由服务端回落到操作台配置的模型名
const protocol = ref<'openai' | 'anthropic'>(
  localStorage.getItem(LS.protocol) === 'anthropic' ? 'anthropic' : 'openai',
);
const stream = ref(localStorage.getItem(LS.stream) !== '0');

const messages = reactive<ChatTurn[]>(loadHistory());
const input = ref('');
const sending = ref(false);
const error = ref('');
const showSettings = ref(false);
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
  localStorage.setItem(LS.key, apiKey.value);
  localStorage.setItem(LS.protocol, protocol.value);
  localStorage.setItem(LS.stream, stream.value ? '1' : '0');
  localStorage.setItem(LS.history, JSON.stringify(messages.slice(-40)));
}

watch([apiKey, protocol, stream], persist);
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
      401: 'API Key 无效，请点右上角 ⚙ 检查',
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

async function callOpenAI(payload: ChatTurn[], target: ChatTurn, signal: AbortSignal): Promise<void> {
  const res = await fetch('/v1/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey.value.trim()}` },
    body: JSON.stringify({ messages: payload, stream: stream.value }),
    signal,
  });
  if (!res.ok) throw await parseFail(res);

  if (!stream.value) {
    const body = (await res.json()) as { choices?: Array<{ message?: { content?: string } }> };
    target.content = body.choices?.[0]?.message?.content ?? '';
    return;
  }
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

async function callAnthropic(payload: ChatTurn[], target: ChatTurn, signal: AbortSignal): Promise<void> {
  const res = await fetch('/v1/messages', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'x-api-key': apiKey.value.trim(),
      'anthropic-version': '2023-06-01',
    },
    body: JSON.stringify({
      max_tokens: 4096,
      messages: payload,
      stream: stream.value,
    }),
    signal,
  });
  if (!res.ok) throw await parseFail(res);

  if (!stream.value) {
    const body = (await res.json()) as { content?: Array<{ type?: string; text?: string }> };
    target.content = (body.content ?? [])
      .filter((b) => b.type === 'text')
      .map((b) => b.text ?? '')
      .join('');
    return;
  }
  await consumeSse(res, (dataStr) => {
    const j = JSON.parse(dataStr) as {
      type?: string;
      delta?: { type?: string; text?: string };
      error?: { message?: string };
    };
    if (j.type === 'content_block_delta' && j.delta?.type === 'text_delta') {
      target.content += j.delta.text ?? '';
    } else if (j.type === 'error') {
      throw { status: 529, message: j.error?.message ?? '流中断' } satisfies ApiFail;
    }
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
    if (protocol.value === 'openai') await callOpenAI(payload, assistant, abortCtrl.signal);
    else await callAnthropic(payload, assistant, abortCtrl.signal);
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

onMounted(() => {
  document.title = 'ChatShy · 对话';
  // 还没配 Key 就直接把设置摊开，省得用户对着输入框发不出去
  if (!hasKey.value) showSettings.value = true;
});
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
      <button class="db-icon-btn" title="设置" @click="showSettings = true">⚙</button>
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
      <div class="db-composer">
        <textarea
          ref="taEl"
          v-model="input"
          class="db-ta"
          rows="1"
          :disabled="sending"
          placeholder="给 ChatShy 发消息"
          @keydown="onKeydown"
          @input="autoGrow"
        ></textarea>
        <div class="db-tools">
          <button class="db-pill" @click="showSettings = true">
            {{ protocol === 'openai' ? 'OpenAI 格式' : 'Anthropic 格式' }}<template v-if="stream">
              · 流式</template
            >
          </button>
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
        <p v-if="!hasKey" class="db-hint">先点右上角 ⚙ 填上 API Key 才能发送</p>
      </div>
    </div>

    <div v-if="showSettings" class="db-mask" @click.self="showSettings = false">
      <div class="db-modal">
        <h3>设置</h3>
        <label class="db-field">
          <span>API Key（找管理员要）</span>
          <input v-model="apiKey" type="password" placeholder="sk-chatshy-…" autocomplete="off" />
        </label>
        <label class="db-field">
          <span>协议</span>
          <select v-model="protocol">
            <option value="openai">OpenAI 格式</option>
            <option value="anthropic">Anthropic 格式</option>
          </select>
        </label>
        <label class="db-check"><input v-model="stream" type="checkbox" /> 流式接收</label>
        <div class="db-modal-actions">
          <button class="db-btn primary" @click="showSettings = false">完成</button>
        </div>
        <p class="db-note">API Key 与对话记录只存在你这台设备的浏览器里。</p>
      </div>
    </div>
  </div>
</template>
