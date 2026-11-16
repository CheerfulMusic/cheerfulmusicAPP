// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { prepareProfileImage } from '../src/modules/artist/model/profile-image'

// These are signature fixtures. Actual WebP decoding/encoding is covered by the
// separate real-browser acceptance, not by this mocked canvas.
const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3])
const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1])
const webp = new Uint8Array([0x52, 0x49, 0x46, 0x46, 12, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20])
const maxBytes = 5 * 1024 * 1024

let width = 500, height = 375, decoding: 'success' | 'error' | 'timeout' = 'success'
let encoded: Blob | null
let loadedImage: FakeImage | undefined
const context = { fillStyle: '', fillRect: vi.fn(), drawImage: vi.fn() }
const createUrl = vi.fn((_blob: Blob | MediaSource) => 'blob:profile-image-source')
const revokeUrl = vi.fn()

class FakeImage {
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  naturalWidth = width
  naturalHeight = height
  removeAttribute = vi.fn()
  constructor() { loadedImage = this }
  set src(_url: string) {
    queueMicrotask(() => {
      if (decoding === 'success') this.onload?.()
      else if (decoding === 'error') this.onerror?.()
    })
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  width = 500; height = 375; decoding = 'success'; loadedImage = undefined
  encoded = new Blob([jpeg], { type: 'image/jpeg' })
  vi.stubGlobal('Image', FakeImage)
  vi.spyOn(URL, 'createObjectURL').mockImplementation(createUrl)
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(revokeUrl)
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => { callback(encoded) })
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('profile image preparation', () => {
  it.each([
    ['photo.JPG', 'image/jpeg', jpeg],
    ['photo.png', 'image/png', png],
    ['no-extension', '', jpeg],
    ['wrong.jpg', 'image/jpeg', png],
  ] as const)('recognizes actual JPG/PNG bytes and preserves the original file: %s', async (name, type, bytes) => {
    const original = new File([bytes], name, { type })
    expect(await prepareProfileImage(original)).toBe(original)
    expect(createUrl).not.toHaveBeenCalled()
    expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled()
  })

  it.each(['image/jpeg', 'image/webp', ''])('converts WebP disguised as .JPG regardless of MIME label: %s', async type => {
    const original = new File([webp], 'ims.JPG', { type })
    const result = await prepareProfileImage(original)
    expect(result).not.toBe(original)
    expect(result.name).toBe('ims.jpg')
    expect(result.type).toBe('image/jpeg')
    expect(result.size).toBe(jpeg.length)
    expect(original.name).toBe('ims.JPG')
    expect(original.size).toBe(webp.length)
    const inputBlob = createUrl.mock.calls[0]?.[0] as Blob
    expect(inputBlob.type).toBe('image/webp')
    expect(context.fillStyle).toBe('#fff')
    expect(context.fillRect).toHaveBeenCalledWith(0, 0, 500, 375)
    expect(context.drawImage).toHaveBeenCalledWith(loadedImage, 0, 0, 500, 375)
    expect(HTMLCanvasElement.prototype.toBlob).toHaveBeenCalledWith(expect.any(Function), 'image/jpeg', 0.9)
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
    expect(loadedImage?.removeAttribute).toHaveBeenCalledWith('src')
    expect(loadedImage?.onload).toBeNull()
    expect(loadedImage?.onerror).toBeNull()
  })

  it('downscales large WebP while preserving aspect ratio', async () => {
    width = 4000; height = 2250
    await prepareProfileImage(new File([webp], 'cover.webp'))
    expect(context.fillRect).toHaveBeenCalledWith(0, 0, 1600, 900)
    expect(context.drawImage).toHaveBeenCalledWith(loadedImage, 0, 0, 1600, 900)
    const canvas = vi.mocked(HTMLCanvasElement.prototype.getContext).mock.instances[0] as HTMLCanvasElement | undefined
    expect(canvas?.width).toBe(0)
    expect(canvas?.height).toBe(0)
  })

  it.each([
    ['empty.jpg', new Uint8Array()],
    ['fake.jpg', new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"/>')],
    ['fake.png', new TextEncoder().encode('<html>not an image</html>')],
    ['almost.webp', new TextEncoder().encode('RIFF0000NOTP')],
    ['short.webp', webp.slice(0, 10)],
  ])('rejects unsupported or incomplete content instead of trusting the name: %s', async (name, bytes) => {
    await expect(prepareProfileImage(new File([bytes], name, { type: 'image/jpeg' }))).rejects.toThrow()
    expect(createUrl).not.toHaveBeenCalled()
  })

  it('rejects inputs over 5 MB before creating a preview or decoder', async () => {
    const bytes = new Uint8Array(maxBytes + 1); bytes.set(webp)
    await expect(prepareProfileImage(new File([bytes], 'large.JPG', { type: 'image/jpeg' }))).rejects.toThrow('5 MB')
    expect(createUrl).not.toHaveBeenCalled()
  })

  it('releases the temporary URL when a WebP cannot be decoded', async () => {
    decoding = 'error'
    await expect(prepareProfileImage(new File([webp], 'broken.JPG'))).rejects.toThrow()
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
    expect(HTMLCanvasElement.prototype.toBlob).not.toHaveBeenCalled()
  })

  it.each([[0, 100], [100, 0], [8193, 1], [1, 8193], [5000, 4001]])('rejects unsafe dimensions %s × %s before drawing', async (w, h) => {
    width = w; height = h
    await expect(prepareProfileImage(new File([webp], 'oversize.webp'))).rejects.toThrow('尺寸过大')
    expect(context.drawImage).not.toHaveBeenCalled()
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
  })

  it.each([[8192, 1], [5000, 4000]])('accepts the exact permitted dimension boundary %s × %s', async (w, h) => {
    width = w; height = h
    expect((await prepareProfileImage(new File([webp], 'boundary.webp'))).type).toBe('image/jpeg')
  })

  it.each(['null', 'empty', 'wrong-mime', 'wrong-bytes', 'oversize'])('rejects invalid encoder output: %s', async failure => {
    if (failure === 'null') encoded = null
    if (failure === 'empty') encoded = new Blob([], { type: 'image/jpeg' })
    if (failure === 'wrong-mime') encoded = new Blob([jpeg], { type: 'image/png' })
    if (failure === 'wrong-bytes') encoded = new Blob([png], { type: 'image/jpeg' })
    if (failure === 'oversize') encoded = new Blob([new Uint8Array(maxBytes + 1)], { type: 'image/jpeg' })
    await expect(prepareProfileImage(new File([webp], 'image.webp'))).rejects.toThrow()
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
  })

  it('reports unsupported canvas without leaking the image URL', async () => {
    vi.mocked(HTMLCanvasElement.prototype.getContext).mockReturnValue(null)
    await expect(prepareProfileImage(new File([webp], 'image.webp'))).rejects.toThrow('当前浏览器')
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
  })
})
