<script setup lang="ts">
import { computed, ref } from 'vue';
import AutoForwardBar from './components/AutoForwardBar.vue';
import ChatView from './components/ChatView.vue';
import Launchpad from './components/Launchpad.vue';
import Login from './components/Login.vue';
import RequestMonitor from './components/RequestMonitor.vue';
import SettingsPanel from './components/SettingsPanel.vue';
import ThreadList from './components/ThreadList.vue';
import UsageView from './components/UsageView.vue';
import { logout, pendingTotal, selectThread, state } from './store';

export type View = 'launchpad' | 'chat' | 'requests' | 'usage' | 'settings';

const view = ref<View>('launchpad');

const viewTitles: Record<View, string> = {
  launchpad: '工作台',
  chat: '会话管理',
  requests: '请求监控',
  usage: '用量统计',
  settings: '设置',
};

const showTopbar = computed(() => view.value !== 'launchpad');
const title = computed(() => viewTitles[view.value]);
const hasProviders = computed(() => state.providers.length > 0);

/** 从会话列表或请求监控打开某个会话，并切到会话管理视图。 */
function openThread(id: string): void {
  view.value = 'chat';
  void selectThread(id);
}

async function exitApp(): Promise<void> {
  await logout();
  view.value = 'launchpad';
}
</script>

<template>
  <div v-if="state.booting" class="boot">加载中…</div>
  <Login v-else-if="!state.authed" />
  <div v-else class="workspace">
    <header v-if="showTopbar" class="topbar">
      <button class="topbar-home" title="返回工作台" @click="view = 'launchpad'">
        <span class="logo-dot"></span>ChatShy
      </button>
      <div class="topbar-title">{{ title }}</div>
      <div class="topbar-actions">
        <button
          v-if="view !== 'chat'"
          class="topbar-pill"
          :class="{ hot: pendingTotal > 0 }"
          title="待回复请求"
          @click="view = 'chat'"
        >
          🔔 {{ pendingTotal }}
        </button>
        <button class="topbar-pill" title="工作台" @click="view = 'launchpad'">⊞ 工作台</button>
        <button class="topbar-pill" title="退出登录" @click="exitApp">⏻</button>
      </div>
    </header>

    <Launchpad v-if="view === 'launchpad'" :pending-total="pendingTotal" @open="view = $event" />

    <div v-else-if="view === 'chat'" class="chat-layout">
      <aside class="sidebar">
        <div class="sidebar-head">
          <div class="logo">
            <span class="logo-dot"></span>会话
            <span v-if="pendingTotal > 0" class="logo-badge">{{ pendingTotal }}</span>
          </div>
        </div>
        <AutoForwardBar />
        <p v-if="!hasProviders" class="auto-forward-msg muted">先在 ⊞ 工作台 → 设置里添加 AI 厂商</p>
        <p v-if="state.forwardError" class="auto-forward-msg err">{{ state.forwardError }}</p>
        <ThreadList
          :threads="state.threads"
          :selected-id="state.selectedId"
          :now="state.now"
          @select="openThread"
        />
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
    </div>

    <main v-else-if="view === 'requests'" class="page-main">
      <RequestMonitor @open-thread="openThread" />
    </main>

    <main v-else-if="view === 'usage'" class="page-main">
      <UsageView />
    </main>

    <main v-else-if="view === 'settings'" class="page-main">
      <SettingsPanel />
    </main>
  </div>
</template>
