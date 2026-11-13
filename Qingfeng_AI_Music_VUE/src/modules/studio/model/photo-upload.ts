export const maxPhotoCount = 5
export const maxOriginalPhotoBytes = 10 * 1024 * 1024
const maxUploadPhotoBytes = 1024 * 1024
const maxPhotoEdge = 1600

export function canSelectPhoto(file: File): boolean {
  return ['image/jpeg', 'image/png'].includes(file.type)
    && file.size > 0 && file.size <= maxOriginalPhotoBytes
}

export async function preparePhoto(file: File): Promise<File> {
  const source = URL.createObjectURL(file)
  const canvas = document.createElement('canvas')
  try {
    const image = new Image()
    image.src = source
    await image.decode()
    const { naturalWidth: width, naturalHeight: height } = image
    const longest = Math.max(width, height)
    if (Math.min(width, height) < 16) throw new Error('照片尺寸太小。')
    if (longest <= maxPhotoEdge && file.size <= maxUploadPhotoBytes) return file

    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法处理照片。')
    for (const { edge, quality } of [
      { edge: maxPhotoEdge, quality: 0.86 },
      { edge: maxPhotoEdge, quality: 0.68 },
      { edge: 1024, quality: 0.72 },
    ]) {
      const scale = Math.min(1, edge / longest)
      canvas.width = Math.max(16, Math.round(width * scale))
      canvas.height = Math.max(16, Math.round(height * scale))
      context.fillStyle = '#fff'
      context.fillRect(0, 0, canvas.width, canvas.height)
      context.drawImage(image, 0, 0, canvas.width, canvas.height)
      const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality))
      if (blob?.type === 'image/jpeg' && blob.size > 0 && blob.size <= maxUploadPhotoBytes) {
        return new File([blob], file.name.replace(/\.[^.]+$/, '') + '.jpg', { type: blob.type })
      }
    }
    throw new Error('照片压缩失败。')
  } finally {
    URL.revokeObjectURL(source)
    canvas.width = canvas.height = 0
  }
}
