<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { blacklistThread, deleteThread, deleteThreads, remainingSeconds } from '../store';
import type { ThreadSummary } from '../types';

const props = defineProps<{ threads: ThreadSummary[]; selectedId: string | null; now: number }>();
const emit = defineEmits<{ select: [id: string] }>();

const batchMode = ref(false);
const selected = ref<Set<string>>(new Set());
const allSelected = computed(() =>
  props.threads.length > 0 && props.threads.every((t) => selected.value.has(t.id)));
const selectedThreads = computed(() => props.threads.filter((t) => selected.value.has(t.id)));
const pendingSelected = computed(() =>
  selectedThreads.value.filter((t) => t.pendingCount > 0).length);

// 会话被 SSE 删除或列表刷新后，清掉已经不存在的选中项
watch(() => props.threads, (threads) => {
  const alive = new Set(threads.map((t) => t.id));
  const next = new Set([...selected.value].filter((id) => alive.has(id)));
  if (next.size !== selected.value.size) selected.value = next;
});

function timeAgo(ts: number, now: number): string {
  const s = Math.max(0, Math.floor((now - ts) / 1000));
  if (s < 60) return '刚刚';
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`;
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`;
  return new Date(ts).toLocaleDateString();
}

function toggleBatch(): void {
  batchMode.value = !batchMode.value;
  if (!batchMode.value) selected.value = new Set();
}

function toggle(id: string): void {
  const next = new Set(selected.value);
  if (next.has(id)) next.delete(id); else next.add(id);
  selected.value = next;
}

function toggleAll(): void {
  selected.value = allSelected.value
    ? new Set()
    : new Set(props.threads.map((t) => t.id));
}

function onRowClick(id: string): void {
  if (batchMode.value) toggle(id);
  else emit('select', id);
}

async function onBatchDelete(): Promise<void> {
  const items = selectedThreads.value;
  if (items.length === 0) return;
  const extra = pendingSelected.value > 0
    ? `\n\n其中 ${pendingSelected.value} 条有挂起中的请求，删除后调用方会立刻收到超时错误。`
    : '';
  if (!confirm(`删除选中的 ${items.length} 条会话？\n消息与请求记录一并删除，不可恢复。${extra}`)) return;
  try {
    const res = await deleteThreads(items.map((t) => t.id));
    selected.value = new Set();
    batchMode.value = false;
    if (res.deleted < items.length) {
      alert(`已删除 ${res.deleted} 条，${items.length - res.deleted} 条未找到`);
    }
  } catch {
    alert('批量删除失败，请刷新页面后重试');
  }
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

async function onBlacklist(t: ThreadSummary): Promise<void> {
  if (t.blacklisted) {
    try {
      await blacklistThread(t.id, false);
    } catch {
      alert('解除拉黑失败，请刷新页面后重试');
    }
    return;
  }
  if (!confirm(`拉黑会话「${t.title || '无标题'}」？\n拉黑后该会话的新请求会被直接拒绝（403），随时可解除。`)) return;
  try {
    await blacklistThread(t.id, true);
  } catch {
    alert('拉黑失败，请刷新页面后重试');
  }
}
</script>

<template>
  <div class="thread-toolbar">
    <button v-if="!batchMode" class="ghost-btn small" @click="toggleBatch">批量管理</button>
    <template v-else>
      <label class="batch-all">
        <input type="checkbox" :checked="allSelected" @change="toggleAll" />
        全选
      </label>
      <button class="ghost-btn small danger" :disabled="selected.size === 0" @click="onBatchDelete">
        删除选中 ({{ selected.size }})
      </button>
      <button class="ghost-btn small" @click="toggleBatch">退出</button>
    </template>
  </div>

  <div class="thread-list">
    <div v-if="threads.length === 0" class="empty-hint">暂无会话</div>
    <!-- 用 div 而非 button：删除按钮要嵌在行内，button 套 button 是非法的 HTML -->
    <div
      v-for="t in threads"
      :key="t.id"
      class="thread-item"
      :class="{ selected: t.id === selectedId, pending: t.pendingCount > 0, blacklisted: t.blacklisted, checked: selected.has(t.id) }"
      role="button"
      tabindex="0"
      @click="onRowClick(t.id)"
      @keydown.enter.prevent="onRowClick(t.id)"
      @keydown.space.prevent="onRowClick(t.id)"
    >
      <div class="row1">
        <input
          v-if="batchMode"
          class="thread-check"
          type="checkbox"
          :checked="selected.has(t.id)"
          @click.stop="toggle(t.id)"
        />
        <span class="title">{{ t.title || '（无标题）' }}</span>
        <span v-if="t.blacklisted" class="tag blacklisted-tag" title="已拉黑：该会话的新请求会被直接拒绝">已拉黑</span>
        <span v-if="t.pendingCount > 0 && t.pendingDeadlineAt !== null" class="countdown">
          ⏳ {{ remainingSeconds(t.pendingDeadlineAt, now) }}s
        </span>
        <template v-if="!batchMode">
          <button
            class="thread-del thread-ban"
            :title="t.blacklisted ? '解除拉黑' : '拉黑这条会话'"
            @click.stop="onBlacklist(t)"
          >{{ t.blacklisted ? '✓' : '🚫' }}</button>
          <button class="thread-del" title="删除这条会话" @click.stop="onDelete(t)">✕</button>
        </template>
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
