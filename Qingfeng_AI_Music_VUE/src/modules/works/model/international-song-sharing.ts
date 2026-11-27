import { posterFilename, shareCancelled } from './song-sharing'

const iconPath = '/brand/share-channels/'
export const internationalShareChannels = [
  { id: 'wechat', label: 'WeChat', chineseLabel: '微信', icon: `${iconPath}wechat.png` },
  { id: 'youtube', label: 'YouTube', chineseLabel: '油管', icon: `${iconPath}youtube.png` },
  { id: 'whatsapp', label: 'WhatsApp', chineseLabel: 'WhatsApp', icon: `${iconPath}whatsapp.svg` },
  { id: 'instagram', label: 'Instagram', chineseLabel: '照片墙', icon: `${iconPath}instagram.png` },
  { id: 'facebook', label: 'Facebook', chineseLabel: '脸书', icon: `${iconPath}facebook.png` },
  { id: 'telegram', label: 'Telegram', chineseLabel: '电报', icon: `${iconPath}telegram.svg` },
  { id: 'x', label: 'X', chineseLabel: 'X', icon: `${iconPath}x.svg` },
  { id: 'email', label: 'Email', chineseLabel: '邮件', icon: `${iconPath}email.svg` },
] as const

type Channel = typeof internationalShareChannels[number]['id']
interface SongSharePayload { title: string; text: string; url: string }
type ShareResult = 'shared' | 'cancelled' | 'unavailable'

function encodeShareValue(value: string): string {
  // JavaScript's encoder rejects lone surrogates. Keep valid astral characters intact.
  return encodeURIComponent(value.replace(/[\uD800-\uDFFF]/gu, '\ufffd'))
}

function xWeight(text: string): number {
  let weight = 0
  // Count code points conservatively under X's twitter-text v3 rules.
  for (const character of text) {
    const point = character.codePointAt(0)!
    const single = point <= 0x10ff || (point >= 0x2000 && point <= 0x200d)
      || (point >= 0x2010 && point <= 0x201f) || (point >= 0x2032 && point <= 0x2037)
    weight += single ? 1 : 2
  }
  return weight
}

function fitXText(text: string, limit: number): string {
  if (limit <= 0) return ''
  const normalized = text.normalize('NFC')
  if (xWeight(normalized) <= limit) return normalized
  const ellipsis = '…'
  let weight = xWeight(ellipsis)
  if (weight > limit) return ''
  const segments = typeof Intl.Segmenter === 'function'
    ? Array.from(new Intl.Segmenter().segment(normalized), entry => entry.segment) : Array.from(normalized)
  let result = ''
  for (const segment of segments) {
    weight += xWeight(segment)
    if (weight > limit) break
    result += segment
  }
  return result.trimEnd() + ellipsis
}

export function internationalShareHref(channel: Channel, { title, text, url }: SongSharePayload): string | undefined {
  if (!url) return undefined
  const invitation = [title, text].filter(Boolean).join('\n')
  const encodedUrl = encodeShareValue(url)
  switch (channel) {
    case 'whatsapp': return `https://wa.me/?text=${encodeShareValue(`${invitation}\n${url}`)}`
    // Facebook reads the link preview fields from the public song HTML.
    case 'facebook': return `https://www.facebook.com/sharer/sharer.php?u=${encodedUrl}`
    case 'telegram': return `https://t.me/share/url?url=${encodedUrl}&text=${encodeShareValue(invitation)}`
    case 'x': {
      let playerCardUrl: URL
      try { playerCardUrl = new URL(url) } catch { return undefined }
      if (!['http:', 'https:'].includes(playerCardUrl.protocol)) return undefined
      playerCardUrl.searchParams.set('card', 'x')
      playerCardUrl.hash = ''
      const captionLimit = 280 - 23 - 1 // X's shortened song URL and separator.
      const caption = fitXText(text, captionLimit)
      const shortTitle = fitXText(title, captionLimit - (caption ? xWeight(caption) + 1 : 0))
      return `https://twitter.com/intent/tweet?url=${encodeShareValue(playerCardUrl.href)}&text=${encodeShareValue([shortTitle, caption].filter(Boolean).join('\n'))}`
    }
    case 'email': {
      const subject = title.replace(/[\x00-\x1f\x7f]/g, ' ').trim()
      const description = text.replace(/\r\n|\r|\n/g, '\r\n')
      return `mailto:?subject=${encodeShareValue(subject)}&body=${encodeShareValue(`${description}\r\n\r\n${url}`)}`
    }
  }
}

export async function copyInternationalShareLink(
  url: string, input?: HTMLInputElement, current: () => boolean = () => true,
): Promise<boolean> {
  if (!url || !current()) return false
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(url)
      return current()
    } catch {
      // Embedded browsers can deny clipboard access even when the API is present.
    }
  }
  if (!current() || !input?.isConnected || input.value !== url) return false
  const previous = document.activeElement
  input.focus({ preventScroll: true })
  input.select()
  input.setSelectionRange(0, url.length)
  try {
    if (typeof document.execCommand === 'function' && document.execCommand('copy')) {
      if (previous instanceof HTMLElement) previous.focus({ preventScroll: true })
      return true
    }
  } catch {
    // Leave the link selected so the listener can copy it manually.
  }
  return false
}

async function openSystemShare(data: ShareData): Promise<ShareResult> {
  try {
    if (typeof navigator.share !== 'function') return 'unavailable'
    if (data.files && !navigator.canShare?.({ files: data.files })) return 'unavailable'
    await navigator.share(data)
    return 'shared'
  } catch (cause) {
    return shareCancelled(cause) ? 'cancelled' : 'unavailable'
  }
}

export function shareInternationalSong(payload: SongSharePayload): Promise<ShareResult> {
  return openSystemShare(payload)
}

export function shareInternationalPoster(poster: Blob, title: string): Promise<ShareResult> {
  if (typeof File !== 'function' || !poster.size || poster.type !== 'image/png') return Promise.resolve('unavailable')
  const file = new File([poster], posterFilename(title), { type: 'image/png' })
  return openSystemShare({ files: [file] })
}
