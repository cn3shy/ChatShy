<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { fetchRequestDetail, fetchRequests } from '../store';
import type { RequestDetail, RequestRecord } from '../types';

defineEmits<{ openThread: [id: string] }>();

const LIMIT = 50;
const items = ref<RequestRecord[]>([]);
const total = ref(0);
const offset = ref(0);
const status = ref('');
const protocol = ref('');
const q = ref('');
const fromDate = ref('');
const toDate = ref('');
const loading = ref(false);
const error = ref('');
const detailError = ref('');
const expandedId = ref<string | null>(null);
const detail = ref<RequestDetail | null>(null);
const detailLoading = ref(false);

const page = computed(() => Math.floor(offset.value / LIMIT) + 1);
const pages = computed(() => Math.max(1, Math.ceil(total.value / LIMIT)));

/** datetime-local 字符串 → epoch 毫秒（结束时间取当天 23:59:59.999） */
function fromLocal(value: string): number | undefined {
  if (!value) return undefined;
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : undefined;
}

function toLocalEnd(value: string): number | undefined {
  if (!value) return undefined;
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms + 59_999 : undefined;
}

const STATUS_LABELS: Record<string, string> = {
  pending: '待回复',
  replied: '已回复',
  timeout: '超时',
  cancelled: '已取消',
  superseded: '被替代',
  dropped: '已丢弃',
};

let loadSeq = 0;
let detailSeq = 0;
let timer: number | null = null;

async function load(): Promise<void> {
  const seq = ++loadSeq;
  loading.value = true;
  error.value = '';
  try {
    const res = await fetchRequests({
      limit: LIMIT,
      offset: offset.value,
      status: status.value,
      protocol: protocol.value,
      q: q.value.trim(),
      from: fromLocal(fromDate.value),
      to: toLocalEnd(toDate.value),
    });
    if (seq !== loadSeq) return;
    if (res.requests.length === 0 && offset.value > 0 && res.total > 0) {
      offset.value = Math.max(0, Math.floor((res.total - 1) / LIMIT) * LIMIT);
      await load();
      return;
    }
    items.value = res.requests;
    total.value = res.total;
  } catch {
    if (seq === loadSeq) error.value = '加载失败，请稍后重试';
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

function applyFilters(): void {
  offset.value = 0;
  void load();
}

function resetFilters(): void {
  status.value = '';
  protocol.value = '';
  q.value = '';
  fromDate.value = '';
  toDate.value = '';
  applyFilters();
}

function prevPage(): void {
  if (offset.value === 0) return;
  offset.value = Math.max(0, offset.value - LIMIT);
  void load();
}

function nextPage(): void {
  if (offset.value + LIMIT >= total.value) return;
  offset.value += LIMIT;
  void load();
}

async function toggleDetail(row: RequestRecord): Promise<void> {
  if (expandedId.value === row.id) {
    detailSeq++;
    expandedId.value = null;
    detail.value = null;
    detailError.value = '';
    detailLoading.value = false;
    return;
  }
  const seq = ++detailSeq;
  expandedId.value = row.id;
  detail.value = null;
  detailError.value = '';
  detailLoading.value = true;
  try {
    const res = await fetchRequestDetail(row.id);
    if (seq === detailSeq && expandedId.value === row.id) detail.value = res;
  } catch {
    if (seq === detailSeq && expandedId.value === row.id) detailError.value = '详情加载失败';
  } finally {
    if (seq === detailSeq) detailLoading.value = false;
  }
}

function statusLabel(value: string): string {
  return STATUS_LABELS[value] ?? value;
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString();
}

function formatJson(raw: string): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

onMounted(() => {
  void load();
  // 5 秒轮询；打开详情时暂停，避免列表跳动
  timer = window.setInterval(() => {
    if (!loading.value && expandedId.value === null) void load();
  }, 5000);
});

onUnmounted(() => {
  if (timer !== null) window.clearInterval(timer);
});
</script>

<template>
  <div class="monitor">
    <header class="monitor-head">
      <div class="monitor-title">
        <h2>请求监控</h2>
        <span class="muted">共 {{ total }} 条请求参数记录</span>
      </div>
      <div class="monitor-actions">
        <button class="ghost-btn small" :disabled="loading" @click="load">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <div class="monitor-filters">
      <select v-model="status" @change="applyFilters">
        <option value="">全部状态</option>
        <option value="pending">待回复</option>
        <option value="replied">已回复</option>
        <option value="timeout">超时</option>
        <option value="cancelled">已取消</option>
        <option value="superseded">被新请求替代</option>
        <option value="dropped">重启丢弃</option>
      </select>
      <select v-model="protocol" @change="applyFilters">
        <option value="">全部协议</option>
        <option value="openai">OpenAI</option>
        <option value="anthropic">Anthropic</option>
      </select>
      <input
        v-model="fromDate"
        class="range-input"
        type="datetime-local"
        title="开始时间"
        @change="applyFilters"
      />
      <span class="muted range-sep">至</span>
      <input
        v-model="toDate"
        class="range-input"
        type="datetime-local"
        title="结束时间"
        @change="applyFilters"
      />
      <input
        v-model="q"
        class="monitor-search"
        placeholder="搜索请求内容 / 会话标题"
        @keydown.enter="applyFilters"
      />
      <button class="primary-btn small" @click="applyFilters">查询</button>
      <button class="ghost-btn small" @click="resetFilters">重置</button>
    </div>

    <p v-if="error" class="note err">{{ error }}</p>
    <div v-if="items.length === 0 && !loading" class="empty-hint">暂无请求记录</div>

    <div class="req-list">
      <div v-for="r in items" :key="r.id" class="req-item" :class="{ open: expandedId === r.id }">
        <div
          class="req-row"
          role="button"
          tabindex="0"
          @click="toggleDetail(r)"
          @keydown.enter.prevent="toggleDetail(r)"
        >
          <span class="req-status" :class="'st-' + r.status">{{ statusLabel(r.status) }}</span>
          <span class="req-protocol">{{ r.protocol }} · {{ r.mode }}</span>
          <span class="req-source" :title="r.sourceUa">
            {{ r.sourceClient || '未知来源' }} / {{ r.sourceIp }}
          </span>
          <span class="req-time">{{ formatTime(r.createdAt) }}</span>
          <span class="req-toggle">{{ expandedId === r.id ? '收起' : '详情' }}</span>
        </div>
        <div class="req-meta">
          <span class="tag">{{ r.model || '未知模型' }}</span>
          <span v-if="r.providerName">{{ r.via }} · {{ r.providerName }}</span>
          <span v-else-if="r.via">{{ r.via }}</span>
          <span class="req-preview" :title="r.requestPreview">{{ r.requestPreview }}</span>
        </div>

        <div v-if="expandedId === r.id" class="req-detail">
          <div v-if="detailLoading" class="muted">加载中…</div>
          <p v-else-if="detailError" class="note err">{{ detailError }}</p>
          <template v-else-if="detail">
            <div class="req-detail-actions">
              <button class="ghost-btn small" @click.stop="$emit('openThread', detail.threadId)">
                查看会话
              </button>
              <span class="muted">请求 ID：{{ detail.id }}</span>
              <span v-if="detail.threadTitle" class="muted">会话：{{ detail.threadTitle }}</span>
              <span v-if="detail.tokenSource" class="muted">
                Token：输入 {{ detail.inputTokens ?? '—' }} / 输出 {{ detail.outputTokens ?? '—' }}
                <template v-if="detail.cacheReadTokens">（缓存命中 {{ detail.cacheReadTokens }}）</template>
                · {{ detail.tokenSource === 'upstream' ? '上游回报' : '估算' }}
              </span>
            </div>
            <h4>请求参数</h4>
            <pre class="req-json">{{ formatJson(detail.requestJson) }}</pre>
            <template v-if="detail.replyContent">
              <h4>回复内容</h4>
              <pre class="req-json">{{ detail.replyContent }}</pre>
            </template>
            <p v-if="detail.forwardError" class="note err">转发失败：{{ detail.forwardError }}</p>
          </template>
        </div>
      </div>
    </div>

    <footer class="monitor-pager">
      <button class="ghost-btn small" :disabled="offset === 0" @click="prevPage">上一页</button>
      <span class="muted">第 {{ page }} / {{ pages }} 页</span>
      <button class="ghost-btn small" :disabled="offset + LIMIT >= total" @click="nextPage">下一页</button>
    </footer>
  </div>
</template>
