// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
const mocks = vi.hoisted(() => ({ request: vi.fn(), upload: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({
  ApiError: class extends Error {}, apiUrl: (s: string) => `/api/${s}`, currentCreatorId: () => 'owner', ensureCreator: async () => {}, requestJson: mocks.request, uploadForm: mocks.upload,
}))
import MusicVideoEditor from '../src/modules/works/components/MusicVideoEditor.vue'
beforeEach(() => {
  sessionStorage.clear(); mocks.request.mockReset(); mocks.upload.mockReset()
  HTMLDialogElement.prototype.showModal = vi.fn(); HTMLDialogElement.prototype.close = vi.fn()
  mocks.request.mockImplementation(async (path: string, _method?: string, body?: any) => {
    if (path.includes('/options?')) return { durationMillis: 60000 }
    if (path.endsWith('/prepare')) return { plan: body.plan, credits: 120, generatedSeconds: 60 }
    if (path === 'app/music-videos') return { projectId: '12345678-1234-1234-1234-123456789012' }
    return []
  })
})
describe('MV editor confirmation', () => {
  it('creates no video until the reviewed quote is explicitly confirmed', async () => {
    const wrapper = mount(MusicVideoEditor, { props: { workId: 'work', assetId: 'asset', title: '歌' }, global: { stubs: { AgentVideoProgress: true } } })
    await flushPromises()
    expect(wrapper.findAll('textarea')).toHaveLength(2)
    for (const area of wrapper.findAll('textarea')) await area.setValue('同一位旅人沿海岸前行')
    await wrapper.findAll('button').find(b => b.text() === '核对分镜与费用')!.trigger('click'); await flushPromises()
    expect(mocks.request.mock.calls.filter(c => c[0] === 'app/music-videos')).toHaveLength(0)
    await wrapper.findAll('button').find(b => b.text().startsWith('确认生成 ·'))!.trigger('click'); await flushPromises()
    const created = mocks.request.mock.calls.find(c => c[0] === 'app/music-videos')!
    expect(created[2].quotedCredits).toBe(120); expect(created[2].plan.durationMillis).toBe(60000); expect(created[2].requestKey).toMatch(/^[a-f0-9-]{36}$/)
    wrapper.unmount()
  })
  it('uploads an image without requesting video generation and subtracts its free hold time', async () => {
    mocks.upload.mockResolvedValue({ imageId: 'owned-image' })
    const wrapper = mount(MusicVideoEditor, { props: { workId: 'work', assetId: 'asset', title: '歌' } })
    await flushPromises()
    const input = wrapper.find('input[type=file]')
    Object.defineProperty(input.element, 'files', { value: [new File(['image'], 'photo.png', { type: 'image/png' })] })
    await input.trigger('change'); await flushPromises()
    await wrapper.find('input[type=number]').setValue(10)
    expect(wrapper.text()).toContain('预计 100 积分')
    expect(mocks.request.mock.calls.filter(c => c[0] === 'app/music-videos')).toHaveLength(0)
    wrapper.unmount()
  })
})
