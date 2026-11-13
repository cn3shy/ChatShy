<script setup lang="ts">
import type { View } from '../App.vue';

defineProps<{ pendingTotal: number }>();
const emit = defineEmits<{ open: [view: View] }>();

const tiles: Array<{ view: View; icon: string; label: string; desc: string }> = [
  { view: 'chat', icon: '💬', label: '会话管理', desc: '回复调用方来信' },
  { view: 'requests', icon: '📊', label: '请求监控', desc: '全部请求参数记录' },
  { view: 'usage', icon: '📈', label: '用量统计', desc: 'Token 消耗与花费' },
  { view: 'settings', icon: '⚙', label: '设置', desc: 'Key · 厂商 · 单价' },
];
</script>

<template>
  <div class="launchpad">
    <div class="launchpad-inner">
      <h1 class="launchpad-title"><span class="logo-dot"></span>ChatShy 工作台</h1>
      <div class="launchpad-grid">
        <button
          v-for="tile in tiles"
          :key="tile.view"
          class="launch-tile"
          @click="emit('open', tile.view)"
        >
          <span class="launch-icon">
            {{ tile.icon }}
            <span v-if="tile.view === 'chat' && pendingTotal > 0" class="launch-badge">
              {{ pendingTotal }}
            </span>
          </span>
          <span class="launch-label">{{ tile.label }}</span>
          <span class="launch-desc">{{ tile.desc }}</span>
        </button>
      </div>
    </div>
  </div>
</template>
