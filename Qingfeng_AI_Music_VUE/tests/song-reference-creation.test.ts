// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import DraftStudioPage from '../src/modules/studio/pages/DraftStudioPage.vue'
import { loadSongReference, songCreationRoute, type SongReference } from '../src/modules/studio/model/song-reference'
import { saveWork } from '../src/modules/studio/api/works'
import { generateLyrics, getLyricsRequest, getUsage, submitTask } from '../src/modules/works/api/detail'
import { loadMusicCatalog, saveMusicSettings } from '../src/modules/works/api/music-settings'

vi.mock('../src/shared/http/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('../src/shared/http/api-client')>(),
  ensureCreator: async () => {},
  currentCreatorId: () => 'hot-song-reference-test',
}))
vi.mock('../src/shared/http/system', () => ({
  getSystemInfo: async () => ({ capabilities: { workPersistence: true, lyricsGeneration: true, quickCreation: true } }),
  usesLocalPhotoCapability: false,
}))
vi.mock('../src/shared/credits/useCredits', async () => {
  const { ref } = await import('vue')
  return { useCredits: () => ({ credits: ref(), refreshCredits: async () => {} }) }
})
vi.mock('../src/modules/studio/model/song-reference', async importOriginal => ({
  ...await importOriginal<typeof import('../src/modules/studio/model/song-reference')>(),
  loadSongReference: vi.fn(),
}))
vi.mock('../src/modules/studio/api/works', () => ({ saveWork: vi.fn() }))
vi.mock('../src/modules/works/api/music-settings', () => ({ loadMusicCatalog: vi.fn(), saveMusicSettings: vi.fn() }))
vi.mock('../src/modules/works/api/detail', () => ({
  generateLyrics: vi.fn(), getLyricsRequest: vi.fn(), getUsage: vi.fn(), submitTask: vi.fn(),
}))

const genres = [
  { id: 'pop', zh: '流行', en: 'Pop', prompt: 'pop' },
  { id: 'chinese', zh: '中国风', en: 'Chinese style', prompt: 'guofeng' },
  { id: 'electronic', zh: '电子', en: 'Electronic', prompt: 'electronic' },
]
const saved = { id: 'new-work', version: 0, createdAt: '', updatedAt: '' }

beforeEach(() => {
  Object.defineProperty(HTMLDialogElement.prototype, 'close', {
    configurable: true,
    value() { this.removeAttribute('open') },
  })
  vi.mocked(loadMusicCatalog).mockResolvedValue({
    version: 1, recipes: [], groups: [{ id: 'genre', zh: '曲风', en: 'Genre', max: 2, options: genres }],
  })
  const allowance = { active: 0, dailyRemaining: 10, dailyLimit: 10, concurrentLimit: 2, resetsAt: '2026-11-24T00:00:00+08:00' }
  vi.mocked(getUsage).mockResolvedValue({ lyrics: allowance, music: allowance })
  vi.mocked(getLyricsRequest).mockResolvedValue({ status: 'NOT_FOUND', revisionId: null, errorCode: null })
  vi.mocked(saveWork).mockImplementation(async draft => ({ ...draft, ...saved }))
  vi.mocked(generateLyrics).mockResolvedValue({
    id: 'new-lyrics', workId: saved.id, workVersion: 1, title: '新歌词', lyrics: '我的新故事',
    source: 'OPENAI', confirmed: false, createdAt: '',
  })
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.resetAllMocks()
})

async function open(id: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/create', component: DraftStudioPage },
    { path: '/works/:id', component: { template: '<div />' } },
  ] })
  await router.push(songCreationRoute({ id }))
  const mountPage = () => mount(RouterView, { global: { plugins: [router] } })
  const page = mountPage()
  await flushPromises()
  return { router, page, mountPage }
}

it.each([
  ['luo-yu-de-cao-chang', '落雨的操场', 'zh-CN', 'pop', ['Verse 1', 'Verse 2', 'Chorus', 'Verse', 'Chorus']],
  ['red-guofeng', '红', 'zh-CN', 'chinese', ['Verse', 'Pre-Chorus', 'Chorus', 'Interlude', 'Pre-Chorus', 'Chorus', 'Chorus']],
  ['body-language', 'Body Language', 'en', 'electronic', ['Verse', 'Pre-Chorus', 'Chorus']],
] as const)('carries %s to an editable draft without generating on entry', async (id, title, language, genre, sections) => {
  const reference: SongReference = {
    title, language, structure: { templateId: 'CUSTOM', chorusFirst: false, customSections: [...sections] },
    preferences: { version: 1, recipeId: null, choices: { genre: [genre] }, vocalGender: 'AUTO', freeText: `Direction for ${genre}`, negativeText: '' },
  }
  vi.mocked(loadSongReference).mockResolvedValue(reference)
  vi.mocked(saveMusicSettings).mockImplementation(async () => ({
    work: { ...vi.mocked(saveWork).mock.lastCall![0], ...saved, version: 1 },
    direction: { preferences: reference.preferences, style: reference.preferences.freeText, negativeTags: '', summaryZh: '', summaryEn: '', catalogVersion: 1 },
  }))

  const { page, mountPage, router } = await open(id)
  expect(loadSongReference).toHaveBeenCalledExactlyOnceWith(id)
  expect(router.currentRoute.value.query).toMatchObject({ mode: 'custom', reference: id, draft: expect.any(String) })
  expect(page.get('.studio-song-reference').text()).toContain(title)
  expect(page.get('#inspiration-input').element).toHaveProperty('value', '')
  expect(page.get('[name=lyric-language]').element).toHaveProperty('value', language)
  expect(page.get('.creation-chips [aria-pressed=true]').text()).toContain(genres.find(option => option.id === genre)!.zh)
  expect(page.get('.creation-mode-switch button').attributes()).toHaveProperty('disabled')
  expect(saveWork).not.toHaveBeenCalled()
  expect(generateLyrics).not.toHaveBeenCalled()

  const story = 'A new story about opening a bookshop with an old friend'
  await page.get('#inspiration-input').setValue(story)
  page.unmount()
  const restored = mountPage()
  try {
    await flushPromises()
    expect(restored.get('#inspiration-input').element).toHaveProperty('value', story)
    expect(restored.get('.studio-song-reference').text()).toContain(title)
    expect(loadSongReference).toHaveBeenCalledTimes(1)
    await restored.get('form').trigger('submit')
    await flushPromises()
    expect(saveWork).toHaveBeenCalledTimes(1)
    expect(saveWork).toHaveBeenCalledWith(expect.objectContaining({ story, title: '', message: '' }), undefined, expect.any(String), id)
    expect(saveMusicSettings).toHaveBeenCalledExactlyOnceWith(saved.id, 0, reference.preferences)
    expect(generateLyrics).toHaveBeenCalledExactlyOnceWith(saved.id, 1, expect.any(String), language, '', undefined, reference.structure)
    expect(router.currentRoute.value.query.revision).toBe('new-lyrics')
    expect(submitTask).not.toHaveBeenCalled()
  } finally { restored.unmount() }
})

it('lets the user remove a failed reference and continue with a manual story', async () => {
  vi.mocked(loadSongReference).mockRejectedValue(new Error('unavailable'))
  const { page, router } = await open('red-guofeng')
  try {
    expect(page.get('.studio-song-reference').text()).toContain('参考歌曲暂时无法读取')
    expect(page.get('button[type=submit]').attributes()).toHaveProperty('disabled')
    const story = '等在旧城里的朋友'
    await page.get('#inspiration-input').setValue(story)
    const remove = page.findAll('.studio-song-reference button').find(button => button.text() === '移除参考')!
    await remove.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.reference).toBeUndefined()
    expect(page.find('.studio-song-reference').exists()).toBe(false)
    expect(page.get('#inspiration-input').element).toHaveProperty('value', story)
    expect(saveWork).not.toHaveBeenCalled()
  } finally { page.unmount() }
})
