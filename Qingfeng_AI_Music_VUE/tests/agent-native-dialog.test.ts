// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ upload: vi.fn(), covers: vi.fn(), owner: 'owner-1' }))
vi.mock('../src/shared/http/api-client', () => ({ uploadForm: api.upload, currentCreatorId: () => api.owner }))
vi.mock('../src/modules/works/api/covers', () => ({ loadCovers: api.covers }))
vi.mock('../src/modules/identity/pages/RechargePage.vue', () => ({ default: { props: { embedded: Boolean }, emits: ['orders', 'login'], template: '<div class="recharge-stub"><span>{{ embedded }}</span><button @click="$emit(\'orders\')">订单</button></div>' } }))
vi.mock('../src/modules/identity/pages/IdentityPage.vue', () => ({ default: { props: { embedded: Boolean }, template: '<div class="identity-stub">{{ embedded }}</div>' } }))
vi.mock('../src/modules/works/components/CoverEditor.vue', () => ({ default: { props: ['workId', 'state'], template: '<div class="cover-stub">{{ workId }}</div>' } }))
import AgentNativeDialog from '../src/modules/agent/components/AgentNativeDialog.vue'
import { setLocale } from '../src/shared/i18n'
const wrappers: Array<{ unmount(): void }> = []
async function dialog(kind: 'upload_audio' | 'upload_cover' | 'recharge' | 'account' | 'privacy') {
  const wrapper = mount(AgentNativeDialog, { props: { kind, workId: 'work-1' } }); wrappers.push(wrapper)
  await flushPromises(); return wrapper
}
beforeEach(() => { vi.clearAllMocks(); api.owner = 'owner-1'; setLocale('zh-CN') })
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()) })
describe('native actions inside the conversation', () => {
  it('reuses recharge in embedded mode and returns order checks to the conversation', async () => {
    const wrapper = await dialog('recharge')
    await vi.waitFor(() => expect(wrapper.find('.recharge-stub').exists()).toBe(true))
    expect(wrapper.find('.recharge-stub').text()).toContain('true')
    await wrapper.find('.recharge-stub button').trigger('click')
    expect(wrapper.emitted('send')).toEqual([['查看我的订单']])
  })
  it('scopes cover editor to the requested work and uses its real cover state', async () => {
    api.covers.mockResolvedValue({ currentCoverId: null, version: 1, assets: [], edits: [], aiAvailable: false })
    const wrapper = await dialog('upload_cover')
    await vi.waitFor(() => expect(wrapper.find('.cover-stub').exists()).toBe(true))
    expect(api.covers).toHaveBeenCalledWith('work-1')
    expect(wrapper.find('.cover-stub').text()).toBe('work-1')
  })
  it('uploads only supported local audio and asks to check versions before another uncertain upload', async () => {
    api.upload.mockRejectedValueOnce(new Error('网络中断，结果待核对'))
    const wrapper = await dialog('upload_audio'), input = wrapper.find('input')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['audio'], 'song.ogg', { type: 'audio/ogg' })] })
    await input.trigger('change'); expect(api.upload).not.toHaveBeenCalled()
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['audio'], 'song.wav', { type: 'audio/wav' })] })
    await input.trigger('change'); await flushPromises()
    expect(api.upload).toHaveBeenCalledWith('app/works/work-1/audio', expect.any(FormData))
    expect(input.attributes('disabled')).toBeDefined()
    await input.trigger('change'); expect(api.upload).toHaveBeenCalledTimes(1)
    await wrapper.find('.agent-import-audio button').trigger('click')
    expect(wrapper.emitted('send')).toEqual([['查看作品 work-1 的音频版本']])
  })
  it('ignores an upload reply that belongs to a previous identity', async () => {
    let resolve!: (value: unknown) => void
    api.upload.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const wrapper = await dialog('upload_audio'), input = wrapper.find('input')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['audio'], 'song.mp3')] })
    await input.trigger('change')
    api.owner = 'owner-2'; resolve({ id: 'audio-1' }); await flushPromises()
    expect(wrapper.emitted('refresh')).toBeUndefined()
    expect(wrapper.text()).not.toContain('音频已上传')
  })
})
