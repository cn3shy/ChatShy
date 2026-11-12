<script setup lang="ts">
import { deleteThread, remainingSeconds } from '../store';
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

async function onDelete(t: ThreadSummary): Promise<void> {
  const extra =
    t.pendingCount > 0 ? '\n\n这条会话还有挂起中的请求，删除后调用方会立刻收到超时错误。' : '';
  if (!confirm(`删除会话「${t.title || '无标题'}」？\n消息与请求记录一并删除，不可恢复。${extra}`)) return;
  try {
    await deleteThread(t.id);
  } catch {
    alert('删除失败，请刷新页面后重试');
  }
}
</script>

<template>
  <div class="thread-list">
    <div v-if="threads.length === 0" class="empty-hint">暂无会话</div>
    <!-- 用 div 而非 button：删除按钮要嵌在行内，button 套 button 是非法的 HTML -->
    <div
      v-for="t in threads"
      :key="t.id"
      class="thread-item"
      :class="{ selected: t.id === selectedId, pending: t.pendingCount > 0 }"
      role="button"
      tabindex="0"
      @click="$emit('select', t.id)"
      @keydown.enter.prevent="$emit('select', t.id)"
      @keydown.space.prevent="$emit('select', t.id)"
    >
      <div class="row1">
        <span class="title">{{ t.title || '（无标题）' }}</span>
        <span v-if="t.pendingCount > 0 && t.pendingDeadlineAt !== null" class="countdown">
          ⏳ {{ remainingSeconds(t.pendingDeadlineAt, now) }}s
        </span>
        <button class="thread-del" title="删除这条会话" @click.stop="onDelete(t)">✕</button>
      </div>
      <div class="row2">
        <span class="tag source" :title="t.sourceUa">{{ t.sourceClient || '未知来源' }}</span>
        <span class="tag ip">{{ t.sourceIp }}</span>
        <span class="time">{{ timeAgo(t.updatedAt, now) }}</span>
      </div>
      <div class="preview">{{ t.lastMessage }}</div>
    </div>
  </div>
</template>
