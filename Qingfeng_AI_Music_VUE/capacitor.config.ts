import type { CapacitorConfig } from '@capacitor/cli'

// 原生工程在 Android/iOS SDK 就绪后使用 cap add 生成，前端资源随安装包分发。
const config: CapacitorConfig = {
  appId: 'com.qingfeng.music',
  appName: '青风音乐',
  webDir: 'dist',
  plugins: { CapacitorHttp: { enabled: true } },
}
export default config
