// @vitest-environment jsdom
import {expect, it, vi} from 'vitest'
import {createApp, h, ref} from 'vue'
import SectionEditor from '../src/modules/works/components/SectionEditor.vue'

const api = vi.hoisted(() => ({
  context: vi.fn(), list: vi.fn(), submit: vi.fn(), play: vi.fn(),
}))

vi.mock('../src/modules/works/api/section-edits', () => ({
  sectionContext: api.context,
  listSectionEdits: api.list,
  submitSectionEdit: api.submit,
  getAudioSelection: async () => ({version: 0, assetId: 'original'}),
  selectAudio: vi.fn(),
}))
vi.mock('../src/shared/player/store', () => ({usePlayer: () => ({playSegment: api.play})}))

it('selects one direction across both sets and previews the same range on each version', async () => {
  const lyrics = '陪伴每个清晨'
  api.context.mockResolvedValue({available: true, lyrics, duration: 120, lines: [], vocalAdjustmentAvailable: true})
  api.list.mockResolvedValue([])
  api.submit.mockImplementation(async (_id, input) => {
    api.list.mockResolvedValue([{
      taskId: 'edited', sourceAssetId: 'original', resultAssetId: 'adjusted',
      start: input.start, end: input.end, replacementLyrics: lyrics, status: 'SUCCEEDED',
      createdAt: '2026-11-16T00:00:00Z', vocalAdjustment: input.vocalAdjustment,
    }])
  })

  const open = ref(true)
  const work = {id: 'work', version: 1, title: '歌曲', recipient: '', occasion: '', story: '', message: '', style: '', vocalGender: 'AUTO' as const, createdAt: '', updatedAt: ''}
  const asset = {id: 'original', workId: work.id, origin: 'GENERATED' as const, contentType: 'audio/mpeg', byteSize: 1, createdAt: ''}
  const root = document.body.appendChild(document.createElement('div'))
  const app = createApp({setup: () => () => h(SectionEditor, {
    work, asset, title: work.title, open: open.value, tasks: [], onClose: () => { open.value = false },
  })})
  const dialog = HTMLDialogElement.prototype
  const methods = {showModal: dialog.showModal, close: dialog.close, scrollIntoView: HTMLElement.prototype.scrollIntoView}
  dialog.showModal = function () { this.open = true }
  dialog.close = function () { this.open = false }
  HTMLElement.prototype.scrollIntoView = vi.fn()
  const button = (label: string) => [...root.querySelectorAll('button')].find(node => node.textContent?.trim() === label)!
  try {
    app.mount(root)
    await vi.waitFor(() => expect(root.querySelector('.section-edit-modes')).toBeTruthy())
    root.querySelectorAll<HTMLInputElement>('.section-edit-modes input')[1]!.click()
    await vi.waitFor(() => expect(root.querySelectorAll('.section-vocal-group input')).toHaveLength(6))
    const more = root.querySelector<HTMLDetailsElement>('.section-vocal-more')!
    expect(more.open).toBe(false)
    expect(more.querySelectorAll('input')).toHaveLength(3)

    const line = root.querySelector('select')!
    line.value = '0'
    line.dispatchEvent(new Event('change'))
    root.querySelector<HTMLInputElement>('input[value="FALLING_END"]')!.click()
    more.open = true
    more.querySelector<HTMLInputElement>('input[value="LONGER_END"]')!.click()
    await vi.waitFor(() => expect(root.querySelector('.section-vocal-confirmation')?.textContent).toContain('最后一个字多唱一会儿'))
    expect([...root.querySelectorAll<HTMLInputElement>('input[name="section-vocal-adjustment"]:checked')].map(input => input.value)).toEqual(['LONGER_END'])
    button('生成试听版').click()

    await vi.waitFor(() => expect(button('听修改版')).toBeTruthy())
    expect(api.submit).toHaveBeenCalledWith('work', expect.objectContaining({vocalAdjustment: 'LONGER_END'}))
    expect(api.submit.mock.calls[0]![1]).not.toHaveProperty('replacementLyrics')
    expect(root.querySelector('.section-version-lyrics')?.textContent).toContain('尾音拉长')
    button('听原版').click()
    button('听修改版').click()
    expect(api.play.mock.calls.map(([track]) => track.id)).toEqual(['original', 'adjusted'])
    expect(api.play.mock.calls[0]!.slice(1, 3)).toEqual(api.play.mock.calls[1]!.slice(1, 3))
  } finally {
    app.unmount()
    root.remove()
    dialog.showModal = methods.showModal
    dialog.close = methods.close
    HTMLElement.prototype.scrollIntoView = methods.scrollIntoView
    sessionStorage.clear()
  }
})
