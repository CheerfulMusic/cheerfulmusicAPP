// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { featureTarget, sharedDownloadUrl } from '../src/modules/agent/model/assistant-result'
const api = vi.hoisted(() => ({ request: vi.fn(), upload: vi.fn(), owner: 'owner' }))
vi.mock('../src/shared/http/api-client', () => ({ currentCreatorId: () => api.owner, requestJson: api.request, uploadForm: api.upload }))
import AgentFeatureDialog from '../src/modules/agent/components/AgentFeatureDialog.vue'
import AgentAdminPanel from '../src/modules/agent/components/AgentAdminPanel.vue'
beforeEach(() => {
 vi.clearAllMocks(); api.owner = 'owner'; api.request.mockResolvedValue({ durationMillis: 35000 }); api.upload.mockResolvedValue({ imageId: 'real-upload-id' })
 HTMLDialogElement.prototype.showModal = vi.fn(); HTMLDialogElement.prototype.close = vi.fn()
})
describe('Agent feature boundaries', () => {
 it('accepts only known panels and scoped IDs, never arbitrary URLs or paths', () => {
  const target = (value: unknown) => featureTarget({ kind: 'feature', label: '打开', text: JSON.stringify(value) })
  expect(target({ feature: 'mv', workId: 'w', assetId: 'a' })?.feature).toBe('mv')
  for (const value of [{ feature: 'http' }, { feature: 'mv', workId: '../other', assetId: 'a' }, { feature: 'mv_image', workId: 'w' }]) expect(target(value)).toBeNull()
  expect(sharedDownloadUrl('https://outside.example')).toBe('')
  expect(sharedDownloadUrl('/api/app/shared-downloads/12345678-1234-1234-1234-123456789012')).not.toBe('')
 })
 it('uploads to the owned work and sends the real image ID and hold time without creating video', async () => {
  const wrapper = mount(AgentFeatureDialog, { props: { target: { feature: 'mv_image', workId: 'w', assetId: 'a' } } })
  await flushPromises()
  const input = wrapper.get('input[type=file]')
  Object.defineProperty(input.element, 'files', { value: [new File(['image'], 'photo.png', { type: 'image/png' })] })
  await input.trigger('change'); await flushPromises()
  expect((api.upload.mock.calls[0][1] as FormData).get('workId')).toBe('w')
  const numbers = wrapper.findAll('input[type=number]'); await numbers[0]!.setValue(2); await numbers[1]!.setValue(5)
  await wrapper.findAll('button').find(b => b.text().startsWith('带入对话'))!.trigger('click')
  const text = wrapper.emitted('send')![0]![0] as string
  expect(text).toContain('real-upload-id'); expect(text).toContain('第2段'); expect(text).toContain('停留5秒'); expect(text).toContain('暂不生成视频')
  expect(api.request.mock.calls.every(c => c[0].includes('/options?'))).toBe(true)
  wrapper.unmount()
 })
 it('rejects a still longer than the final segment', async () => {
  const wrapper = mount(AgentFeatureDialog, { props: { target: { feature: 'mv_image', workId: 'w', assetId: 'a' } } })
  await flushPromises(); const input = wrapper.get('input[type=file]')
  Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'p.png', { type: 'image/png' })] })
  await input.trigger('change'); await flushPromises()
  const numbers = wrapper.findAll('input[type=number]'); await numbers[0]!.setValue(2); await numbers[1]!.setValue(6)
  await wrapper.findAll('button').find(b => b.text().startsWith('带入对话'))!.trigger('click')
  expect(wrapper.emitted('send')).toBeUndefined(); expect(wrapper.text()).toContain('超出歌曲范围')
  wrapper.unmount()
 })
 it('does not send a financial review just by opening or first clicking review', async () => {
  api.request.mockResolvedValue([{ id: 'case', provider: 'stripe', orderId: 'order', eventType: 'dispute', lastEventId: 'event', status: 'OPEN' }])
  const wrapper = mount(AgentAdminPanel, { props: { feature: 'payment_risk' } }); await flushPromises()
  const select = wrapper.findAll('select')[0]!
  await select.setValue((select.findAll('option')[1]!.element as HTMLOptionElement).value)
  // An explicit second confirmation is required before the native mutation.
  await wrapper.get('textarea').setValue('已经仔细核对真实订单和风险记录')
  await wrapper.findAll('button').find(b => b.text() === '核对操作')!.trigger('click')
  expect(api.request.mock.calls.every(c => c[0] === 'app/payment-risk/cases')).toBe(true)
  await wrapper.findAll('button').find(b => b.text() === '确认执行上述操作')!.trigger('click'); await flushPromises()
  expect(api.request).toHaveBeenCalledWith('app/payment-risk/cases/case/review', 'POST', { expectedEventId: 'event', decision: 'HOLD', reason: '已经仔细核对真实订单和风险记录' })
  wrapper.unmount()
 })
})
