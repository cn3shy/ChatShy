<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue';
import type { ChatMessage } from '../types';
import MessageItem from './MessageItem.vue';

const props = defineProps<{ messages: ChatMessage[]; streaming: boolean }>();
defineEmits<{ regenerate: [] }>();

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
      @regenerate="$emit('regenerate')"
    />
  </section>
</template>
