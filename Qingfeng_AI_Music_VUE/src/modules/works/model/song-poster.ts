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
function image(url: string, error = '封面加载失败，请重试生成海报。'): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    const timer = setTimeout(() => { img.src = ''; reject(new Error(error)) }, 15000)
    img.onload = () => { clearTimeout(timer); resolve(img) }
    img.onerror = () => { clearTimeout(timer); reject(new Error(error)) }
    img.crossOrigin = 'anonymous'
    img.src = url
  })
}
/** 按实际字宽换行；保留中英文和 Unicode 码点，不改写原歌词。 */
export function textLines(text: string, measure: (s: string) => number, width: number, max: number): string[] {
  const lines: string[] = []
  let current = ''
  for (const char of text.replace(/\s+/g, ' ').trim()) {
    if (current && measure(current + char) > width) {
      if (lines.length === max - 1) {
        const last = Array.from(current)
        while (last.length && measure(last.join('') + '…') > width) last.pop()
        lines.push(last.join('') + '…')
        return lines
      }
      lines.push(current)
      current = ''
    }
    current += char
  }
  if (current) lines.push(current)
  return lines
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

interface InternationalPosterInput {
  title: string
  artist?: string
  cover: string
  link: string
  tagline?: string
  quote?: string
  signature?: string
  scanLabel?: string
}

export async function renderInternationalSongPoster(input: InternationalPosterInput): Promise<Blob> {
  const [cover, brand] = await Promise.all([
    image(input.cover, 'The song cover could not be loaded. Try again or copy the link.'),
    image('/brand/logo-symbol.svg', 'The poster could not be generated. Try again or copy the link.'),
    document.fonts?.ready,
  ])
  const canvas = document.createElement('canvas')
  canvas.width = 1080
  canvas.height = 1920
  const c = canvas.getContext('2d')
  if (!c) throw new Error('This browser cannot generate a poster. You can copy the link.')

  const margin = 72
  const width = canvas.width - 2 * margin
  const background = c.createLinearGradient(0, 0, canvas.width, canvas.height)
  background.addColorStop(0, '#f9e4d8')
  background.addColorStop(.57, '#fffbf4')
  background.addColorStop(1, '#f2eee5')
  c.fillStyle = background
  c.fillRect(0, 0, canvas.width, canvas.height)

  c.drawImage(brand, margin, 70, 75, 75 * 37 / 41.5)
  c.fillStyle = '#16251d'
  c.font = `700 49px ${font}`
  c.fillText('Cheerful Music', margin + 98, 112)
  c.fillStyle = '#5b6157'
  c.font = `400 28px ${font}`
  c.fillText('cheerfulmusic.com', margin + 100, 153)

  c.save()
  c.beginPath()
  if (typeof c.roundRect === 'function') c.roundRect(margin, 202, width, width, 45)
  else c.rect(margin, 202, width, width)
  c.clip()
  const side = Math.min(cover.naturalWidth, cover.naturalHeight)
  c.drawImage(cover, (cover.naturalWidth - side) / 2, (cover.naturalHeight - side) / 2, side, side,
    margin, 202, width, width)
  c.restore()

  const measure = (line: string) => c.measureText(line).width
  c.fillStyle = '#16251d'
  c.font = `700 74px ${font}`
  const title = textLines(input.title, measure, width, 3)
  title.forEach((line, index) => c.fillText(line, margin, 1240 + index * 81))
  c.fillStyle = '#5b6157'
  c.font = `400 42px ${font}`
  const [artist] = textLines(input.artist ?? '', measure, width, 1)
  const creditY = 1240 + title.length * 81
  if (artist) c.fillText(artist, margin, creditY)
  const signature = input.signature?.trim()
  if (signature && signature !== input.artist?.trim()) {
    c.font = `400 32px ${font}`
    const [credit] = textLines(signature, measure, width, 1)
    c.fillText(credit, margin, creditY + (artist ? 46 : 0))
  }

  const footerY = 1552
  const footerHeight = 296
  c.fillStyle = '#dceee1'
  c.beginPath()
  if (typeof c.roundRect === 'function') c.roundRect(margin, footerY, width, footerHeight, 40)
  else c.rect(margin, footerY, width, footerHeight)
  c.fill()
  c.fillStyle = '#183e2d'
  c.font = `650 46px ${font}`
  const invitation = textLines(input.quote?.trim() || input.tagline || 'Come listen to my song', measure, 560, 2)
  const baseline = invitation.length === 1 ? 1689 : 1659
  invitation.forEach((line, index) => c.fillText(line, margin + 42, baseline + index * 54))
  c.font = `400 30px ${font}`
  const scanLines = textLines(input.scanLabel?.trim() || 'Scan to listen', measure, 560, 2)
  scanLines.forEach((line, index) => c.fillText(line, margin + 42, baseline + invitation.length * 54 + index * 38))

  const qr = document.createElement('canvas')
  await QRCode.toCanvas(qr, input.link, { width: 236, margin: 4, errorCorrectionLevel: 'M' })
  c.drawImage(qr, canvas.width - margin - qr.width - 30, footerY + (footerHeight - qr.height) / 2)
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob)
    : reject(new Error('The poster could not be generated. Try again or copy the link.')), 'image/png'))
}
