import type { PlatformKind, PlatformServices } from '../contracts'

/** 通用链接分享与后台音频独立于 WechatMusic 的原生音乐消息接口。 */
export function createNativePlatform(kind: Exclude<PlatformKind, 'web'>): PlatformServices {
  return {
    kind, capabilities: { nativeShare: false, backgroundAudio: false },
    async shareLink() { throw new Error('原生分享插件尚未接入') },
  }
}
