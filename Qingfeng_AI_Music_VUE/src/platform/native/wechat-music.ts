import { registerPlugin } from '@capacitor/core'
import type { WechatDestination } from '../../modules/works/model/song-sharing'

export const musicOrigin = 'https://cheerfulmusic.com'
const shareToken = /^[A-Za-z0-9_-]{43}$/
export interface PublicTrack {
  id: string
  title: string
  creator: string
  audio: string
  thumbnail: string
  lyrics: string
  durationSeconds?: number
}
interface MusicMessage {
  songId: string
  title: string
  singer: string
  destination: WechatDestination
  durationMs: number
  thumbnailBase64: string
  lrc?: string
}
interface WechatMusicBridge {
  availability(): Promise<{ configured: boolean; installed: boolean; supported: boolean }>
  share(payload: MusicMessage): Promise<void>
  consumeOpenedSong(): Promise<{ songId?: string }>
}
const bridge = registerPlugin<WechatMusicBridge>('WechatMusic')

export function tokenFromSongUrl(value: string): string | undefined {
  try {
    const url = new URL(value.trim())
    const match = /^\/song\/([\w-]{43})$/.exec(url.pathname)
    if (url.origin === musicOrigin && !url.username && !url.password) return match?.[1]
  } catch { /* 输入框可能仍是未完成的网址。 */ }
}

async function publicFetch<T>(url: string, read: (response: Response) => Promise<T>): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(url, { credentials: 'omit', cache: 'no-store', signal: controller.signal })
    if (response.status === 404 || response.status === 410) throw new Error('这首歌的公开试听已关闭或到期。')
    if (!response.ok) throw new Error('暂时无法读取公开歌曲，请稍后重试。')
    return await read(response)
  } finally { clearTimeout(timer) }
}

export async function loadPublicTrack(id: string): Promise<PublicTrack> {
  if (!shareToken.test(id)) throw new Error('请输入有效的青风音乐公开歌曲链接。')
  const base = musicOrigin + '/api/songs/' + id
  const data = await publicFetch(base + '/public', response => response.json())
  if (data?.url !== musicOrigin + '/song/' + id || data.audio !== base + '/audio' ||
      data.thumbnail !== base + '/thumbnail.png' || typeof data.title !== 'string' ||
      typeof data.creator !== 'string' || !data.title.trim() || !data.creator.trim()) {
    throw new Error('公开歌曲资料不完整，请重新打开这首歌。')
  }
  return { id, title: data.title.trim(), creator: data.creator.trim(), audio: data.audio,
    thumbnail: data.thumbnail, lyrics: typeof data.lyrics === 'string' ? data.lyrics : '',
    durationSeconds: typeof data.durationSeconds === 'number' ? data.durationSeconds : undefined }
}

function readAudioDuration(audioUrl: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const audio = document.createElement('audio')
    function release() {
      clearTimeout(timer)
      audio.onloadedmetadata = audio.ondurationchange = audio.onerror = null
      audio.removeAttribute('src')
      audio.load()
    }
    function failed() { release(); reject(new Error('无法读取音频时长，请检查网络后重试。')) }
    const timer = setTimeout(failed, 15000)
    audio.preload = 'metadata'
    audio.onloadedmetadata = audio.ondurationchange = () => {
      const duration = audio.duration
      if (Number.isFinite(duration) && duration > 0) { release(); resolve(duration) }
    }
    audio.onerror = failed
    audio.src = audioUrl
    audio.load()
  })
}

async function thumbnailBase64(url: string): Promise<string> {
  const blob = await publicFetch(url, response => response.blob())
  const objectUrl = URL.createObjectURL(blob)
  try {
    const image = new Image()
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve()
      image.onerror = () => reject(new Error('无法读取歌曲封面，请稍后重试。'))
      image.src = objectUrl
    })
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 120
    const context = canvas.getContext('2d')
    if (!context) throw new Error('设备无法处理歌曲封面。')
    context.fillStyle = '#fff'
    context.fillRect(0, 0, 120, 120)
    context.drawImage(image, 0, 0, 120, 120)
    const thumbnail = canvas.toDataURL('image/jpeg', 0.82).split(',')[1]
    if (!thumbnail || atob(thumbnail).length > 32768) throw new Error('歌曲缩略图超出微信大小限制。')
    return thumbnail
  } finally { URL.revokeObjectURL(objectUrl) }
}

export async function shareNativeMusic(id: string, destination: WechatDestination, current = () => true) {
  const status = await bridge.availability()
  if (!status.configured) throw new Error('此安装包尚未配置微信移动应用 AppID。')
  if (!status.installed) throw new Error('请先在此设备安装微信。')
  if (!status.supported) throw new Error('请更新微信以使用音乐卡片。')
  const track = await loadPublicTrack(id)
  if (!current()) return false
  const known = track.durationSeconds
  const [seconds, thumbnail] = await Promise.all([
    known && Number.isFinite(known) && known > 0 ? known : readAudioDuration(track.audio),
    thumbnailBase64(track.thumbnail),
  ])
  const durationMs = Math.round(seconds * 1000)
  if (!Number.isSafeInteger(durationMs) || durationMs < 1 || durationMs > 2147483647) throw new Error('歌曲时长无效。')
  const lrc = /^\s*\[\d+:\d{2}[.:]\d+\]/m.test(track.lyrics) &&
    new TextEncoder().encode(track.lyrics).length <= 32768 ? track.lyrics : undefined
  if (!current()) return false
  await bridge.share({ songId: id, title: track.title, singer: track.creator, durationMs,
    thumbnailBase64: thumbnail, destination, ...(lrc ? { lrc } : {}) })
  return true
}

export async function receiveOpenedSong(open: (id: string) => void) {
  const { songId } = await bridge.consumeOpenedSong()
  if (songId && shareToken.test(songId)) open(songId)
}
