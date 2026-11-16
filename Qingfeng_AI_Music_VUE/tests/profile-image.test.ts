// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { decodeProfileImage, defaultCrop, drawProfileCrop, encodeProfileCrop } from '../src/modules/artist/model/profile-image'

const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3])
const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1])
const webp = new Uint8Array([0x52, 0x49, 0x46, 0x46, 12, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20])
const maxBytes = 5 * 1024 * 1024
let width = 500, height = 375, decoding: 'success' | 'error' | 'pending'
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
      if (decoding === 'error') this.onerror?.()
    })
  }
}

function decode(bytes: Uint8Array<ArrayBuffer>, type = 'image/jpeg') {
  return decodeProfileImage(new File([bytes], 'portrait.JPG', { type }), new AbortController().signal)
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
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('profile image crop', () => {
  it.each([
    [jpeg, '', 'image/jpeg'],
    [png, 'image/jpeg', 'image/png'],
    [webp, 'image/jpeg', 'image/webp'],
    [webp, 'image/webp', 'image/webp'],
  ] as const)('uses the recognized binary format for decoding', async (bytes, mime, expected) => {
    const image = await decode(bytes, mime)
    expect((createUrl.mock.calls[0]?.[0] as Blob).type).toBe(expected)
    expect(revokeUrl).not.toHaveBeenCalled()
    expect(image.element).toBe(loadedImage)
    expect(loadedImage?.onload).toBeNull()
    expect(loadedImage?.onerror).toBeNull()
    image.release()
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
    expect(loadedImage?.removeAttribute).toHaveBeenCalledWith('src')
  })

  it.each([
    new Uint8Array(), new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"/>'),
    new TextEncoder().encode('RIFF0000NOTP'), webp.slice(0, 10),
    new Uint8Array(maxBytes + 1),
  ])('rejects unsupported, incomplete or oversized data before creating a preview', async bytes => {
    await expect(decode(bytes)).rejects.toThrow('5 MB')
    expect(createUrl).not.toHaveBeenCalled()
  })

  it('releases the temporary URL when an image cannot be decoded', async () => {
    decoding = 'error'
    await expect(decode(jpeg)).rejects.toThrow('图片无法读取')
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
  })

  it('aborts the decoder and releases its URL while an image is loading', async () => {
    decoding = 'pending'
    const controller = new AbortController()
    createUrl.mockImplementationOnce(() => {
      queueMicrotask(() => controller.abort())
      return 'blob:profile-image-source'
    })
    await expect(decodeProfileImage(new Blob([jpeg]), controller.signal)).rejects.toThrow('图片无法读取')
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
    expect(loadedImage?.onload).toBeNull()
    expect(loadedImage?.onerror).toBeNull()
  })

  it.each([[0, 100], [100, 0], [8193, 1], [1, 8193], [5000, 4001]])('rejects unsafe dimensions %s × %s before drawing', async (w, h) => {
    width = w; height = h
    await expect(decode(webp)).rejects.toThrow('尺寸过大')
    expect(context.drawImage).not.toHaveBeenCalled()
    expect(revokeUrl).toHaveBeenCalledExactlyOnceWith('blob:profile-image-source')
  })

  it.each([[8192, 1], [5000, 4000]])('accepts the permitted dimension boundary %s × %s', async (w, h) => {
    width = w; height = h
    const image = await decode(webp)
    expect(image.element.naturalWidth).toBe(w)
    image.release()
  })

  it('uses a centered square for the avatar and the selected 4:1 region for the cover', async () => {
    const image = await decode(webp)
    const canvas = document.createElement('canvas')
    canvas.width = 384; canvas.height = 384
    drawProfileCrop(canvas, image.element, 'avatar', defaultCrop)
    expect(context.drawImage).toHaveBeenLastCalledWith(loadedImage, 62.5, 0, 375, 375, 0, 0, 384, 384)
    canvas.width = 1600; canvas.height = 400
    drawProfileCrop(canvas, image.element, 'cover', { zoom: 2, x: 1, y: 0 })
    expect(context.drawImage).toHaveBeenLastCalledWith(loadedImage, 250, 0, 250, 62.5, 0, 0, 1600, 400)
    expect(context.fillStyle).toBe('#fff')
    expect(context.fillRect).toHaveBeenLastCalledWith(0, 0, 1600, 400)
    image.release()
    expect(await encodeProfileCrop(canvas, 'cover')).toMatchObject({ name: 'cover.jpg', type: 'image/jpeg', size: jpeg.length })
  })

  it.each(['null', 'empty', 'wrong-mime', 'wrong-bytes', 'oversize'])('rejects invalid encoder output: %s', async failure => {
    if (failure === 'null') encoded = null
    if (failure === 'empty') encoded = new Blob([], { type: 'image/jpeg' })
    if (failure === 'wrong-mime') encoded = new Blob([jpeg], { type: 'image/png' })
    if (failure === 'wrong-bytes') encoded = new Blob([png], { type: 'image/jpeg' })
    if (failure === 'oversize') encoded = new Blob([new Uint8Array(maxBytes + 1)], { type: 'image/jpeg' })
    await expect(encodeProfileCrop(document.createElement('canvas'), 'avatar')).rejects.toThrow()
  })
})
