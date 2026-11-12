// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { Work } from '../src/modules/studio'
import type { GenerationTask, Revision } from '../src/modules/works/api/detail'
import type { MusicCatalog, MusicPreferences } from '../src/modules/works/api/music-settings'
import MusicSettingsPanel from '../src/modules/works/components/MusicSettingsPanel.vue'

const api = vi.hoisted(() => ({ snapshot: vi.fn(), load: vi.fn(), preview: vi.fn() }))
const catalog = JSON.parse(readFileSync('../Qingfeng_AI_Music/qf-infrastructure/src/main/resources/music/catalog-v1.json', 'utf8')) as MusicCatalog
vi.mock('../src/modules/works/api/music-settings', () => ({
  loadMusicCatalog: async () => catalog,
  loadMusicSettings: api.load,
  loadTaskMusicSettings: api.snapshot,
  previewWorkMusicSettings: api.preview,
  musicBriefAvailable: async () => false,
  suggestMusicBrief: vi.fn(),
}))
vi.mock('../src/shared/credits/useCredits', () => ({
  useCredits: () => ({ credits: undefined, refreshCredits: async () => {} }),
}))

const work = { id: 'work', version: 2, style: '摇滚', vocalGender: 'MALE' } as Work
const task = { id: 'original', style: '温暖民谣', vocalGender: 'FEMALE' } as GenerationTask
const revision = { title: '回家的灯', lyrics: '灯在等你' } as Revision
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

it('opens directly at detailed choices and selects the settings from the audio being adjusted', async () => {
  api.snapshot.mockResolvedValue({ preferences })
  const panel = mountAdjustment()
  await flushPromises()

  expect(api.snapshot).toHaveBeenCalledExactlyOnceWith(work.id, task.id)
  expect(panel.get('.music-settings-controls').element.firstElementChild?.textContent).toBe('精细调整')
  expect(panel.find('.music-recipes').exists()).toBe(false)
  expect(panel.find('.music-refine-toggle').exists()).toBe(false)
  const controls = panel.get('.music-settings-controls')
  expect(controls.findAll('button[aria-pressed="true"]').map(button => button.text())).toEqual([
    '女声', '民谣主', '木吉他主', '钢琴', '舒缓', '平稳', '不要说唱',
  ])
  expect(controls.get('details.music-advanced').attributes('open')).toBeDefined()
  expect(panel.get<HTMLTextAreaElement>('.music-free-text textarea').element.value).toBe(preferences.freeText)
  expect(panel.get<HTMLInputElement>('.music-exclude input').element.value).toBe(preferences.negativeText)

  await controls.findAll('button').find(button => button.text() === '钢琴')!.trigger('click')
  const submit = panel.get<HTMLButtonElement>('.music-settings-footer .button:not(.secondary)')
  await vi.waitFor(() => expect(submit.element.disabled).toBe(false))
  await submit.trigger('click')
  expect(panel.emitted('apply')).toEqual([[{
    expectedVersion: work.version,
    preferences: { ...preferences, recipeId: null, choices: { ...preferences.choices, instruments: ['guitar'] } },
    lyrics: revision.lyrics,
  }]])
})

it('uses the audio task’s recorded genre and voice when the legacy snapshot has no structured choices', async () => {
  api.snapshot.mockResolvedValue({ preferences: { ...preferences, recipeId: null, choices: {}, freeText: '', negativeText: '' } })
  const panel = mountAdjustment()
  await flushPromises()

  const selected = panel.findAll('.music-settings-controls button[aria-pressed="true"]').map(button => button.text())
  expect(selected).toContain('民谣主')
  expect(selected).toContain('女声')
  expect(selected).not.toContain('摇滚主')
  expect(panel.text()).toContain('原版设置记录不完整')
})
