import { createApp } from 'vue';
import 'highlight.js/styles/github-dark.css';
import App from './App.vue';
import { bootstrap } from './store';
import './style.css';

createApp(App).mount('#app');
void bootstrap();
