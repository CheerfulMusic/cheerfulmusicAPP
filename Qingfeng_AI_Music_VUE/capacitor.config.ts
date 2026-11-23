import type { CapacitorConfig } from '@capacitor/cli'

// 前端资源随安装包分发，移动应用 AppID 分别由 Gradle 和 Xcode 注入。
const config: CapacitorConfig = {
  appId: 'com.qingfeng.music',
  appName: '青风音乐',
  webDir: 'dist',
  plugins: { CapacitorHttp: { enabled: true } },
}
export default config
