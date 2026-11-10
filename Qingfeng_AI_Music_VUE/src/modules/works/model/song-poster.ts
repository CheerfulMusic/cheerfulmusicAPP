import QRCode from 'qrcode'
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
  const [cover, mark] = await Promise.all([image(input.cover), image('/brand/logo-symbol.svg')])
  const canvas = document.createElement('canvas'); canvas.width = 1080; canvas.height = 1800
  const c = canvas.getContext('2d'); if (!c) throw new Error('当前浏览器无法生成海报，请复制链接分享。')
  c.fillStyle = '#fffaf8'; c.fillRect(0, 0, 1080, 1800)
  c.drawImage(mark, 72, 59, 60, 60)
  c.fillStyle = '#222223'; c.font = `700 35px ${font}`; c.fillText('Cheerful Music', 150, 100)
  c.save(); c.beginPath(); c.roundRect(72, 160, 936, 936, 36); c.clip()
  const side = Math.min(cover.naturalWidth, cover.naturalHeight)
  c.drawImage(cover, (cover.naturalWidth - side) / 2, (cover.naturalHeight - side) / 2, side, side, 72, 160, 936, 936)
  c.restore()
  c.fillStyle = '#222223'; c.font = `700 62px ${font}`
  const title = textLines(input.title, s => c.measureText(s).width, 936, 2)
  title.forEach((line, i) => c.fillText(line, 72, 1190 + i * 80))
  c.fillStyle = '#766a66'; c.font = `400 29px ${font}`
  if (input.signature.trim()) c.fillText(input.signature.trim().slice(0, 24), 72, 1190 + title.length * 80)
  c.fillStyle = '#f93822'; c.font = `400 35px ${font}`
  textLines(input.quote, s => c.measureText(s).width, 936, 2).forEach((line, i) => c.fillText(line, 72, 1410 + i * 49))
  c.strokeStyle = '#eaded9'; c.lineWidth = 2; c.beginPath(); c.moveTo(72, 1500); c.lineTo(1008, 1500); c.stroke()
  const qr = document.createElement('canvas')
  // 在本机编码，不把歌词、分享令牌交给第三方二维码服务。
  await QRCode.toCanvas(qr, input.link, { width: 208, margin: 4, errorCorrectionLevel: 'M', color: { dark: '#222223', light: '#ffffff' } })
  c.drawImage(qr, 800, 1530, 208, 208)
  c.fillStyle = '#222223'; c.font = `700 33px ${font}`
  textLines(input.tagline, s => c.measureText(s).width, 650, 2).forEach((line, i) => c.fillText(line, 72, 1584 + i * 42))
  c.fillStyle = '#766a66'; c.font = `400 27px ${font}`
  textLines(input.scanLabel, s => c.measureText(s).width, 650, 2).forEach((line, i) => c.fillText(line, 72, 1662 + i * 37))
  c.font = `400 23px ${font}`; c.fillText(input.expiresLabel, 72, 1763, 936)
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob)
    : reject(new Error('海报生成失败，请重试或复制链接。')), 'image/png'))
}
