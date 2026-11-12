<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { MAX_RETRIES } from '../store';
import type { ChatMessage } from '../types';
import MessageItem from './MessageItem.vue';

const props = defineProps<{ messages: ChatMessage[]; streaming: boolean }>();
defineEmits<{ regenerate: [] }>();

/** 最近一条用户消息的重试次数达到上限后，隐藏重试/重新生成按钮。 */
const retryBlocked = computed(() => {
  const messages = props.messages;
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]!.role === 'user') return (messages[i]!.retries ?? 0) >= MAX_RETRIES;
  }
  return false;
});

const listEl = ref<HTMLElement | null>(null);
const stickToBottom = ref(true);

function onScroll(): void {
  const el = listEl.value;
  if (el === null) return;
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 140;
}

function scrollToBottom(): void {
  const el = listEl.value;
  if (el !== null) el.scrollTop = el.scrollHeight;
}

onMounted(() => nextTick(scrollToBottom));

watch(
  () => props.messages.length,
  () => nextTick(scrollToBottom),
);

watch(
  () => props.messages.map((m) => `${m.content ?? ''}\u0000${m.reasoning ?? ''}`).join('\u0001'),
  () => {
    if (stickToBottom.value) nextTick(scrollToBottom);
  },
);
</script>

<template>
  <section ref="listEl" class="chat" @scroll="onScroll">
    <MessageItem
      v-for="(message, index) in messages"
      :key="index"
      :message="message"
      :is-last-assistant="index === messages.length - 1 && message.role === 'assistant'"
      :is-streaming="streaming && index === messages.length - 1
        && message.role === 'assistant' && !message.error"
      :retry-blocked="retryBlocked"
      @regenerate="$emit('regenerate')"
    />
  </section>
</template>
