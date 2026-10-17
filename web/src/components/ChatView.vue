<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { renderMarkdown } from '../markdown';
import { forwardJob, ignoreJob, remainingSeconds, reply, state } from '../store';
import type { ThreadDetail } from '../types';
import Composer from './Composer.vue';

const props = defineProps<{ detail: ThreadDetail; now: number }>();

const pendingJob = computed(() => props.detail.pendingJobs.at(-1) ?? null);
const pendingCount = computed(() => props.detail.pendingJobs.length);
const canReply = computed(() => pendingJob.value !== null);

const listEl = ref<HTMLElement | null>(null);
const selectedProviderId = ref('');
const forwarding = ref(false);
const forwardError = ref('');
const replyError = ref('');
const providerIds = computed(() => state.providers.map((p) => p.id).join(','));

/** 默认选中自动转发配置的厂商；它被删掉时回落到第一个 */
watch(
  [providerIds, () => state.settings?.autoForwardProviderId],
  () => {
    if (state.providers.some((p) => p.id === selectedProviderId.value)) return;
    const autoId = state.settings?.autoForwardProviderId ?? '';
    selectedProviderId.value = state.providers.some((p) => p.id === autoId)
      ? autoId
      : (state.providers[0]?.id ?? '');
  },
  { immediate: true },
);

// 换了一个待回复请求就清掉上一条的手动转发错误
watch(
  () => pendingJob.value?.id,
  () => {
    forwardError.value = '';
    replyError.value = '';
  },
);

const forwardErrorText = computed(() => {
  if (forwardError.value !== '') return forwardError.value;
  const autoError = pendingJob.value?.forwardError ?? '';
  return autoError === '' ? '' : `最近一次自动转发失败：${autoError}`;
});

async function onForward(): Promise<void> {
  const job = pendingJob.value;
  if (job === null || selectedProviderId.value === '' || forwarding.value) return;
  forwarding.value = true;
  forwardError.value = '';
  const res = await forwardJob(job.id, selectedProviderId.value);
  forwarding.value = false;
  if (!res.ok) forwardError.value = res.error ?? '转发失败';
}

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
  if (pendingJob.value === null) return;
  const res = await reply(text);
  replyError.value = res?.error ?? '';
}

function senderLabel(m: ThreadDetail['messages'][number]): string {
  if (m.role === 'user') return `调用方 · ${props.detail.thread.sourceClient || '未知来源'}`;
  if (m.role === 'assistant') return m.via !== '' ? `AI · ${m.via}` : '我（操作员）';
  return 'System';
}
</script>

<template>
  <div class="chat">
    <header class="chat-head">
      <div>
        <div class="chat-title">{{ detail.thread.title || '（无标题）' }}</div>
        <div class="chat-sub">
          <span class="tag source" :title="detail.thread.sourceUa">
            {{ detail.thread.sourceClient || '未知来源' }}
          </span>
          <span class="tag ip">{{ detail.thread.sourceIp }}</span>
          <span class="tag">{{ detail.thread.protocol }}</span>
          <span v-if="detail.thread.model" class="tag">{{ detail.thread.model }}</span>
        </div>
      </div>
    </header>

    <div ref="listEl" class="messages">
      <div v-if="detail.messages.length === 0" class="empty-hint">这个会话还没有消息</div>
      <div
        v-for="m in detail.messages"
        :key="m.id"
        class="msg"
        :class="[m.role, m.source, { error: !m.delivered }]"
      >
        <div class="msg-meta">
          <span class="msg-sender">{{ senderLabel(m) }}</span>
          <span class="msg-time">{{ new Date(m.createdAt).toLocaleTimeString() }}</span>
          <span v-if="m.role === 'assistant' && !m.delivered" class="undelivered">
            未送达（请求已超时）
          </span>
        </div>
        <div class="msg-body" v-html="renderMarkdown(m.content)"></div>
      </div>
    </div>

    <template v-if="pendingJob !== null">
      <div class="pending-bar">
        <span class="pulse"></span>
        <span class="pending-status">
          正在等待你的回复… 剩余 <b>{{ remainingSeconds(pendingJob.deadlineAt, now) }}</b> 秒
          <template v-if="pendingCount > 1">
            （有 {{ pendingCount }} 个重复挂起请求，回复将发给最新的一个）
          </template>
        </span>
        <div v-if="state.providers.length > 0" class="forward-controls">
          <select v-model="selectedProviderId" class="provider-select" :disabled="forwarding">
            <option v-for="p in state.providers" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <button
            class="ghost-btn small ai-reply-btn"
            :disabled="forwarding || selectedProviderId === ''"
            :title="'调用所选厂商并直接回复调用方'"
            @click="onForward"
          >
            {{ forwarding ? '生成中…' : 'AI 回复' }}
          </button>
        </div>
        <button class="ghost-btn ignore-btn" @click="ignoreJob(pendingJob.id)">忽略</button>
      </div>
      <div v-if="forwardErrorText !== ''" class="forward-error-row">{{ forwardErrorText }}</div>
    </template>

    <div v-if="replyError !== ''" class="forward-error-row">{{ replyError }}</div>

    <div class="composer-wrap">
      <Composer
        :disabled="!canReply"
        :placeholder="
          canReply ? '输入你的回复（Enter 发送，Shift+Enter 换行，支持 Markdown）' : '当前没有等待回复的请求'
        "
        @send="onSend"
      />
    </div>
  </div>
</template>
