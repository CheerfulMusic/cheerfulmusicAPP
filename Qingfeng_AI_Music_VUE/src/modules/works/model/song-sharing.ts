export interface SharedSong { title: string; lyrics: string; hasCover?: boolean; expiresAt: string }
export function songShareUrl(token: string, origin: string): string {
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('分享链接暂时无法创建，请重试。')
  const base = new URL(origin)
  if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password) throw new Error('分享地址无效')
  return new URL(`/gift/${token}`, base.origin).href
}
export function lyricQuotes(lyrics: string): string[] {
  return [...new Set(lyrics.split(/\r?\n/).map(line => line.trim()).filter(line =>
    !!line && !/^\[.*\]$/.test(line) && !/^【.*】$/.test(line)))].slice(0, 100)
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
