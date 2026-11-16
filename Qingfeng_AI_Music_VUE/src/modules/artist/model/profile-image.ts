export type ProfileImageKind = 'avatar' | 'cover'
export interface CropPosition { zoom: number; x: number; y: number }
export const defaultCrop: CropPosition = { zoom: 1, x: 0.5, y: 0.5 }
export const cropSize = { avatar: { width: 384, height: 384 }, cover: { width: 1600, height: 400 } }

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
