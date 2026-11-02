import { assertShareUrl, type PlatformServices } from '../contracts'

export const webPlatform: PlatformServices = {
  kind: 'web', capabilities: { nativeShare: false, backgroundAudio: false },
  async shareLink(payload) {
    assertShareUrl(payload.url)
    if (navigator.share) await navigator.share(payload)
    else if (navigator.clipboard) await navigator.clipboard.writeText(payload.url)
    else throw new Error('当前浏览器暂不支持分享，请手动复制地址')
  },
}
