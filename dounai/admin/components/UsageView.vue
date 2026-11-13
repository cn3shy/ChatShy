<script setup lang="ts">
import { BarElement, CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, Tooltip } from 'chart.js';
import { computed, onMounted, ref } from 'vue';
import { Bar, Line } from 'vue-chartjs';
import { state } from '../store';
import { api } from '../api';
import type { UsageResponse } from '../types';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, Tooltip, Legend);

type RangeKey = '7d' | '30d' | 'custom';

const range = ref<RangeKey>('7d');
const customFrom = ref('');
const customTo = ref('');
const loading = ref(false);
const error = ref('');
const data = ref<UsageResponse | null>(null);

const ranges: Array<{ key: RangeKey; label: string }> = [
  { key: '7d', label: '近 7 天' },
  { key: '30d', label: '近 30 天' },
  { key: 'custom', label: '自定义' },
];

/** datetime-local 字符串 → epoch 毫秒 */
function localToMs(value: string): number | undefined {
  if (!value) return undefined;
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) ? ms : undefined;
}

const fromMs = computed<number | undefined>(() => {
  if (range.value === '7d') return Date.now() - 7 * 86_400_000;
  if (range.value === '30d') return Date.now() - 30 * 86_400_000;
  return localToMs(customFrom.value);
});

const toMs = computed<number | undefined>(() => {
  if (range.value === 'custom') return localToMs(customTo.value);
  return undefined;
});

const canQuery = computed(() => range.value !== 'custom' || (customFrom.value !== '' && customTo.value !== ''));

async function load(): Promise<void> {
  if (!canQuery.value) return;
  loading.value = true;
  error.value = '';
  try {
    data.value = await api.usage({ from: fromMs.value, to: toMs.value });
  } catch {
    error.value = '加载失败，请稍后重试';
  } finally {
    loading.value = false;
  }
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M';
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
  return String(n);
}

function formatCost(value: number | string | undefined, currency: string): string {
  if (value === undefined) return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—';
  const symbol = currency === 'USD' ? '$' : '¥';
  return symbol + (n >= 0.01 ? n.toFixed(4) : n.toPrecision(3));
}

const costCurrencies = computed(() => {
  const costs = data.value?.totals.costs;
  if (!costs) return [];
  return Object.keys(costs).filter((k) => k !== 'unpricedRequests');
});

const chartBase = {
  responsive: true,
  maintainAspectRatio: false,
  animation: { duration: 200 },
  plugins: { legend: { labels: { boxWidth: 12, font: { size: 11 } } } },
} as const;

const dailyChart = computed(() => {
  const daily = data.value?.daily ?? [];
  return {
    type: 'bar' as const,
    data: {
      labels: daily.map((d) => d.day.slice(5)),
      datasets: [
        {
          label: '输入 token',
          data: daily.map((d) => d.input_tokens),
          backgroundColor: 'rgba(91, 141, 239, 0.75)',
          stack: 'tokens',
        },
        {
          label: '输出 token',
          data: daily.map((d) => d.output_tokens),
          backgroundColor: 'rgba(26, 127, 55, 0.75)',
          stack: 'tokens',
        },
        {
          label: '缓存命中',
          data: daily.map((d) => d.cache_read_tokens),
          backgroundColor: 'rgba(180, 83, 9, 0.7)',
          stack: 'tokens',
        },
      ],
    },
    options: {
      ...chartBase,
      scales: { x: { stacked: true }, y: { stacked: true } },
    },
  };
});

const requestChart = computed(() => {
  const daily = data.value?.daily ?? [];
  return {
    type: 'line' as const,
    data: {
      labels: daily.map((d) => d.day.slice(5)),
      datasets: [
        {
          label: '请求数',
          data: daily.map((d) => d.requests),
          borderColor: '#5b8def',
          backgroundColor: 'rgba(91, 141, 239, 0.15)',
          fill: true,
          tension: 0.3,
          pointRadius: 3,
        },
      ],
    },
    options: chartBase,
  };
});

const byModel = computed(() => data.value?.byModel ?? []);
const byProvider = computed(() => data.value?.byProvider ?? []);
const bySource = computed(() => data.value?.bySource ?? []);

onMounted(() => void load());

function switchRange(key: RangeKey): void {
  range.value = key;
  if (key !== 'custom') void load();
}
</script>

<template>
  <div class="usage-page">
    <header class="page-head">
      <div class="page-title">
        <h2>用量统计</h2>
        <span class="muted">Token 消耗 · 请求数 · 缓存命中 · 资金消耗</span>
      </div>
      <div class="range-picker">
        <button
          v-for="r in ranges"
          :key="r.key"
          class="ghost-btn small"
          :class="{ active: range === r.key }"
          @click="switchRange(r.key)"
        >
          {{ r.label }}
        </button>
        <template v-if="range === 'custom'">
          <input v-model="customFrom" class="range-input" type="datetime-local" />
          <span class="muted">至</span>
          <input v-model="customTo" class="range-input" type="datetime-local" />
          <button class="primary-btn small" :disabled="!canQuery || loading" @click="load">
            {{ loading ? '查询中…' : '查询' }}
          </button>
        </template>
      </div>
    </header>

    <p v-if="error" class="note err">{{ error }}</p>

    <div v-if="data" class="usage-body">
      <div class="stat-row">
        <div class="stat-card">
          <div class="stat-value">{{ data.totals.requests }}</div>
          <div class="stat-label">请求数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ formatTokens(data.totals.inputTokens) }}</div>
          <div class="stat-label">输入 token</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ formatTokens(data.totals.outputTokens) }}</div>
          <div class="stat-label">输出 token</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ (data.totals.cacheHitRate * 100).toFixed(1) }}%</div>
          <div class="stat-label">缓存命中率</div>
        </div>
        <div v-for="cur in costCurrencies" :key="cur" class="stat-card">
          <div class="stat-value">{{ formatCost(data.totals.costs[cur], cur) }}</div>
          <div class="stat-label">估算花费（{{ cur }}）</div>
        </div>
      </div>

      <div class="chart-grid">
        <div class="chart-card">
          <h3>每日 Token 消耗</h3>
          <div class="chart-box"><Bar :data="dailyChart.data" :options="dailyChart.options" /></div>
        </div>
        <div class="chart-card">
          <h3>每日请求趋势</h3>
          <div class="chart-box"><Line :data="requestChart.data" :options="requestChart.options" /></div>
        </div>
      </div>

      <div class="table-grid">
        <div class="chart-card">
          <h3>按模型</h3>
          <table class="usage-table">
            <thead>
              <tr><th>模型</th><th>请求</th><th>输入</th><th>输出</th><th>花费</th></tr>
            </thead>
            <tbody>
              <tr v-for="g in byModel" :key="g.name">
                <td class="t-name" :title="g.name">{{ g.name }}</td>
                <td>{{ g.requests }}</td>
                <td>{{ formatTokens(g.inputTokens) }}</td>
                <td>{{ formatTokens(g.outputTokens) }}</td>
                <td>{{ g.inputCost !== undefined ? formatCost(
                  (Number(g.inputCost) || 0) + (Number(g.cacheReadCost) || 0) + (Number(g.outputCost) || 0),
                  g.currency ?? 'CNY') : '未配价' }}</td>
              </tr>
              <tr v-if="byModel.length === 0"><td colspan="5" class="muted">暂无数据</td></tr>
            </tbody>
          </table>
        </div>
        <div class="chart-card">
          <h3>按厂商</h3>
          <table class="usage-table">
            <thead>
              <tr><th>厂商</th><th>请求</th><th>输入</th><th>输出</th></tr>
            </thead>
            <tbody>
              <tr v-for="g in byProvider" :key="g.name">
                <td class="t-name" :title="g.name">{{ g.name }}</td>
                <td>{{ g.requests }}</td>
                <td>{{ formatTokens(g.inputTokens) }}</td>
                <td>{{ formatTokens(g.outputTokens) }}</td>
              </tr>
              <tr v-if="byProvider.length === 0"><td colspan="4" class="muted">暂无数据</td></tr>
            </tbody>
          </table>
        </div>
        <div class="chart-card">
          <h3>按来源</h3>
          <table class="usage-table">
            <thead>
              <tr><th>来源</th><th>请求</th><th>输入</th><th>输出</th></tr>
            </thead>
            <tbody>
              <tr v-for="g in bySource" :key="g.name">
                <td class="t-name" :title="g.name">{{ g.name }}</td>
                <td>{{ g.requests }}</td>
                <td>{{ formatTokens(g.inputTokens) }}</td>
                <td>{{ formatTokens(g.outputTokens) }}</td>
              </tr>
              <tr v-if="bySource.length === 0"><td colspan="4" class="muted">暂无数据</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <p class="note muted usage-footnote">
        统计口径：只含版本升级后有 token 数据的请求；人工回复的 token 为估算值（不计花费），
        AI 转发为上游回报的真实值。花费按「设置 → 模型单价」估算，
        <template v-if="data.totals.costs.unpricedRequests > 0">
          另有 {{ data.totals.costs.unpricedRequests }} 条请求的模型未配价、未计入花费。
        </template>
      </p>
    </div>
    <div v-else-if="loading" class="empty-hint">加载中…</div>
  </div>
</template>
