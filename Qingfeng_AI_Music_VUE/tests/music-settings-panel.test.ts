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

const api = vi.hoisted(() => ({
  snapshot: vi.fn(), load: vi.fn(), preview: vi.fn(), rewrite: vi.fn(), rhyme: vi.fn(), rewriteSelection: vi.fn(),
  compose: vi.fn(), requestState: vi.fn(), revisionById: vi.fn(), system: vi.fn(), replaceRoute: vi.fn(),
}))
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
  rhymeLyricLine: api.rhyme,
  rewriteLyricSelection: api.rewriteSelection,
  listRevisions: async () => [revision],
  generateLyrics: api.compose,
  getLyricsRequest: api.requestState,
  getRevision: api.revisionById,
}))
vi.mock('../src/shared/http/system', () => ({ getSystemInfo: api.system }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: api.replaceRoute }) }))

const work = { id: 'work', version: 2, style: '摇滚', vocalGender: 'MALE' } as Work
const task = { id: 'original', style: '温暖民谣', vocalGender: 'FEMALE' } as GenerationTask
const revision = { id: 'base-revision', workVersion: work.version, title: '回家的灯', lyrics: '[Verse]\n灯在等你\n夜风吹过山城', language: 'zh-CN' } as Revision
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
  localStorage.clear()
  api.system.mockResolvedValue({ capabilities: { lyricsGeneration: true } })
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

it('compares the Mandarin rhyme endings and applies the chosen candidate in the remake draft', async () => {
  api.snapshot.mockResolvedValue({ preferences })
  api.rhyme.mockResolvedValueOnce({ status: 'CHOOSE_REFERENCE', candidates: [] }).mockResolvedValueOnce({
    status: 'SUGGESTIONS', referenceIndex: 1, reference: { word: '城', sound: 'eng' }, original: { word: '你', sound: 'i' },
    candidates: [{ text: '等你到三更', ending: { word: '更', sound: 'eng' }, match: 'EXACT' }],
  })
  const panel = mountAdjustment()
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('.music-settings-lyrics textarea')
  editor.element.setSelectionRange(revision.lyrics.indexOf('灯在等你'), revision.lyrics.indexOf('灯在等你'))
  await editor.trigger('click')
  await panel.findAll('button').find(button => button.text() === '更押韵')!.trigger('click')
  await flushPromises()
  const rhyme = panel.get('.lyric-rhyme-editor')
  expect(rhyme.text()).toContain('请选择想和这句押韵的参考句。')
  await rhyme.get('select').setValue('1')
  await rhyme.get('.rhyme-generate').trigger('click')
  await flushPromises()
  expect(api.rhyme).toHaveBeenLastCalledWith(work.id, expect.objectContaining({
    line: '灯在等你', lines: ['灯在等你', '夜风吹过山城'], lineIndex: 0, referenceIndex: 1, language: 'zh-CN', rewriteIfAlreadyRhymes: true,
  }))
  expect(rhyme.findAll('mark').map(mark => mark.text())).toEqual(['城 · eng', '你 · i', '更 · eng'])
  expect(editor.element.value).toBe(revision.lyrics)
  await rhyme.findAll('button').find(button => button.text() === '采用这句')!.trigger('click')
  expect(editor.element.value).toBe('[Verse]\n等你到三更\n夜风吹过山城')
  expect(panel.find('.lyric-rhyme-editor').exists()).toBe(false)
  expect(api.rewrite).not.toHaveBeenCalled()
})

it('offers a rewrite when the automatic check finds that the original line already rhymes', async () => {
  api.snapshot.mockResolvedValue({ preferences })
  const ends = { referenceIndex: 1, reference: { word: '城', sound: 'chéng' }, original: { word: '灯', sound: 'dēng' } }
  api.rhyme.mockResolvedValueOnce({ ...ends, status: 'ALREADY_RHYMES', candidates: [] }).mockResolvedValueOnce({
    ...ends, status: 'SUGGESTIONS',
    candidates: [{ text: '替你守到三更', ending: { word: '更', sound: 'gēng' }, match: 'EXACT' }],
  })
  const panel = mountAdjustment()
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('.music-settings-lyrics textarea')
  const original = '[Verse]\n还为你留盏灯\n夜风吹过山城'
  await editor.setValue(original)
  editor.element.setSelectionRange(original.indexOf('还为你'), original.indexOf('还为你'))
  await editor.trigger('click')
  await panel.findAll('button').find(button => button.text() === '更押韵')!.trigger('click')
  await flushPromises()
  expect(api.rhyme).toHaveBeenNthCalledWith(1, work.id, expect.objectContaining({ rewriteIfAlreadyRhymes: false }))
  const rhyme = panel.get('.lyric-rhyme-editor')
  const generate = rhyme.get('.rhyme-generate')
  expect(generate.text()).toBe('保留押韵，换一句')
  await generate.trigger('click')
  await flushPromises()
  expect(api.rhyme).toHaveBeenNthCalledWith(2, work.id, expect.objectContaining({
    line: '还为你留盏灯', lineIndex: 0, rewriteIfAlreadyRhymes: true,
  }))
  expect(editor.element.value).toBe(original)
  await rhyme.findAll('button').find(button => button.text() === '采用这句')!.trigger('click')
  expect(editor.element.value).toBe('[Verse]\n替你守到三更\n夜风吹过山城')
})

it('uses the current English stanza, preserving inline section headers and unrelated lyrics', async () => {
  const panel = mount(LyricsPanel, { props: { work } })
  await flushPromises()
  const editor = panel.get<HTMLTextAreaElement>('textarea')
  const original = '[Verse]\n[Pre-Chorus] I stayed awake till dawn\nI watched the fading light\n\n[Chorus]\nWe wait until it rains'
  await editor.setValue(original)
  const start = original.indexOf('I stayed')
  editor.element.setSelectionRange(start, start)
  await editor.trigger('click')
  api.rhyme.mockResolvedValueOnce({
    status: 'SUGGESTIONS', referenceIndex: 1, reference: { word: 'light', sound: 'aɪt' }, original: { word: 'dawn', sound: 'ɔn' },
    candidates: [{ text: 'I stayed awake all night', ending: { word: 'night', sound: 'aɪt' }, match: 'EXACT' }],
  })
  await panel.findAll('button').find(button => button.text() === '更押韵')!.trigger('click')
  await flushPromises()
  const rhyme = panel.get('.lyric-rhyme-editor')
  expect(api.rhyme).toHaveBeenCalledExactlyOnceWith(work.id, expect.objectContaining({
    line: 'I stayed awake till dawn', lines: ['I stayed awake till dawn', 'I watched the fading light'], lineIndex: 0, language: 'en',
  }))
  expect(rhyme.findAll('mark').map(mark => mark.text())).toEqual(['light · aɪt', 'dawn · ɔn', 'night · aɪt'])
  await rhyme.findAll('button').find(button => button.text() === '采用这句')!.trigger('click')
  expect(editor.element.value).toBe(original.replace('I stayed awake till dawn', 'I stayed awake all night'))
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

it.each([
  new ApiError('等待预览超时', 408),
  new ApiError('模型响应超时，结果待核对', 409, 'REWRITE_PROVIDER_RESULT_UNKNOWN'),
])('reopens and checks a lost preview using the original request ID', async failure => {
  api.rewriteSelection.mockRejectedValueOnce(failure)
    .mockResolvedValueOnce({ lyrics: revision.lyrics.replace('夜风吹过山城', '风里还有灯火') })
  const panel = mount(LyricsPanel, { props: { work: { ...work, id: 'retry-work' } } })
  await flushPromises()
  await panel.get('.lyric-rewrite-all').trigger('click')
  const dialog = panel.get('dialog.lyric-selection-dialog')
  await dialog.get('.selection-row input').setValue(true)
  const generate = () => dialog.findAll('button').find(button => button.text() === '生成预览')!
  await generate().trigger('click')
  await flushPromises()
  expect(dialog.get('[role="alert"]').text()).toBe(failure.message)
  await dialog.get('.selection-close').trigger('click')
  await panel.get('.lyric-rewrite-all').trigger('click')
  const reopened = panel.get('dialog.lyric-selection-dialog')
  expect(reopened.get<HTMLInputElement>('.selection-row input').element.checked).toBe(true)
  await reopened.findAll('button').find(button => button.text() === '核对预览结果')!.trigger('click')
  await flushPromises()
  expect(api.rewriteSelection).toHaveBeenCalledTimes(2)
  expect(api.rewriteSelection.mock.calls[1]).toEqual(api.rewriteSelection.mock.calls[0])
  expect(reopened.text()).toContain('新版歌词预览')
})

it('sends the live draft for a full rewrite and keeps it when a response only changes punctuation', async () => {
  vi.stubGlobal('confirm', vi.fn(() => true))
  const panel = mount(LyricsPanel, { props: { work } })
  try {
    await flushPromises()
    const editor = panel.get<HTMLTextAreaElement>('textarea')
    const source = '[Verse]\n我在门前等你\n今天风吹得很轻'
    await editor.setValue(source)
    api.compose.mockResolvedValueOnce({ ...revision, id: 'duplicate', lyrics: '[Verse 2]\n我在门前等你！\n今天风吹得很轻。' })
    await panel.get('.lyric-rewrite-all').trigger('click')
    await panel.get('.lyric-selection-dialog').findAll('button').find(button => button.text() === '全部重写')!.trigger('click')
    await flushPromises()
    expect(api.compose).toHaveBeenCalledWith(work.id, work.version, expect.any(String), 'zh-CN', '', revision.id, expect.any(Object), source)
    expect(editor.element.value).toBe(source)
    expect(panel.get('.notice.error').text()).toContain('这次重写没有生成新的歌词，请重试。')
    expect(api.replaceRoute).not.toHaveBeenCalled()

    const changed = { ...revision, id: 'rewritten', lyrics: '[Verse]\n门前的灯照着你的路\n晚风捎回你的脚步' }
    api.compose.mockResolvedValueOnce(changed)
    await panel.get('.notice.error button').trigger('click')
    await flushPromises()
    expect(api.compose.mock.calls[0]?.[2]).not.toBe(api.compose.mock.calls[1]?.[2])
    expect(api.compose.mock.calls[1]?.[7]).toBe(source)
    expect(editor.element.value).toBe(changed.lyrics)
    expect(api.replaceRoute).toHaveBeenCalledWith(expect.objectContaining({ query: expect.objectContaining({ revision: changed.id }) }))
  } finally { vi.unstubAllGlobals() }
})

it('recovers a full rewrite after timeout without resubmitting or discarding the submitted draft', async () => {
  vi.stubGlobal('confirm', vi.fn(() => true))
  const panel = mount(LyricsPanel, { props: { work } })
  try {
    await flushPromises()
    const editor = panel.get<HTMLTextAreaElement>('textarea')
    const source = revision.lyrics.replace('灯在等你', '灯还在窗边')
    await editor.setValue(source)
    const changed = { ...revision, id: 'recovered', lyrics: '[Verse]\n窗台亮了一夜\n风把你的消息送到' }
    api.compose.mockRejectedValueOnce(new ApiError('等待重写超时', 408))
    api.requestState.mockResolvedValueOnce({ status: 'UNKNOWN' })
      .mockResolvedValueOnce({ status: 'SUCCEEDED', revisionId: changed.id })
    api.revisionById.mockResolvedValueOnce(changed)
    await panel.get('.lyric-rewrite-all').trigger('click')
    await panel.get('.lyric-selection-dialog').findAll('button').find(button => button.text() === '全部重写')!.trigger('click')
    await flushPromises()
    expect(editor.element.value).toBe(source)
    await panel.get('.notice.error button').trigger('click')
    await flushPromises()
    expect(api.compose).toHaveBeenCalledTimes(1)
    expect(api.requestState.mock.calls[0]).toEqual([work.id, api.compose.mock.calls[0]?.[2]])
    expect(api.requestState.mock.calls[1]).toEqual(api.requestState.mock.calls[0])
    expect(editor.element.value).toBe(changed.lyrics)
  } finally { vi.unstubAllGlobals() }
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

it.each([false, true])('checks the timed-out rewrite before retry (late revision: %s)', async late => {
  vi.stubGlobal('confirm', vi.fn(() => true))
  const panel = mount(LyricsPanel, { props: { work } })
  try {
    await flushPromises()
    const editor = panel.get<HTMLTextAreaElement>('textarea')
    const source = '[Verse]\n门外的风依旧温柔'
    await editor.setValue(source)
    const changed = { ...revision, id: 'retry-result', lyrics: '[Verse]\n窗前仍有晚风相伴' }
    const unknown = { status: 'UNKNOWN', revisionId: null, errorCode: 'LYRICS_PROVIDER_TIMEOUT' }
    api.compose.mockRejectedValueOnce(new ApiError('timeout', 409, 'LYRICS_PROVIDER_TIMEOUT')).mockResolvedValueOnce(changed)
    api.requestState.mockResolvedValueOnce(unknown).mockResolvedValueOnce(late
      ? { status: 'SUCCEEDED', revisionId: changed.id, errorCode: null } : unknown)
    api.revisionById.mockResolvedValueOnce(changed)
    await panel.get('.lyric-rewrite-all').trigger('click')
    await panel.get('.lyric-selection-dialog').findAll('button').find(button => button.text() === '全部重写')!.trigger('click')
    await flushPromises()
    expect(panel.get('.notice.error').text()).toContain('本次歌词生成超时，可以重试。')
    expect(editor.element.value).toBe(source)
    const key = api.compose.mock.calls[0]?.[2]
    expect(api.compose).toHaveBeenCalledTimes(1)
    await panel.get('.notice.error button').trigger('click')
    await flushPromises()
    expect(api.requestState.mock.calls).toEqual([[work.id, key], [work.id, key]])
    expect(api.compose).toHaveBeenCalledTimes(late ? 1 : 2)
    if (!late) {
      expect(api.compose.mock.calls[1]?.[2]).not.toBe(key)
      expect(api.compose.mock.calls[1]?.[7]).toBe(source)
      api.revisionById.mockReset()
    }
    expect(editor.element.value).toBe(changed.lyrics)
  } finally { vi.unstubAllGlobals() }
})
