// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

const api = vi.hoisted(() => ({ select: vi.fn(), load: vi.fn() }))
vi.mock('../src/modules/works/api/covers', () => ({
  coverUrl: (_work: string, id: string | null) => id ? `/covers/${id}` : '/default.jpg',
  selectCover: api.select, loadCovers: api.load, editCover: vi.fn(), uploadCover: vi.fn(),
}))
vi.mock('../src/modules/works/api/detail', () => ({ getUsage: async () => ({}) }))
import CoverEditor from '../src/modules/works/components/CoverEditor.vue'

const state = { currentCoverId: 'current', version: 4, assets: [], edits: [], aiAvailable: false }
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  api.load.mockResolvedValue(state)
  api.select.mockResolvedValue({ ...state, version: 5 })
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: vi.fn() })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: vi.fn() })
  wrapper = mount(CoverEditor, { props: { workId: 'work', state, published: true }, global: { stubs: { Teleport: true } } })
})
afterEach(() => wrapper.unmount())

it('lets the owner save the current cover again to synchronize an old publication', async () => {
  expect(wrapper.text()).toContain('保存后将同步更新广场和歌手主页')
  await wrapper.findAll('button').find(button => button.text() === '保存封面')!.trigger('click')
  await flushPromises()
  expect(api.select).toHaveBeenCalledWith('work', 'current', 4)
  expect(wrapper.emitted('saved')).toHaveLength(1)
  expect(wrapper.emitted('updated')?.[0]).toEqual([{ ...state, version: 5 }])
  expect(wrapper.emitted('close')).toHaveLength(1)
})

it('restores the default cover through the same save flow', async () => {
  await wrapper.findAll('button').find(button => button.text() === '恢复默认封面')!.trigger('click')
  await flushPromises()
  expect(api.select).toHaveBeenCalledWith('work', null, 4)
  expect(wrapper.emitted('saved')).toHaveLength(1)
})

it('keeps a failed save open and shows the error without claiming success', async () => {
  api.select.mockRejectedValueOnce(new Error('另一页面已修改封面，请刷新后重试'))
  await wrapper.findAll('button').find(button => button.text() === '保存封面')!.trigger('click')
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('另一页面已修改封面')
  expect(wrapper.emitted('saved')).toBeUndefined()
  expect(wrapper.emitted('close')).toBeUndefined()
})

it('shows the public synchronization hint only for a public song', async () => {
  await wrapper.setProps({ published: false })
  expect(wrapper.text()).not.toContain('保存后将同步更新广场和歌手主页')
})
