import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// WebUI 构建配置：
// - base './' 相对路径：产物可挂载在任意子路径下，配合 Ktor 的 assets 托管
// - 生产环境使用相对路径 /api/...；开发环境通过 proxy 转发到电视上的 Ktor 服务
export default defineConfig({
  plugins: [vue()],
  base: './',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发时指向电视上的 HyperTV 服务，按需修改为电视 IP
      '/api': {
        target: process.env.HYPERTV_API ?? 'http://192.168.1.100:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // dist 由 Gradle 的 cleanWebuiDist 在构建链中清理（部分 Windows 环境下 vite 的 emptyDir
    // 会被文件系统安全删除拦截），这里不再自行清空
    emptyOutDir: false,
    chunkSizeWarningLimit: 1500,
  },
})
