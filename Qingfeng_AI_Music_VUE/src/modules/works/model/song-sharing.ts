export interface SharedSong { songId: string; title: string; creator?: string; lyrics: string; hasCover: boolean; expiresAt: string; timelineTitle?: string }

export function songTimelineTitle(song: SharedSong): string {
  return song.timelineTitle || `青风音乐 Cheerful Music｜${song.title}`
}
export interface SongShareStatus {
  status: 'PRIVATE' | 'SHAREABLE' | 'REVOKED' | 'EXPIRED'
  card: SharedSong | null
  views: number
  plays: number
}
export function songShareUrl(token: string, origin: string): string {
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('分享链接暂时无法创建，请重试。')
  const base = new URL(origin)
  if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password) throw new Error('分享地址无效')
  return new URL(`/song/${token}`, base.origin).href
}
export type WechatDestination = 'friend' | 'timeline'
export type WechatSongData = ShareData & { url: string }
export interface WechatCard {
  link: string
  imgUrl: string
  friendTitle: string
  timelineTitle: string
  description: string
}
export type WechatCardState = 'loading' | 'ready' | 'failed'
export type WechatShareFeedback = { message: string; clipboard?: 'copied' | 'manual' }
declare global {
  interface Window {
    cheerfulWechatCard?: {
      entryUrl: string
      prepare(card: WechatCard, update: (state: WechatCardState) => void): () => void
    }
    cheerfulWechat?: {
      mode(destination: WechatDestination): '' | 'client'
      draft(data: WechatSongData, destination: WechatDestination): string
      copyDraft(data: WechatSongData, destination: WechatDestination): Promise<WechatShareFeedback>
      open(destination: WechatDestination, data: WechatSongData): Promise<WechatShareFeedback | undefined>
      clientUrl: string
      isWindows: boolean
      windowsInstruction: string
    }
  }
}
export function wechatShareEntry(link: string, destination: WechatDestination): string {
  const entry = new URL(link)
  entry.hash = 'share-' + destination
  return entry.href
}
export function lyricQuotes(lyrics: string): string[] {
  return [...new Set(lyrics.split(/\r?\n/).map(line => line.trim().replace(/^(\*\*|__)(.+)\1$/, '$2')).filter(line =>
    !!line && !/^#{1,6}\s/.test(line) && !/^\[.*\]$/.test(line) && !/^【.*】$/.test(line)))].slice(0, 100)
}
export function localShareAddress(link: string): boolean {
  const host = new URL(link).hostname
  return /^(localhost$|127\.|10\.|192\.168\.|\[?::1\]?$)/.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host)
}
export function posterFilename(title: string): string {
  return (title.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '').trim().slice(0, 70) || 'Cheerful-Music') + '-poster.png'
}
export function shareCancelled(error: unknown): boolean {
  return !!error && typeof error === 'object' && 'name' in error && error.name === 'AbortError'
}
export async function shareSongCard(payload: ShareData): Promise<'shared' | 'copied' | 'cancelled'> {
  if (navigator.share) {
    try { await navigator.share(payload); return 'shared' }
    catch (error) { if (shareCancelled(error)) return 'cancelled' }
  }
  if (!payload.url) throw new Error('分享地址无效')
  await navigator.clipboard.writeText(payload.url)
  return 'copied'
}
