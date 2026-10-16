import { createApp } from 'vue';
import 'highlight.js/styles/github-dark.css';
import App from './App.vue';
import CallerChat from './CallerChat.vue';
import { bootstrap } from './store';
import './style.css';

// /chat = 调用方页面（填 API Key 聊天）；其余路径 = 管理端操作台
const isCallerChat = window.location.pathname.startsWith('/chat');
if (isCallerChat) {
  createApp(CallerChat).mount('#app');
} else {
  createApp(App).mount('#app');
  void bootstrap();
}
