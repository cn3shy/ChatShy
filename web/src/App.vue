<script setup lang="ts">
import ChatView from './components/ChatView.vue';
import Login from './components/Login.vue';
import SettingsPanel from './components/SettingsPanel.vue';
import ThreadList from './components/ThreadList.vue';
import { logout, pendingTotal, selectThread, state } from './store';
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
