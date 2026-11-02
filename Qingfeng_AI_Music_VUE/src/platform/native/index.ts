import type { PlatformKind, PlatformServices } from '../contracts'

/** 原生SDK和插件未就绪时明确不可用，后续在此接入原生分享与后台音频。 */
export function createNativePlatform(kind: Exclude<PlatformKind, 'web'>): PlatformServices {
  return {
    kind, capabilities: { nativeShare: false, backgroundAudio: false },
    async shareLink() { throw new Error('原生分享插件尚未接入') },
  }
}
