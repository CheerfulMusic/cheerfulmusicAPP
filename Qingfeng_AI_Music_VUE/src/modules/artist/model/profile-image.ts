const maxBytes = 5 * 1024 * 1024
const invalid = () => new Error('请选择 5 MB 以内的 JPG、PNG 或 WebP 图片。')
export const profileImageAccept = 'image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp'
export type ProfileImageKind = 'avatar' | 'cover'
export interface CropPosition { zoom: number; x: number; y: number }
export const defaultCrop: CropPosition = { zoom: 1, x: 0.5, y: 0.5 }
export const cropSize = { avatar: { width: 384, height: 384 }, cover: { width: 1600, height: 400 } }

function header(blob: Blob): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => reader.result instanceof ArrayBuffer ? resolve(new Uint8Array(reader.result)) : reject(invalid())
    reader.onerror = () => reject(invalid())
    reader.onabort = () => reject(invalid())
    reader.readAsArrayBuffer(blob.slice(0, 12))
  })
}

/** Image bytes can disagree with the file name or MIME label. */
async function imageType(blob: Blob) {
  if (!blob.size || blob.size > maxBytes) throw invalid()
  const bytes = await header(blob)
  const starts = (signature: number[], offset = 0) => signature.every((value, index) => bytes[offset + index] === value)
  if (starts([0xff, 0xd8, 0xff])) return 'image/jpeg'
  if (starts([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) return 'image/png'
  if (starts([0x52, 0x49, 0x46, 0x46]) && starts([0x57, 0x45, 0x42, 0x50], 8)) return 'image/webp'
  throw invalid()
}

export async function decodeProfileImage(blob: Blob, signal: AbortSignal) {
  const type = await imageType(blob)
  const url = URL.createObjectURL(new Blob([blob], { type }))
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
        reject(new Error('图片无法读取，请换一张重试'))
      }
      element.onload = () => { cleanup(); resolve() }
      element.onerror = failed
      if (signal.aborted) failed()
      else {
        signal.addEventListener('abort', failed, { once: true })
        element.src = url
      }
    })
    const { naturalWidth: width, naturalHeight: height } = element
    if (width < 1 || height < 1 || width > 8192 || height > 8192 || width * height > 20_000_000) {
      throw new Error('图片尺寸过大，请缩小图片后重试。')
    }
    return { element, release }
  } catch (error) {
    release()
    throw error
  }
}

export function cropBounds(image: HTMLImageElement, kind: ProfileImageKind, position: CropPosition) {
  const { width, height } = cropSize[kind]
  const ratio = width / height
  const cropWidth = Math.min(image.naturalWidth, image.naturalHeight * ratio) / position.zoom
  const cropHeight = cropWidth / ratio
  const horizontalTravel = image.naturalWidth - cropWidth
  const verticalTravel = image.naturalHeight - cropHeight
  return {
    x: position.x * horizontalTravel, y: position.y * verticalTravel,
    width: cropWidth, height: cropHeight, horizontalTravel, verticalTravel,
  }
}

export function drawProfileCrop(canvas: HTMLCanvasElement, image: HTMLImageElement, kind: ProfileImageKind, position: CropPosition) {
  const context = canvas.getContext('2d')
  if (!context) throw new Error('当前浏览器无法处理图片，请转换为 JPG 或 PNG 后重试。')
  const bounds = cropBounds(image, kind, position)
  context.fillStyle = '#fff'
  context.fillRect(0, 0, canvas.width, canvas.height)
  context.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, 0, 0, canvas.width, canvas.height)
}

export async function encodeProfileCrop(canvas: HTMLCanvasElement, kind: ProfileImageKind) {
  const jpeg = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92))
  if (!jpeg || jpeg.type !== 'image/jpeg' || await imageType(jpeg) !== 'image/jpeg') throw invalid()
  return new File([jpeg], `${kind}.jpg`, { type: 'image/jpeg' })
}
