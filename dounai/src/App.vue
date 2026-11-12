<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import ChatComposer from './components/ChatComposer.vue';
import ChatMessages from './components/ChatMessages.vue';
import Sidebar from './components/Sidebar.vue';
import TopBar from './components/TopBar.vue';
import WelcomeScreen from './components/WelcomeScreen.vue';
import {
  currentConversation,
  deleteConversation,
  loadModel,
  newChat,
  regenerate,
  selectConversation,
  send,
  state,
  stop,
} from './store';

const sidebarOpen = ref(false);
const composer = ref<InstanceType<typeof ChatComposer> | null>(null);
const hasMessages = computed(() => (currentConversation.value?.messages.length ?? 0) > 0);

onMounted(() => {
  void loadModel();
  setTimeout(() => composer.value?.focus(), 100);
});

function onNewChat(): void {
  newChat();
  sidebarOpen.value = false;
}

function onSelect(id: string): void {
  selectConversation(id);
  sidebarOpen.value = false;
}

function onDelete(id: string): void {
  deleteConversation(id);
}

async function onSend(text: string): Promise<void> {
  const ok = await send(text);
  if (!ok) composer.value?.fill(text);
}

function onFill(text: string): void {
  composer.value?.fill(text);
}
</script>

<template>
  <div class="app">
    <div class="backdrop" :class="{ show: sidebarOpen }" @click="sidebarOpen = false"></div>

    <Sidebar
      :conversations="state.conversations"
      :current-id="state.currentId"
      :open="sidebarOpen"
      @new-chat="onNewChat"
      @select="onSelect"
      @delete="onDelete"
    />

    <main class="main">
      <TopBar :model="state.model" @menu="sidebarOpen = true" />

      <WelcomeScreen v-if="!hasMessages" @fill="onFill" />
      <ChatMessages
        v-else
        :messages="currentConversation?.messages ?? []"
        :streaming="state.streaming"
        @regenerate="regenerate"
      />

      <ChatComposer
        ref="composer"
        :streaming="state.streaming"
        @send="onSend"
        @stop="stop"
      />
    </main>

    <Transition name="toast-fade">
      <div v-if="state.notice" class="toast">{{ state.notice }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.toast {
  position: fixed;
  left: 50%;
  bottom: 120px;
  transform: translateX(-50%);
  background: rgba(28, 28, 34, 0.92);
  color: #fff;
  padding: 8px 18px;
  border-radius: 10px;
  font-size: 13px;
  z-index: 80;
  pointer-events: none;
  white-space: nowrap;
}

.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: opacity 0.2s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
}
</style>
