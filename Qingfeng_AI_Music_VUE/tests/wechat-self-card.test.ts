// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WechatSelfCard from '../src/modules/works/components/WechatSelfCard.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), request: vi.fn(), ensure: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({
  getJson: api.get, requestJson: api.request, ensureCreator: api.ensure,
  ApiError: class extends Error { constructor(readonly status: number) { super('API error') } },
}))

const path = '/song/' + 'A'.repeat(43)
const challenge = { command: '绑定卡片 0123456789ABCDEF', expiresAt: '2099-01-01T00:00:00Z' }

beforeEach(() => {
  vi.resetAllMocks()
  api.get.mockResolvedValue({ accountName: '青风音乐测试公众号', bound: true })
  api.ensure.mockResolvedValue(undefined)
})

describe('send a song card to my WeChat', () => {
  it('hides the entry when the official-account feature is disabled', async () => {
    api.get.mockRejectedValueOnce(new Error('unavailable'))
    const page = mount(WechatSelfCard, { props: { path } })
    await flushPromises()
    expect(page.find('.wechat-self-send').exists()).toBe(false)
    expect(api.request).not.toHaveBeenCalled()
    page.unmount()
  })

  it('offers the one-time binding command, then sends the song after the binding appears', async () => {
    api.get.mockResolvedValueOnce({ accountName: '青风音乐测试公众号' }).mockResolvedValueOnce({ bound: false })
    api.request.mockResolvedValueOnce(challenge).mockResolvedValueOnce({ state: 'SENT' })
    const page = mount(WechatSelfCard, { props: { path } })
    await flushPromises()
    await page.get('.wechat-self-send').trigger('click')
    await flushPromises()
    expect(api.request.mock.calls).toEqual([['app/wechat-card/binding', 'POST', {}]])
    expect(page.get('input').element.value).toBe(challenge.command)
    expect(page.text()).toContain('青风音乐测试公众号')
    await page.get('.wechat-self-send').trigger('click')
    await flushPromises()
    expect(api.request.mock.calls[1]).toEqual([
      'app/wechat-card/send', 'POST', { path, requestId: expect.any(String) }, 16000,
    ])
    expect(page.text()).toContain('卡片已发到你的微信')
    expect(page.find('input').exists()).toBe(false)
    page.unmount()
  })

  it('reuses the request ID when a send outcome is uncertain', async () => {
    api.request.mockRejectedValueOnce(new Error('lost response')).mockResolvedValueOnce({ state: 'SENT' })
    const page = mount(WechatSelfCard, { props: { path } })
    await flushPromises()
    await page.get('.wechat-self-send').trigger('click')
    await flushPromises()
    expect(page.get('.wechat-self-send').text()).toBe('查询发送结果')
    await page.get('.wechat-self-send').trigger('click')
    await flushPromises()
    expect(api.request.mock.calls[0]).toEqual(api.request.mock.calls[1])
    expect(page.text()).toContain('卡片已发到你的微信')
    page.unmount()
  })

  it('asks the user to refresh an expired WeChat conversation before trying again', async () => {
    api.request.mockResolvedValueOnce({ state: 'NEEDS_MESSAGE' })
    const page = mount(WechatSelfCard, { props: { path } })
    await flushPromises()
    await page.get('.wechat-self-send').trigger('click')
    await flushPromises()
    expect(page.text()).toContain('请给公众号「青风音乐测试公众号」发送「分享卡片」')
    expect(api.request).toHaveBeenCalledTimes(1)
    page.unmount()
  })
})
