<script setup lang="ts">
import type { Conversation } from '../types';

defineProps<{
  conversations: Conversation[];
  currentId: string | null;
  open: boolean;
}>();

defineEmits<{
  'new-chat': [];
  select: [id: string];
  delete: [id: string];
}>();
</script>

<template>
  <aside class="sidebar" :class="{ open }">
    <div class="sidebar-head">
      <img class="brand-avatar" src="/icon.jpg" alt="豆奶" />
      <div class="brand-text">
        <span class="brand-name">豆奶</span>
        <span class="brand-sub">OpenAI 兼容对话</span>
      </div>
    </div>

    <button class="btn-new-chat" @click="$emit('new-chat')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
        <path d="M12 5v14M5 12h14" />
      </svg>
      新对话
    </button>

    <div class="conv-title">历史对话</div>
    <nav class="conv-list">
      <div v-if="conversations.length === 0" class="conv-empty">
        还没有对话记录,点上方「新对话」开聊吧 ~
      </div>
      <div
        v-for="conversation in conversations"
        :key="conversation.id"
        class="conv"
        :class="{ active: conversation.id === currentId }"
        @click="$emit('select', conversation.id)"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
        <span class="title">{{ conversation.title }}</span>
        <button class="del" title="删除" @click.stop="$emit('delete', conversation.id)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z" />
          </svg>
        </button>
      </div>
    </nav>

    <div class="sidebar-foot">
      <div class="sidebar-ver">dounai.3shy.cn</div>
    </div>
  </aside>
</template>
