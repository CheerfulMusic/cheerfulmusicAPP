import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import legacy from '@vitejs/plugin-legacy'
import {previewMediaPlugin} from './build/preview-media'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [
      vue(),
      previewMediaPlugin(),
      // 微信/老版本内置 WebView 对 <script type="module" crossorigin> 有静默失败坑，
      // 会整页白屏（#app 为空、仅 <title> 显示）。生成一份 nomodule 的 ES5 兜底，
      // 让所有 WebView 都能加载，桌面现代浏览器仍走原 module 构建。
      legacy({
        targets: [
          'defaults',
          'ios >= 12',
          'android >= 6',
          'chrome >= 56',
          'safari >= 10',
          'edge >= 18',
          'firefox >= 60',
          'not dead',
        ],
        renderLegacyChunks: true,
        polyfills: true,
      }),
    ],
    base: '/',
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '^/media/(?:sample-[a-z0-9-]+-v[12](?:-preview-128\\.mp3|-cover\\.jpg)|explore-(?:(?:love|family|travel)-v1\\.webp|(?:disco|rock|electronic|instrumental)-v1\\.jpg|guofeng-v1\\.svg)|recommendations/[a-z0-9-]+-[a-f0-9]{12}-(?:preview-128\\.mp3|cover\\.jpg))(?:[?]|$)': {
          target: env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080',
          changeOrigin: true,
        },
        '/api': { target: env.VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: true },
      },
    },
  }
})
