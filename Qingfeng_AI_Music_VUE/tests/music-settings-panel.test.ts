// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../src/shared/http/api-client'
import type { Work } from '../src/modules/studio'
import type { GenerationTask, Revision } from '../src/modules/works/api/detail'
import type { MusicCatalog, MusicPreferences } from '../src/modules/works/api/music-settings'
import LyricsPanel from '../src/modules/works/components/LyricsPanel.vue'
import MusicSettingsPanel from '../src/modules/works/components/MusicSettingsPanel.vue'

const api = vi.hoisted(() => ({ snapshot: vi.fn(), load: vi.fn(), preview: vi.fn(), rewrite: vi.fn(), rewriteSelection: vi.fn() }))
const catalog = JSON.parse(readFileSync('../Qingfeng_AI_Music/qf-infrastructure/src/main/resources/music/catalog-v1.json', 'utf8')) as MusicCatalog
vi.mock('../src/modules/works/api/music-settings', () => ({
  loadMusicCatalog: async () => catalog,
  loadMusicSettings: api.load,
  loadTaskMusicSettings: api.snapshot,
  previewWorkMusicSettings: api.preview,
  musicBriefAvailable: async () => false,
  suggestMusicBrief: vi.fn(),
}))
vi.mock('../src/shared/credits/useCredits', async () => {
  const { ref } = await import('vue')
  return { useCredits: () => ({ credits: ref(null), refreshCredits: async () => {} }) }
})
vi.mock('../src/modules/works/api/detail', () => ({
  rewriteLyricLine: api.rewrite,
  rewriteLyricSelection: api.rewriteSelection,
  listRevisions: async () => [revision],
}))
vi.mock('../src/shared/http/system', () => ({ getSystemInfo: async () => ({ capabilities: { lyricsGeneration: true } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({}) }))

const work = { id: 'work', version: 2, style: '摇滚', vocalGender: 'MALE' } as Work
const task = { id: 'original', style: '温暖民谣', vocalGender: 'FEMALE' } as GenerationTask
const revision = { title: '回家的灯', lyrics: '[Verse]\n灯在等你\n夜风吹过山城', language: 'zh-CN' } as Revision
const preferences: MusicPreferences = {
  version: 1,
  recipeId: catalog.recipes[0]!.id,
  choices: { genre: ['folk'], instruments: ['guitar', 'piano'], tempo: ['slow'], groove: ['steady'], exclude: ['no_rap'] },
  vocalGender: 'FEMALE',
  freeText: '人声靠前',
  negativeText: '尖锐的高音',
}

function mountAdjustment() {
  return mount(MusicSettingsPanel, { props: { work, busy: false, remake: { task, revision } } })
}

enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.clearAllMocks()
  api.load.mockResolvedValue({ work, direction: { preferences: { ...preferences, vocalGender: 'MALE', choices: { genre: ['rock'] } } } })
  api.preview.mockImplementation(async (_work: string, selected: MusicPreferences) => ({ preferences: selected }))
})

it('displays the source lyrics and music preferences, retaining edits across mobile tabs', async () => {
  api.snapshot.mockResolvedValue({ preferences })
  const panel = mountAdjustment()
  await flushPromises()

  expect(api.snapshot).toHaveBeenCalledExactlyOnceWith(work.id, task.id)
  expect(panel.get('.music-settings-controls').element.firstElementChild?.textContent).toBe('音乐设置')
  expect(panel.find('.music-recipes').exists()).toBe(false)
  expect(panel.find('.music-refine-toggle').exists()).toBe(false)
  const controls = panel.get('.music-settings-controls')
  const lyrics = panel.get<HTMLTextAreaElement>('.music-settings-lyrics textarea')
  expect(lyrics.element.value).toBe(revision.lyrics)
  expect(controls.get<HTMLSelectElement>('select').element.value).toBe('FEMALE')
  expect(controls.findAll('button[aria-pressed="true"]').map(button => button.text())).toEqual([
    '民谣主', '舒缓', '木吉他主', '钢琴', '平稳', '不要说唱',
  ])
  const more = controls.get('details.music-settings-more')
  expect(more.attributes('open')).toBeUndefined()
  expect(more.get('summary').text()).toContain('木吉他')
  expect(more.get<HTMLTextAreaElement>('textarea').element.value).toBe(preferences.freeText)
  expect(more.get<HTMLInputElement>('input').element.value).toBe(preferences.negativeText)

  await lyrics.setValue('灯依然在等你')
  await panel.get('button[aria-controls="music-options-pane"]').trigger('click')
  expect(panel.get('.music-settings-layout').attributes('data-pane')).toBe('music')
  more.element.setAttribute('open', '')
  await controls.findAll('button').find(button => button.text() === '钢琴')!.trigger('click')
  await panel.get('button[aria-controls="music-lyrics-pane"]').trigger('click')
  expect(panel.get('.music-settings-layout').attributes('data-pane')).toBe('lyrics')
  expect(lyrics.element.value).toBe('灯依然在等你')
  const submit = panel.get<HTMLButtonElement>('.music-settings-footer .button:not(.secondary)')
  await vi.waitFor(() => expect(submit.element.disabled).toBe(false))
  await submit.trigger('click')
  expect(panel.emitted('apply')).toEqual([[{
    expectedVersion: work.version,
    preferences: { ...preferences, recipeId: null, choices: { ...preferences.choices, instruments: ['guitar'] } },
    lyrics: '灯依然在等你',
  }]])
})

it('uses the audio task’s recorded genre and voice when the legacy snapshot has no structured choices', async () => {
  api.snapshot.mockResolvedValue({ preferences: { ...preferences, recipeId: null, choices: {}, freeText: '', negativeText: '' } })
  const panel = mountAdjustment()
  await flushPromises()

  const selected = panel.findAll('.music-settings-controls button[aria-pressed="true"]').map(button => button.text())
  expect(selected).toContain('民谣主')
  expect(panel.get<HTMLSelectElement>('.music-settings-controls select').element.value).toBe('FEMALE')
  expect(selected).not.toContain('摇滚主')
  expect(panel.text()).toContain('原版设置记录不完整')
})

it.each([
  ['改写选句', 'REWRITE', '灯火还在等你'],
  ['更押韵', 'RHYME', '灯火等你回城'],
  ['更口语化', 'COLLOQUIAL', '灯还等着你呢'],
  ['缩短一句', 'SHORTEN', '灯等你'],
])('previews %s and replaces only the selected line after confirmation', async (label, action, replacement) => {
  api.snapshot.mockResolvedValue({ preferences })
  api.rewrite.mockResolvedValue({ text: replacement })
  const panel = mountAdjustment()
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('.music-settings-lyrics textarea')
  const toolbar = panel.get('.music-settings-lyrics > .remake-lyrics-actions')
  const buttons = toolbar.findAll<HTMLButtonElement>('button')
  expect(editor.element.nextElementSibling).toBe(toolbar.element)
  expect(buttons.map(button => button.text())).toEqual(['改写选句', '更押韵', '更口语化', '缩短一句'])
  expect(buttons.every(button => button.element.disabled)).toBe(true)
  const caret = revision.lyrics.indexOf('灯在等你') + 1
  editor.element.setSelectionRange(caret, caret)
  await editor.trigger('click')
  expect(panel.get('.remake-lyrics-selected').text()).toBe('已选歌词灯在等你')
  expect(buttons.every(button => !button.element.disabled)).toBe(true)
  await buttons.find(button => button.text() === label)!.trigger('click')
  await flushPromises()
  expect(api.rewrite).toHaveBeenCalledExactlyOnceWith(work.id, expect.objectContaining({
    action, line: '灯在等你', language: 'zh-CN', context: expect.stringContaining('夜风吹过山城'),
  }))
  expect(panel.get('.remake-lyrics-preview').text()).toContain(replacement)
  expect(editor.element.value).toBe(revision.lyrics)
  await panel.findAll('button').find(button => button.text() === '采用这句')!.trigger('click')
  expect(editor.element.value).toBe(`[Verse]\n${replacement}\n夜风吹过山城`)
  expect(panel.find('.remake-lyrics-preview').exists()).toBe(false)
})

it('keeps the creation tools below the textarea and targets the line at the caret', async () => {
  api.rewrite.mockResolvedValue({ text: '我还在等你' })
  const panel = mount(LyricsPanel, { props: { work } })
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('textarea')
  const toolbar = panel.get('.lyric-line-tools')
  const buttons = toolbar.findAll<HTMLButtonElement>('button')
  expect(editor.element.nextElementSibling).toBe(toolbar.element)
  expect(buttons.map(button => button.text())).toEqual(['改写选句', '更押韵', '更口语化', '缩短一句'])
  expect(buttons.every(button => button.element.disabled)).toBe(true)

  const start = revision.lyrics.indexOf('灯在等你')
  editor.element.setSelectionRange(start, start)
  await editor.trigger('click')
  expect(panel.get('.lyric-selected-line').text()).toBe('已选歌词灯在等你')
  expect(buttons.every(button => !button.element.disabled)).toBe(true)
  const nextLine = revision.lyrics.indexOf('夜风')
  editor.element.setSelectionRange(nextLine, nextLine)
  await editor.trigger('keyup', { key: 'ArrowDown' })
  expect(panel.get('.lyric-selected-line').text()).toBe('已选歌词夜风吹过山城')
  await buttons[2]!.trigger('click')
  await flushPromises()
  expect(api.rewrite).toHaveBeenCalledExactlyOnceWith(work.id, expect.objectContaining({ action: 'COLLOQUIAL', line: '夜风吹过山城' }))
  expect(panel.get('.lyric-line-preview').text()).toContain('我还在等你')
  expect(editor.element.value).toBe(revision.lyrics)
  await panel.get('.lyric-line-preview .button').trigger('click')
  await flushPromises()
  expect(editor.element.value).toBe('[Verse]\n灯在等你\n我还在等你')
})

it('tracks lyrics as they are typed and clears the selected line on a blank line', async () => {
  const panel = mount(LyricsPanel, { props: { work } })
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('textarea')
  const buttons = panel.get('.lyric-line-tools').findAll<HTMLButtonElement>('button')
  await editor.setValue('新写的一句')
  expect(panel.get('.lyric-selected-line').text()).toBe('已选歌词新写的一句')
  expect(buttons.every(button => !button.element.disabled)).toBe(true)
  await editor.setValue('新写的一句\n')
  expect(panel.find('.lyric-selected-line').exists()).toBe(false)
  expect(buttons.every(button => button.element.disabled)).toBe(true)
})

it('keeps individually selected lines and entire sections, applying a preview only on confirmation', async () => {
  const source = '[Verse]\n晚风吹过山城\n楼下亮了灯\n\n[Chorus]\n灯还在等你\n等你走进这座城'
  const rewritten = source.replace('等你走进这座城', '等你循着灯光回来')
  api.rewriteSelection.mockResolvedValue({ lyrics: rewritten })
  const panel = mount(LyricsPanel, { props: { work } })
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('textarea')
  await editor.setValue(source)
  await panel.get('.lyric-rewrite-all').trigger('click')
  const dialog = panel.get('dialog.lyric-selection-dialog')
  const generate = () => dialog.findAll<HTMLButtonElement>('button').find(button => button.text() === '生成预览')!
  expect(generate().element.disabled).toBe(true)
  await dialog.findAll('.selection-heading input')[0]!.setValue(true)
  await dialog.findAll('.selection-row input')[2]!.setValue(true)
  expect(dialog.text()).toContain('保留 3 句 · 重写 1 句')
  await generate().trigger('click')
  await flushPromises()
  expect(api.rewriteSelection).toHaveBeenCalledExactlyOnceWith(work.id, expect.objectContaining({
    lyrics: source, keptLines: [1, 2, 5], workVersion: work.version,
  }))
  expect(dialog.findAll('.selection-result > span').map(line => line.text())).toEqual(['晚风吹过山城', '楼下亮了灯', '灯还在等你', '等你循着灯光回来'])
  expect(dialog.get('details').text()).toContain('等你走进这座城')
  expect(editor.element.value).toBe(source)
  await dialog.findAll('button').find(button => button.text() === '返回选择')!.trigger('click')
  expect(dialog.text()).toContain('保留 3 句 · 重写 1 句')
  await generate().trigger('click')
  expect(api.rewriteSelection).toHaveBeenCalledTimes(1)
  await dialog.findAll('button').find(button => button.text() === '采用新版')!.trigger('click')
  expect(editor.element.value).toBe(rewritten)
  expect(panel.find('dialog.lyric-selection-dialog').exists()).toBe(false)
})

it('retries a lost preview response with the original request ID', async () => {
  api.rewriteSelection.mockRejectedValueOnce(new ApiError('等待预览超时', 408))
    .mockResolvedValueOnce({ lyrics: revision.lyrics.replace('夜风吹过山城', '风里还有灯火') })
  const panel = mount(LyricsPanel, { props: { work: { ...work, id: 'retry-work' } } })
  await flushPromises()
  await panel.get('.lyric-rewrite-all').trigger('click')
  const dialog = panel.get('dialog.lyric-selection-dialog')
  await dialog.get('.selection-row input').setValue(true)
  const generate = () => dialog.findAll('button').find(button => button.text() === '生成预览')!
  await generate().trigger('click')
  await flushPromises()
  expect(dialog.get('[role="alert"]').text()).toBe('等待预览超时')
  await generate().trigger('click')
  await flushPromises()
  expect(api.rewriteSelection).toHaveBeenCalledTimes(2)
  expect(api.rewriteSelection.mock.calls[1]).toEqual(api.rewriteSelection.mock.calls[0])
  expect(dialog.text()).toContain('新版歌词预览')
})

it('ignores a late suggestion when the lyric draft changes during rewriting', async () => {
  api.snapshot.mockResolvedValue({ preferences })
  let finish!: (result: { text: string }) => void
  api.rewrite.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const panel = mountAdjustment()
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('.music-settings-lyrics textarea')
  const caret = revision.lyrics.indexOf('灯在等你')
  editor.element.setSelectionRange(caret, caret)
  await editor.trigger('click')
  await panel.findAll('button').find(button => button.text() === '改写选句')!.trigger('click')
  await editor.setValue('我已经手动改了歌词')
  finish({ text: '迟到的改写' })
  await flushPromises()
  expect(editor.element.value).toBe('我已经手动改了歌词')
  expect(panel.find('.remake-lyrics-preview').exists()).toBe(false)
})
