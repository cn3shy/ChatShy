<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';

const props = defineProps<{ streaming: boolean }>();
const emit = defineEmits<{ send: [text: string]; stop: [] }>();

const text = ref('');
const textarea = ref<HTMLTextAreaElement | null>(null);

function autosize(): void {
  const el = textarea.value;
  if (el === null) return;
  el.style.height = 'auto';
  el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
}

function focus(): void {
  textarea.value?.focus();
}

function fill(value: string): void {
  text.value = value;
  void nextTick(() => {
    autosize();
    focus();
  });
}

function send(): void {
  if (props.streaming) return;
  const value = text.value.trim();
  if (value === '') return;
  emit('send', value);
  text.value = '';
  void nextTick(autosize);
}

function onClick(): void {
  if (props.streaming) emit('stop');
  else send();
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    send();
  }
}

onMounted(autosize);

defineExpose({ fill, focus });
</script>

<template>
  <div class="composer">
    <div class="composer-box">
      <textarea
        ref="textarea"
        v-model="text"
        rows="1"
        placeholder="给豆奶发消息…(Enter 发送,Shift + Enter 换行)"
        @input="autosize"
        @keydown="onKeydown"
      ></textarea>
      <button
        class="btn-send"
        :class="{ streaming }"
        :title="streaming ? '停止生成' : '发送'"
        :disabled="!streaming && text.trim() === ''"
        @click="onClick"
      >
        <svg class="ic-send" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
        <svg class="ic-stop" viewBox="0 0 24 24" fill="currentColor">
          <rect x="7" y="7" width="10" height="10" rx="2" />
        </svg>
      </button>
    </div>
    <div class="composer-hint">内容由 AI 生成,请注意甄别</div>
  </div>
</template>
