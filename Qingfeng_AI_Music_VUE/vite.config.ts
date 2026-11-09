import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import {previewMediaPlugin} from './build/preview-media'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue(),previewMediaPlugin()],
    base: '/',
    server: {
      port: 5173,
      strictPort: true,
      proxy: { '/api': { target: env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: true } },
    },
  }
})
