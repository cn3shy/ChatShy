<script setup lang="ts">
import { remainingSeconds } from '../store';
import type { ThreadSummary } from '../types';

defineProps<{ threads: ThreadSummary[]; selectedId: string | null; now: number }>();
defineEmits<{ select: [id: string] }>();

function timeAgo(ts: number, now: number): string {
  const s = Math.max(0, Math.floor((now - ts) / 1000));
  if (s < 60) return '刚刚';
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`;
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`;
  return new Date(ts).toLocaleDateString();
}
</script>

<template>
  <div class="thread-list">
    <div v-if="threads.length === 0" class="empty-hint">暂无会话</div>
    <button
      v-for="t in threads"
      :key="t.id"
      class="thread-item"
      :class="{ selected: t.id === selectedId, pending: t.pendingCount > 0 }"
      @click="$emit('select', t.id)"
    >
      <div class="row1">
        <span class="title">{{ t.title || '（无标题）' }}</span>
        <span v-if="t.pendingCount > 0 && t.pendingDeadlineAt !== null" class="countdown">
          ⏳ {{ remainingSeconds(t.pendingDeadlineAt, now) }}s
        </span>
      </div>
      <div class="row2">
        <span class="tag source" :title="t.sourceUa">{{ t.sourceClient || '未知来源' }}</span>
        <span class="tag ip">{{ t.sourceIp }}</span>
        <span class="time">{{ timeAgo(t.updatedAt, now) }}</span>
      </div>
      <div class="preview">{{ t.lastMessage }}</div>
    </button>
  </div>
</template>
