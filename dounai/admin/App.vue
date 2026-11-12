<script setup lang="ts">
import { computed, ref } from 'vue';
import ChatView from './components/ChatView.vue';
import Login from './components/Login.vue';
import SettingsPanel from './components/SettingsPanel.vue';
import ThreadList from './components/ThreadList.vue';
import { logout, pendingTotal, selectThread, setAutoForward, state } from './store';

const autoBusy = ref(false);
const autoMsg = ref('');
const hasProviders = computed(() => state.providers.length > 0);
const autoEnabled = computed(() => state.settings?.autoForwardEnabled ?? false);
const selectedProviderId = computed(() => state.settings?.autoForwardProviderId ?? '');

async function toggleAuto(enabled: boolean): Promise<void> {
  if (enabled && !hasProviders.value) {
    autoMsg.value = '请先在设置里添加 AI 厂商';
    return;
  }
  let providerId = selectedProviderId.value;
  if (enabled && (providerId === '' || !state.providers.some((p) => p.id === providerId))) {
    providerId = state.providers[0]?.id ?? '';
  }
  autoBusy.value = true;
  autoMsg.value = '';
  const res = await setAutoForward(enabled, providerId);
  autoBusy.value = false;
  if (!res.ok) autoMsg.value = res.error ?? '保存失败';
}

function onToggle(e: Event): void {
  void toggleAuto((e.target as HTMLInputElement).checked);
}

async function changeProvider(providerId: string): Promise<void> {
  if (providerId === '') return;
  autoBusy.value = true;
  autoMsg.value = '';
  // 即使开关关着也记住选择，下次开启直接生效
  const res = await setAutoForward(autoEnabled.value, providerId);
  autoBusy.value = false;
  if (!res.ok) autoMsg.value = res.error ?? '保存失败';
}

function onChangeProvider(e: Event): void {
  void changeProvider((e.target as HTMLSelectElement).value);
}
</script>

<template>
  <div v-if="state.booting" class="boot">加载中…</div>
  <Login v-else-if="!state.authed" />
  <div v-else class="app">
    <aside class="sidebar">
      <div class="sidebar-head">
        <div class="logo">
          <span class="logo-dot"></span>ChatShy
          <span v-if="pendingTotal > 0" class="logo-badge">{{ pendingTotal }}</span>
        </div>
        <div class="head-actions">
          <button class="icon-btn" title="设置" @click="state.showSettings = true">⚙</button>
          <button class="icon-btn" title="退出登录" @click="logout">⏻</button>
        </div>
      </div>
      <div class="auto-forward-bar">
        <label class="auto-forward-toggle" :class="{ on: autoEnabled }" :title="hasProviders ? '开启后服务端会自动用所选 AI 回复新请求，操作台关掉也生效' : '请先在设置里添加 AI 厂商'">
          <input
            type="checkbox"
            :checked="autoEnabled"
            :disabled="autoBusy || !hasProviders"
            @change="onToggle"
          />
          <span>AI 自动转发</span>
        </label>
        <select
          class="auto-forward-provider"
          :value="selectedProviderId"
          :disabled="autoBusy || !hasProviders"
          @change="onChangeProvider"
        >
          <option v-if="selectedProviderId === ''" value="">选择厂商</option>
          <option v-for="p in state.providers" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </div>
      <p v-if="autoMsg" class="auto-forward-msg">{{ autoMsg }}</p>
      <p v-if="state.forwardError" class="auto-forward-msg err">{{ state.forwardError }}</p>
      <p v-if="!hasProviders" class="auto-forward-msg muted">先在 ⚙ 设置里添加 AI 厂商</p>

      <ThreadList :threads="state.threads" :selected-id="state.selectedId" :now="state.now" @select="selectThread" />
    </aside>

    <main class="main">
      <ChatView v-if="state.detail" :detail="state.detail" :now="state.now" />
      <div v-else class="empty">
        <h2>等待调用方来信 📮</h2>
        <p>
          把任意 OpenAI / Anthropic 客户端指向本服务的 <code>/v1</code>，Key 填设置页里那个全局 Key：<br />
          <code>OPENAI_BASE_URL=https://你的域名/v1</code> 或
          <code>ANTHROPIC_BASE_URL=https://你的域名</code>
        </p>
        <p class="hint">
          每条会话会标注请求来源（IP + 客户端），用来区分不同的调用方。
        </p>
      </div>
    </main>

    <SettingsPanel v-if="state.showSettings" @close="state.showSettings = false" />
  </div>
</template>
