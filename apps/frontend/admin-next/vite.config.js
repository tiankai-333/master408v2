import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  // 生产子路径（9.23 admin-redesign 既定）；dev 根路径
  base: process.env.NODE_ENV === 'production' ? '/admin-next/' : '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    // 9.23 既定 8002；与旧 apps/frontend/admin 同端口，二者不可同时启动（先停旧 admin）
    port: 8002,
    proxy: {
      '/api': {
        target: 'http://localhost:8003',
        changeOrigin: true
      }
    }
  }
})
