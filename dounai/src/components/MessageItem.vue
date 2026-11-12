<script setup lang="ts">
import { computed, ref } from 'vue';
import { copyText } from '../clipboard';
import { renderMarkdown } from '../markdown';
import type { ChatMessage } from '../types';

const props = defineProps<{
  message: ChatMessage;
  isLastAssistant: boolean;
  isStreaming: boolean;
  /** 重试次数已达上限时隐藏重试/重新生成按钮。 */
  retryBlocked?: boolean;
}>();

defineEmits<{ regenerate: [] }>();

const html = computed(() => renderMarkdown(props.message.content || ''));
const copyLabel = ref('复制');

async function onCopy(): Promise<void> {
  const text = props.message.content
    || (props.message.error ? `⚠️ ${props.message.errorMsg || '出错了,请重试'}` : '');
  copyLabel.value = (await copyText(text)) ? '已复制' : '复制失败';
  setTimeout(() => { copyLabel.value = '复制'; }, 1500);
}

async function onBubbleClick(event: MouseEvent): Promise<void> {
  const target = event.target as HTMLElement | null;
  const button = target?.closest('.code-copy') as HTMLButtonElement | null;
  if (!button) return;
  const code = button.closest('pre')?.querySelector('code');
  if (!code) return;
  const ok = await copyText(code.textContent || '');
  button.textContent = ok ? '已复制' : '复制失败';
  button.classList.toggle('done', ok);
  setTimeout(() => {
    button.textContent = '复制';
    button.classList.remove('done');
  }, 1500);
}
</script>

<template>
  <div class="msg" :class="message.role">
    <div class="avatar" :class="{ 'user-avatar': message.role === 'user' }">
      <svg v-if="message.role === 'user'" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 12a5 5 0 1 0-5-5 5 5 0 0 0 5 5zm0 2c-4 0-8 2-8 5v1h16v-1c0-3-4-5-8-5z" />
      </svg>
      <img v-else src="/icon.jpg" alt="豆奶" />
    </div>

    <div class="bubble-wrap">
      <div class="bubble" :class="{ error: message.error }" @click="onBubbleClick">
        <span v-if="message.error" class="err-msg">⚠️ {{ message.errorMsg || '出错了,请重试' }}</span>
        <template v-else-if="message.role === 'user'">{{ message.content }}</template>
        <template v-else>
          <details v-if="message.reasoning" class="reasoning" :open="!message.content">
            <summary>思考过程</summary>
            <div class="reasoning-body">{{ message.reasoning }}</div>
          </details>
          <div class="md" v-html="html"></div>
          <span v-if="isStreaming" class="cursor"></span>
        </template>
      </div>

      <div class="msg-actions">
        <button class="act-btn act-copy" @click="onCopy">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="9" y="9" width="13" height="13" rx="2" />
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
          </svg>
          {{ copyLabel }}
        </button>
        <button
          v-if="isLastAssistant && !message.error && !retryBlocked"
          class="act-btn act-regen"
          @click="$emit('regenerate')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M23 4v6h-6M1 20v-6h6" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
          重新生成
        </button>
        <button v-if="message.error && !retryBlocked" class="act-btn act-regen" @click="$emit('regenerate')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M23 4v6h-6M1 20v-6h6" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
          重试
        </button>
      </div>
    </div>
  </div>
</template>
