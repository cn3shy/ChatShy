import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发时把管理端接口代理到本地服务
      '/admin/api': 'http://localhost:8088',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // /admin 页在豆奶站点（dounai.chat/admin，CSP 为 script-src 'self'）也能打开，
    // 不能有 Vite 注入的内联 modulepreload polyfill，与 dounai 保持一致
    modulePreload: { polyfill: false },
  },
});
