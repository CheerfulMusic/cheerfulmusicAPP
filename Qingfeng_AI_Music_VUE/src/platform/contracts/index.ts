export type PlatformKind = 'web' | 'android' | 'ios'
export interface SharePayload { title: string; text?: string; url: string }
export interface PlatformServices {
  readonly kind: PlatformKind
  readonly capabilities: { nativeShare: boolean; backgroundAudio: boolean }
  shareLink(payload: SharePayload): Promise<void>
}

// 原生桥与浏览器共享此边界，不能将 javascript: / data: 地址交给系统分享或导航。
export function assertShareUrl(value: string): URL {
  const url = new URL(value)
  if (!['https:', 'http:'].includes(url.protocol)) throw new Error('只支持 HTTP(S) 分享链接')
  return url
}
