import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      // 开发时把豆奶接口代理到 Spring Boot 的 8099 监听
      '/api': 'http://localhost:8099',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 与操作台的 /assets 区分，两个站点可以共用一个 Spring Boot 进程
    assetsDir: 'dounai-assets',
    // 页面 CSP 是 script-src 'self'，不能有 Vite 注入的内联 modulepreload polyfill
    modulePreload: { polyfill: false },
  },
});
