import QRCode from 'qrcode'
import { wechatShareEntry, type WechatDestination } from './song-sharing'
export { lyricQuotes, posterFilename } from './song-sharing'
export function wechatShareQr(link: string, destination: WechatDestination): Promise<string> {
  return QRCode.toDataURL(wechatShareEntry(link, destination), { width: 288, margin: 4, errorCorrectionLevel: 'M' })
}
export interface PosterInput {
  title: string; cover: string; link: string; quote: string; signature: string
  tagline: string; scanLabel: string; expiresLabel: string
}
const font = '"Microsoft YaHei", "PingFang SC", system-ui, sans-serif'
function image(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    const timer = setTimeout(() => { img.src = ''; reject(new Error('封面加载失败，请重试生成海报。')) }, 15000)
    img.onload = () => { clearTimeout(timer); resolve(img) }
    img.onerror = () => { clearTimeout(timer); reject(new Error('封面加载失败，请重试生成海报。')) }
    img.crossOrigin = 'anonymous'
    img.src = url
  })
}
/** 按实际字宽换行；保留中英文和 Unicode 码点，不改写原歌词。 */
export function textLines(text: string, measure: (s: string) => number, width: number, max: number): string[] {
  const lines: string[] = []; let current = ''
  for (const char of Array.from(text.replace(/\s+/g, ' ').trim())) {
    if (current && measure(current + char) > width) { lines.push(current); current = '' }
    current += char
  }
  if (current) lines.push(current)
  if (lines.length <= max) return lines
  const kept = lines.slice(0, max); let last = kept[max - 1] || ''
  while (last && measure(last + '…') > width) last = Array.from(last).slice(0, -1).join('')
  kept[max - 1] = last + '…'; return kept
}
export async function renderSongPoster(input: PosterInput): Promise<Blob> {
  await document.fonts?.ready
  const cover = await image(input.cover).catch(() => image('/brand/share-default-cover.png'))
  const canvas = document.createElement('canvas')
  canvas.width = 1200
  canvas.height = 924
  const c = canvas.getContext('2d')
  if (!c) throw new Error('当前浏览器无法生成海报，请复制链接分享。')
  c.fillStyle = '#fffefc'
  c.fillRect(0, 0, canvas.width, canvas.height)
  c.save()
  c.beginPath()
  if (typeof c.roundRect === 'function') c.roundRect(616, 32, 552, 552, 24)
  else c.rect(616, 32, 552, 552)
  c.clip()
  const side = Math.min(cover.naturalWidth, cover.naturalHeight)
  c.drawImage(cover, (cover.naturalWidth - side) / 2, (cover.naturalHeight - side) / 2, side, side, 616, 32, 552, 552)
  c.restore()
  const copyWidth = 536
  const measure = (line: string) => c.measureText(line).width
  c.fillStyle = '#18221e'
  c.font = `700 56px ${font}`
  const title = textLines(input.title, measure, copyWidth, 2)
  let y = title.length > 1 ? 148 : 198
  for (const line of title) { c.fillText(line, 44, y); y += 72 }
  c.fillStyle = '#414c45'
  c.font = `400 40px ${font}`
  for (const line of textLines(input.signature, measure, copyWidth, 2)) { c.fillText(line, 44, y); y += 52 }
  c.fillStyle = '#65716a'
  c.font = `400 34px ${font}`
  for (const line of textLines(input.quote || input.tagline, measure, copyWidth, 2)) {
    c.fillText(line, 44, y + 10)
    y += 45
  }
  c.fillStyle = '#65716a'
  c.font = `500 32px ${font}`
  c.fillText('青风音乐 Cheerful Music', 44, 555)

  c.fillStyle = '#edf6ef'
  c.fillRect(0, 604, canvas.width, canvas.height - 604)
  c.fillStyle = '#236742'
  c.font = `650 44px ${font}`
  const scanLines = textLines(input.scanLabel || '长按识别二维码试听', measure, 808, 2)
  const baseline = input.expiresLabel || scanLines.length > 1 ? 743 : 781
  scanLines.forEach((line, i) => c.fillText(line, 44, baseline + i * 53))
  if (input.expiresLabel) {
    c.fillStyle = '#54685b'
    c.font = `400 25px ${font}`
    c.fillText(input.expiresLabel, 44, baseline + (scanLines.length - 1) * 53 + 51, 808)
  }
  const qr = document.createElement('canvas')
  await QRCode.toCanvas(qr, input.link, { width: 296, margin: 4, errorCorrectionLevel: 'M' })
  c.drawImage(qr, canvas.width - 32 - qr.width, canvas.height - 12 - qr.height)
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob)
    : reject(new Error('海报生成失败，请重试或复制链接。')), 'image/png'))
}
