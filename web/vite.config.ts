import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发时把管理端接口代理到本地服务
      '/admin/api': 'http://localhost:8787',
      // /chat 页面直接打 /v1 端点，同样需要代理（SSE 必须关缓冲，见服务端 X-Accel-Buffering 头）
      '/v1': 'http://localhost:8787',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
