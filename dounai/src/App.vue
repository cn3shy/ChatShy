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

function onSend(text: string): void {
  void send(text);
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
  </div>
</template>
