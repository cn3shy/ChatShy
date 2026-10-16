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
  },
});
