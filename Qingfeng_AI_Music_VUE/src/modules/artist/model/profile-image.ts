const maxBytes = 5 * 1024 * 1024
const invalid = () => new Error('请选择 5 MB 以内的 JPG、PNG 或 WebP 图片。')

function header(blob: Blob): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => reader.result instanceof ArrayBuffer ? resolve(new Uint8Array(reader.result)) : reject(invalid())
    reader.onerror = () => reject(invalid())
    reader.onabort = () => reject(invalid())
    reader.readAsArrayBuffer(blob.slice(0, 12))
  })
}

/** File names and MIME labels can be wrong: a downloaded .JPG may contain WebP. */
export async function prepareProfileImage(file: File): Promise<File> {
  if (!file.size || file.size > maxBytes) throw invalid()
  const bytes = await header(file)
  const starts = (signature: number[], offset = 0) => signature.every((value, index) => bytes[offset + index] === value)
  if (starts([0xff, 0xd8, 0xff]) || starts([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return file
  if (!starts([0x52, 0x49, 0x46, 0x46]) || !starts([0x57, 0x45, 0x42, 0x50], 8)) throw invalid()

  const url = URL.createObjectURL(new Blob([file], { type: 'image/webp' }))
  const canvas = document.createElement('canvas')
  const image = new Image()
  try {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { image.onload = image.onerror = null; reject(invalid()) }, 15_000)
      image.onload = () => { clearTimeout(timer); resolve() }
      image.onerror = () => { clearTimeout(timer); reject(invalid()) }
      image.src = url
    })
    const width = image.naturalWidth, height = image.naturalHeight
    if (width < 1 || height < 1 || width > 8192 || height > 8192 || width * height > 20_000_000) {
      throw new Error('图片尺寸过大，请缩小图片后重试。')
    }
    const scale = Math.min(1, 1600 / Math.max(width, height))
    canvas.width = Math.max(1, Math.round(width * scale))
    canvas.height = Math.max(1, Math.round(height * scale))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法处理图片，请转换为 JPG 或 PNG 后重试。')
    context.fillStyle = '#fff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    const jpeg = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.9))
    if (!jpeg || jpeg.type !== 'image/jpeg' || !jpeg.size || jpeg.size > maxBytes) throw invalid()
    const encoded = await header(jpeg)
    if (encoded[0] !== 0xff || encoded[1] !== 0xd8 || encoded[2] !== 0xff) throw invalid()
    return new File([jpeg], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: 'image/jpeg' })
  } finally {
    image.onload = image.onerror = null
    image.removeAttribute('src')
    URL.revokeObjectURL(url)
    canvas.width = canvas.height = 0
  }
}
