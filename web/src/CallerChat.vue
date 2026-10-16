<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue';
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
  model: 'chatshy.caller.model',
  protocol: 'chatshy.caller.protocol',
  stream: 'chatshy.caller.stream',
  history: 'chatshy.caller.history',
};

const apiKey = ref(localStorage.getItem(LS.key) ?? '');
const model = ref(localStorage.getItem(LS.model) ?? 'chatshy');
const protocol = ref<'openai' | 'anthropic'>(
  localStorage.getItem(LS.protocol) === 'anthropic' ? 'anthropic' : 'openai',
);
const stream = ref(localStorage.getItem(LS.stream) !== '0');

const messages = reactive<ChatTurn[]>(loadHistory());
const input = ref('');
const sending = ref(false);
const error = ref('');
const listEl = ref<HTMLElement | null>(null);
let abortCtrl: AbortController | null = null;

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
  localStorage.setItem(LS.model, model.value);
  localStorage.setItem(LS.protocol, protocol.value);
  localStorage.setItem(LS.stream, stream.value ? '1' : '0');
  localStorage.setItem(LS.history, JSON.stringify(messages.slice(-40)));
}

watch([apiKey, model, protocol, stream], persist);
watch(
  () => messages.length + (messages.at(-1)?.content.length ?? 0),
  async () => {
    await nextTick();
    const el = listEl.value;
    if (el !== null) el.scrollTop = el.scrollHeight;
  },
);

const canSend = computed(() => !sending.value && input.value.trim() !== '' && apiKey.value.trim() !== '');

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    void send();
  }
}

function friendlyError(err: unknown): string {
  if (err !== null && typeof err === 'object' && 'status' in err) {
    const fail = err as ApiFail;
    const base: Record<number, string> = {
      401: 'API Key 无效或已禁用，请检查顶部填写',
      429: '触发限额（日限额或并发上限），可稍后再试',
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
    body: JSON.stringify({ model: model.value.trim() || 'chatshy', messages: payload, stream: stream.value }),
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
      model: model.value.trim() || 'chatshy',
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
</script>

<template>
  <div class="caller-page">
    <header class="caller-head">
      <div class="logo"><span class="logo-dot"></span>ChatShy · 调用方</div>
      <a class="ghost-btn" href="/">操作台入口 →</a>
    </header>

    <div class="caller-bar">
      <input
        v-model="apiKey"
        type="password"
        class="key-input"
        placeholder="API Key（sk-chatshy-…）"
        autocomplete="off"
      />
      <select v-model="protocol">
        <option value="openai">OpenAI 格式</option>
        <option value="anthropic">Anthropic 格式</option>
      </select>
      <input v-model="model" class="model-input" placeholder="模型名" />
      <label class="stream-toggle"><input v-model="stream" type="checkbox" /> 流式</label>
      <button class="ghost-btn" @click="clearHistory">清空对话</button>
    </div>

    <div ref="listEl" class="messages">
      <div v-if="messages.length === 0" class="empty-hint">
        填写上方 API Key 开始聊天。<br />
        消息会发到操作台由真人回复（人工回复需要一点时间，界面会提示等待状态）。<br />
        Key 请找 ChatShy 管理员生成，形如 <code>sk-chatshy-…</code>。
      </div>
      <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <div class="msg-meta">
          <span>{{ m.role === 'user' ? '我（调用方）' : '操作员' }}</span>
        </div>
        <div class="msg-body">
          <template v-if="m.content !== ''"><div v-html="renderMarkdown(m.content)"></div></template>
          <template v-else-if="sending && i === messages.length - 1">
            <span class="waiting"><span class="pulse"></span>请求已发出，等待操作员回复…</span>
          </template>
        </div>
      </div>
      <p v-if="error !== ''" class="caller-error">{{ error }}</p>
    </div>

    <div class="composer-wrap">
      <div class="composer">
        <textarea
          v-model="input"
          :disabled="sending"
          placeholder="输入消息（Enter 发送，Shift+Enter 换行）"
          rows="3"
          @keydown="onKeydown"
        ></textarea>
        <button v-if="!sending" class="send-btn" :disabled="!canSend" @click="send">发送</button>
        <button v-else class="send-btn stop" @click="stop">停止</button>
      </div>
      <p v-if="apiKey.trim() === ''" class="caller-hint">先填写 API Key 才能发送</p>
    </div>
  </div>
</template>
