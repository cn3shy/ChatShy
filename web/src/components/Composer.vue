<script setup lang="ts">
import { ref } from 'vue';

const props = defineProps<{ disabled: boolean; placeholder: string }>();
const emit = defineEmits<{ send: [text: string] }>();

const text = ref('');

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    send();
  }
}

function send(): void {
  const v = text.value;
  if (props.disabled || v.trim() === '') return;
  emit('send', v);
  text.value = '';
}
</script>

<template>
  <div class="composer">
    <textarea
      v-model="text"
      :disabled="disabled"
      :placeholder="placeholder"
      rows="3"
      @keydown="onKeydown"
    ></textarea>
    <button class="send-btn" :disabled="disabled || text.trim() === ''" @click="send">发送</button>
  </div>
</template>
