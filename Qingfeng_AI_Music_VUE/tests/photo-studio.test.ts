// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import PhotoStudioPage from '../src/modules/studio/pages/PhotoStudioPage.vue'
import { saveWork } from '../src/modules/studio/api/works'
import { generatePhotoLyrics, getLyricsRequest } from '../src/modules/works/api/detail'
import { preparePhoto } from '../src/modules/studio/model/photo-upload'

vi.mock('../src/shared/http/api-client', () => ({
  currentCreatorId: () => 'photo-test-owner',
  ensureCreator: async () => {},
}))
vi.mock('../src/shared/http/system', () => ({
  getPhotoLyricsAvailable: async () => true,
}))
vi.mock('../src/shared/credits/useCredits', () => ({
  useCredits: () => ({ credits: { value: null }, refreshCredits: async () => {} }),
}))
vi.mock('../src/modules/studio/api/works', () => ({ saveWork: vi.fn() }))
vi.mock('../src/modules/works/api/detail', () => ({
  generatePhotoLyrics: vi.fn(),
  getLyricsRequest: vi.fn(),
}))
vi.mock('../src/modules/studio/model/photo-upload', async importOriginal => ({
  ...await importOriginal<typeof import('../src/modules/studio/model/photo-upload')>(),
  preparePhoto: vi.fn(async (file: File) => file),
}))

afterEach(() => {
  sessionStorage.clear()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

async function setup(embedded = false) {
  let previewId = 0
  vi.stubGlobal('URL', Object.assign(class extends URL {}, {
    createObjectURL: () => `blob:photo-test-${++previewId}`,
    revokeObjectURL: () => {},
  }))
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/create', component: { template: '<div />' } },
    { path: '/create/photo', component: PhotoStudioPage },
    { path: '/works/:id', component: { template: '<div />' } },
  ] })
  await router.push('/create/photo')
  const page = mount(PhotoStudioPage, { props: { embedded }, global: { plugins: [router] } })
  await flushPromises()
  return { page, router }
}

it('returns an embedded photo result to the conversation without navigating or submitting music', async () => {
  const { page, router } = await setup(true)
  try {
    vi.mocked(saveWork).mockImplementation(async (draft, _, key) => ({ ...draft, id: key!, version: 0, createdAt: '', updatedAt: '' }))
    vi.mocked(generatePhotoLyrics).mockResolvedValue({ id: 'photo-lyric', workId: '', workVersion: 0, title: '海边', lyrics: '风吹过海面', source: 'OPENAI', confirmed: false, createdAt: '' })
    const file = new File(['picture'], 'beach.png', { type: 'image/png' }), input = page.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change'); await flushPromises(); await page.get('form').trigger('submit'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/create/photo')
    const result = page.emitted('created')?.[0]?.[0] as { workId: string; revisionId: string; language: string }
    expect(result).toEqual({ workId: expect.stringMatching(/^[\da-f-]{36}$/), revisionId: 'photo-lyric', language: 'zh-CN' })
    expect(sessionStorage.getItem('qingfeng.photo-draft:photo-test-owner')).toBeNull()
    expect(page.find('.detail-back').text()).toContain('返回对话')
    expect(generatePhotoLyrics).toHaveBeenCalledExactlyOnceWith(result.workId, 0, expect.any(String), 'zh-CN', [file])
  } finally { page.unmount() }
})

it('uploads a single photo with the optional story and opens the unconfirmed lyric draft', async () => {
  const { page, router } = await setup()
  try {
    vi.mocked(saveWork).mockImplementation(async (draft, _, key) => ({
      ...draft, id: key!, version: 0, createdAt: '', updatedAt: '',
    }))
    vi.mocked(generatePhotoLyrics).mockResolvedValue({
      id: 'lyric-revision', workId: '', workVersion: 0, title: '海边', lyrics: '风吹过海面',
      source: 'OPENAI', confirmed: false, createdAt: '',
    })
    const maxBytes = 10 * 1024 * 1024
    const input = page.get('input[type=file]')
    const oversized = new File([new Uint8Array(maxBytes + 1)], 'large.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [oversized] })
    await input.trigger('change')
    expect(page.get('[role=alert]').text()).toContain('10 MB')
    expect(page.find('img').exists()).toBe(false)

    const file = new File([new Uint8Array(maxBytes)], 'beach.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
    await input.trigger('change')
    await flushPromises()
    expect(page.get('img').attributes('src')).toBe('blob:photo-test-1')
    await page.get('textarea').setValue('这是妈妈第一次看到海。')
    await page.get('form').trigger('submit')
    await flushPromises()
    const id = router.currentRoute.value.params.id
    expect(saveWork).toHaveBeenCalledWith(expect.objectContaining({ story: '这是妈妈第一次看到海。' }), undefined, id)
    expect(generatePhotoLyrics).toHaveBeenCalledExactlyOnceWith(id, 0, expect.any(String), 'zh-CN', [file])
    expect(router.currentRoute.value.query.revision).toBe('lyric-revision')
    expect(getLyricsRequest).not.toHaveBeenCalled()
  } finally { page.unmount() }
})

it('limits the selection to five and uploads the prepared photos in their displayed order', async () => {
  const { page } = await setup()
  try {
    const originals = Array.from({ length: 6 }, (_, index) => new File(['original'], `${index}.png`, { type: 'image/png' }))
    const prepared = originals.slice(0, 5).map(file => new File(['compressed'], file.name.replace('.png', '.jpg'), { type: 'image/jpeg' }))
    for (const file of prepared) vi.mocked(preparePhoto).mockResolvedValueOnce(file)
    const input = page.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: originals })
    await input.trigger('change')
    expect(page.get('[role=alert]').text()).toContain('最多可选 5 张')
    expect(preparePhoto).not.toHaveBeenCalled()
    Object.defineProperty(input.element, 'files', { configurable: true, value: originals.slice(0, 5) })
    await input.trigger('change')
    await flushPromises()
    expect(page.get('[role=status]').text()).toContain('5/5')
    expect(input.attributes()).toHaveProperty('disabled')
    await page.get('button[aria-label="删除第 2 张照片"]').trigger('click')
    expect(page.get('[role=status]').text()).toContain('4/5')
    expect(input.attributes()).not.toHaveProperty('disabled')
    await page.get('form').trigger('submit')
    await flushPromises()
    expect(generatePhotoLyrics).toHaveBeenCalledExactlyOnceWith(
      expect.any(String), 0, expect.any(String), 'zh-CN', [prepared[0], ...prepared.slice(2)],
    )
  } finally { page.unmount() }
})

it('downsizes a large photo and tries a lower JPEG quality when the first result exceeds 1 MB', async () => {
  const { preparePhoto: compress } = await vi.importActual<typeof import('../src/modules/studio/model/photo-upload')>(
    '../src/modules/studio/model/photo-upload',
  )
  const createObjectURL = vi.fn(() => 'blob:original')
  const revokeObjectURL = vi.fn()
  vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
  vi.stubGlobal('Image', class {
    naturalWidth = 3200
    naturalHeight = 2400
    async decode() {}
  })
  const drawImage = vi.fn()
  const context = vi.spyOn(HTMLCanvasElement.prototype, 'getContext')
    .mockReturnValue({ drawImage, fillRect: vi.fn() } as unknown as CanvasRenderingContext2D)
  const toBlob = vi.spyOn(HTMLCanvasElement.prototype, 'toBlob')
    .mockImplementationOnce(callback => callback(new Blob([new Uint8Array(1024 * 1024 + 1)], { type: 'image/jpeg' })))
    .mockImplementationOnce(callback => callback(new Blob(['jpeg'], { type: 'image/jpeg' })))
  try {
    const result = await compress(new File(['original'], 'travel.png', { type: 'image/png' }))
    expect(result.name).toBe('travel.jpg')
    expect(result.size).toBe(4)
    expect(drawImage).toHaveBeenLastCalledWith(expect.anything(), 0, 0, 1600, 1200)
    expect(toBlob).toHaveBeenLastCalledWith(expect.any(Function), 'image/jpeg', 0.68)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:original')
  } finally {
    context.mockRestore()
    toBlob.mockRestore()
  }
})

it('finds the saved result after a refresh without re-uploading the photo', async () => {
  const id = '43d0686c-f81d-4db4-a2a4-f5aa7d77fe70'
  const key = '79e7841f-b61a-4a33-a353-f4d97584ed84'
  sessionStorage.setItem('qingfeng.photo-draft:photo-test-owner', JSON.stringify({ id, key, note: '', language: 'en', submitted: true }))
  vi.mocked(getLyricsRequest).mockResolvedValue({ status: 'SUCCEEDED', revisionId: 'restored-lyric', errorCode: null })
  const { page, router } = await setup()
  try {
    expect(getLyricsRequest).toHaveBeenCalledExactlyOnceWith(id, key)
    expect(generatePhotoLyrics).not.toHaveBeenCalled()
    expect(router.currentRoute.value.fullPath).toBe(`/works/${id}?revision=restored-lyric&language=en`)
    expect(sessionStorage.getItem('qingfeng.photo-draft:photo-test-owner')).toBeNull()
  } finally { page.unmount() }
})
