// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import PhotoStudioPage from '../src/modules/studio/pages/PhotoStudioPage.vue'
import { saveWork } from '../src/modules/studio/api/works'
import { generatePhotoLyrics, getLyricsRequest } from '../src/modules/works/api/detail'

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

afterEach(() => {
  sessionStorage.clear()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

async function setup() {
  vi.stubGlobal('URL', Object.assign(class extends URL {}, {
    createObjectURL: () => 'blob:photo-test',
    revokeObjectURL: () => {},
  }))
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/create', component: { template: '<div />' } },
    { path: '/create/photo', component: PhotoStudioPage },
    { path: '/works/:id', component: { template: '<div />' } },
  ] })
  await router.push('/create/photo')
  const page = mount(PhotoStudioPage, { global: { plugins: [router] } })
  await flushPromises()
  return { page, router }
}

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
    const file = new File(['photo'], 'beach.png', { type: 'image/png' })
    const input = page.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    expect(page.get('img').attributes('src')).toBe('blob:photo-test')
    await page.get('textarea').setValue('这是妈妈第一次看到海。')
    await page.get('form').trigger('submit')
    await flushPromises()
    const id = router.currentRoute.value.params.id
    expect(saveWork).toHaveBeenCalledWith(expect.objectContaining({ story: '这是妈妈第一次看到海。' }), undefined, id)
    expect(generatePhotoLyrics).toHaveBeenCalledExactlyOnceWith(id, 0, expect.any(String), 'zh-CN', file)
    expect(router.currentRoute.value.query.revision).toBe('lyric-revision')
    expect(getLyricsRequest).not.toHaveBeenCalled()
  } finally { page.unmount() }
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
