const maxSourceBytes = 50 * 1024 * 1024
const maxSourceSide = 12_000
const maxSourcePixels = 50_000_000
const maxUploadBytes = 1024 * 1024
const jpegQualities = [0.92, 0.8, 0.68, 0.52]

export const imageUploadAccept = 'image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp'

const unreadable = () => new Error('图片无法读取，请换一张重试')
const invalidFormat = () => new Error('请选择 JPG、PNG 或 WebP 图片。')

function header(blob: Blob): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => reader.result instanceof ArrayBuffer ? resolve(new Uint8Array(reader.result)) : reject(unreadable())
    reader.onerror = () => reject(unreadable())
    reader.onabort = () => reject(unreadable())
    reader.readAsArrayBuffer(blob.slice(0, 12))
  })
}

/** Detect the format from the bytes, since the file name and MIME label can disagree. */
async function imageType(blob: Blob) {
  const bytes = await header(blob)
  const starts = (signature: number[], offset = 0) => signature.every((value, index) => bytes[offset + index] === value)
  if (starts([0xff, 0xd8, 0xff])) return 'image/jpeg'
  if (starts([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return 'image/png'
  if (starts([0x52, 0x49, 0x46, 0x46]) && starts([0x57, 0x45, 0x42, 0x50], 8)) return 'image/webp'
  throw invalidFormat()
}

export async function decodeUploadImage(blob: Blob, signal: AbortSignal) {
  if (blob.size > maxSourceBytes) throw new Error('图片超过 50 MB，请选择较小的原图。')
  const type = await imageType(blob)
  if (signal.aborted) throw unreadable()
  const url = URL.createObjectURL(blob.type === type ? blob : blob.slice(0, blob.size, type))
  const element = new Image()
  const release = () => {
    element.removeAttribute('src')
    URL.revokeObjectURL(url)
  }
  try {
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(failed, 15_000)
      function cleanup() {
        clearTimeout(timer)
        signal.removeEventListener('abort', failed)
        element.onload = element.onerror = null
      }
      function failed() {
        cleanup()
        reject(unreadable())
      }
      element.onload = () => { cleanup(); resolve() }
      element.onerror = failed
      signal.addEventListener('abort', failed, { once: true })
      element.src = url
    })
    const { naturalWidth: width, naturalHeight: height } = element
    if (width < 1 || height < 1 || width > maxSourceSide || height > maxSourceSide || width * height > maxSourcePixels) {
      throw new Error('图片尺寸过大，请缩小图片后重试。')
    }
    return { element, release }
  } catch (error) {
    release()
    throw error
  }
}

export async function encodeUploadJpeg(canvas: HTMLCanvasElement, filename: string) {
  for (const quality of jpegQualities) {
    const jpeg = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality))
    if (!jpeg?.size || jpeg.type !== 'image/jpeg') throw unreadable()
    if (jpeg.size > maxUploadBytes) continue
    if (await imageType(jpeg) !== 'image/jpeg') throw unreadable()
    return new File([jpeg], filename, { type: 'image/jpeg' })
  }
  throw new Error('图片压缩后仍超过 1 MB，请更换图片。')
}
