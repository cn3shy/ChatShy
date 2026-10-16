<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { renderMarkdown } from '../markdown';
import { ignoreJob, remainingSeconds, reply, sendConsoleMessage } from '../store';
import type { ThreadDetail } from '../types';
import Composer from './Composer.vue';

const props = defineProps<{ detail: ThreadDetail; now: number }>();

const pendingJob = computed(() => props.detail.pendingJobs.at(-1) ?? null);
const pendingCount = computed(() => props.detail.pendingJobs.length);
const isConsole = computed(() => props.detail.thread.protocol === 'console');
const canReply = computed(() => pendingJob.value !== null || isConsole.value);

const consoleRole = ref<'assistant' | 'user'>('assistant');

const listEl = ref<HTMLElement | null>(null);

watch(
  () => [props.detail.messages.length, props.detail.pendingJobs.length, props.detail.thread.id],
  async () => {
    await nextTick();
    const el = listEl.value;
    if (el !== null) el.scrollTop = el.scrollHeight;
  },
  { immediate: true },
);

async function onSend(text: string): Promise<void> {
  const job = pendingJob.value;
  if (job !== null) {
    await reply(text);
  } else if (isConsole.value) {
    await sendConsoleMessage(consoleRole.value, text);
  }
}

function senderLabel(m: ThreadDetail['messages'][number]): string {
  if (m.role === 'user') return `调用方 · ${props.detail.thread.keyName ?? '控制台'}`;
  if (m.role === 'assistant') return '我（操作员）';
  return 'System';
}
</script>

<template>
  <div class="chat">
    <header class="chat-head">
      <div>
        <div class="chat-title">{{ detail.thread.title || '（无标题）' }}</div>
        <div class="chat-sub">
          <span class="tag">{{ detail.thread.keyName ?? '控制台' }}</span>
          <span class="tag">{{ detail.thread.protocol }}</span>
          <span v-if="detail.thread.model" class="tag">{{ detail.thread.model }}</span>
        </div>
      </div>
    </header>

    <div ref="listEl" class="messages">
      <div v-if="detail.messages.length === 0" class="empty-hint">
        还没有消息——在下面直接发消息，这就是不经过 API 的测试窗口
      </div>
      <div
        v-for="m in detail.messages"
        :key="m.id"
        class="msg"
        :class="[m.role, m.source, { error: !m.delivered }]"
      >
        <div class="msg-meta">
          <span class="msg-sender">{{ senderLabel(m) }}</span>
          <span class="msg-time">{{ new Date(m.createdAt).toLocaleTimeString() }}</span>
          <span v-if="m.role === 'assistant' && m.source === 'console' && !m.delivered" class="undelivered">
            未送达（请求已超时）
          </span>
        </div>
        <div class="msg-body" v-html="renderMarkdown(m.content)"></div>
      </div>
    </div>

    <div v-if="pendingJob !== null" class="pending-bar">
      <span class="pulse"></span>
      <span>
        正在等待你的回复… 剩余 <b>{{ remainingSeconds(pendingJob.deadlineAt, now) }}</b> 秒
        <template v-if="pendingCount > 1">
          （有 {{ pendingCount }} 个重复挂起请求，回复将发给最新的一个）
        </template>
      </span>
      <button class="ghost-btn" @click="ignoreJob(pendingJob.id)">忽略</button>
    </div>

    <div class="composer-wrap">
      <div v-if="isConsole && pendingJob === null" class="role-toggle">
        <label><input v-model="consoleRole" type="radio" value="assistant" /> 以“我”的身份发</label>
        <label><input v-model="consoleRole" type="radio" value="user" /> 模拟调用方</label>
      </div>
      <Composer
        :disabled="!canReply"
        :placeholder="
          canReply
            ? isConsole
              ? '输入消息（Enter 发送，Shift+Enter 换行，支持 Markdown）'
              : '输入你的回复（Enter 发送，Shift+Enter 换行，支持 Markdown）'
            : '当前没有等待回复的请求'
        "
        @send="onSend"
      />
    </div>
  </div>
</template>
