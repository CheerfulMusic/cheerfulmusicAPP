// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AnalyticsPage from '../src/modules/analytics/pages/AnalyticsPage.vue'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function summaryFor(pageviews: number, over: Record<string, unknown> = {}) {
  return {
    allowed: true,
    days: 30,
    totals: { pv: pageviews * 2, pageviews, uv: 5, sessions: 6, avgDurationMs: 61_000, totalDurationMs: 305_000 },
    daily: [{ d: '2026-11-10', pv: pageviews * 2, pageviews, uv: 5, avgDurationMs: 61_000 }],
    pages: [{ pagePath: '/create', pv: 12, avgDurationMs: 61_000, samples: 3 }],
    users: [{
      creatorId: 'creator-1', accountType: 'GOOGLE', name: 'Jiang', email: 'a@example.com', phoneMasked: null,
      pv: 9, pageviews: 7, avgDurationMs: 61_000, samples: 2,
      firstSeen: '2026-11-09T00:00:00Z', lastSeen: '2026-11-10T00:00:00Z',
    }],
    ...over,
  }
}

function stubFetch(handler: (url: string) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn((url: unknown) => Promise.resolve(handler(String(url)))))
}

afterEach(() => { vi.unstubAllGlobals() })

describe('AnalyticsPage 状态机', () => {
  it('白名单账号正常加载：展示真实指标且不出现错误态', async () => {
    stubFetch(url => url.includes('/access') ? json({ allowed: true, viewer: 'a@example.com' }) : json(summaryFor(1_234)))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('1,234')
    expect(wrapper.text()).not.toContain('无权访问')
    expect(wrapper.text()).not.toContain('加载失败')
    expect(wrapper.text()).not.toContain('数据不可用')
  })

  it('服务端明确拒绝时显示无权限', async () => {
    stubFetch(() => json({ allowed: false }))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('无权访问')
    expect(wrapper.text()).not.toContain('加载失败')
  })

  it('HTTP 500 显示加载失败而不是无权限，并给出重试', async () => {
    stubFetch(url => url.includes('/access') ? json({}, 500) : json(summaryFor(10)))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).not.toContain('无权访问')
    expect(wrapper.find('.analytics-retry').exists()).toBe(true)
  })

  it('网络断开显示加载失败而不是无权限', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('offline'))))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).not.toContain('无权访问')
  })

  it('刷新失败时保留上一次成功数据并标注过期，不退化成无权限', async () => {
    let fail = false
    stubFetch(url => {
      if (url.includes('/access')) return json({ allowed: true, viewer: 'a@example.com' })
      return fail ? json({}, 500) : json(summaryFor(321))
    })
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('321')
    fail = true
    await wrapper.find('.analytics-refresh').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('321')
    expect(wrapper.text()).toContain('刷新失败')
    expect(wrapper.text()).not.toContain('无权访问')
  })

  it('QUERY_FAILED 显示数据不可用，不渲染 0 也不生成曲线', async () => {
    stubFetch(url => url.includes('/access')
      ? json({ allowed: true, viewer: 'a@example.com' })
      : json({ allowed: true, error: 'QUERY_FAILED' }))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('数据不可用')
    expect(wrapper.text()).toContain('趋势数据不可用')
    expect(wrapper.text()).toContain('页面数据不可用')
    expect(wrapper.text()).toContain('用户数据不可用')
    expect(wrapper.find('.analytics-kpis').exists()).toBe(false)
    expect(wrapper.find('.line.pv').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('无权访问')
  })

  it('缺少 totals 的异常响应不进入 KPI', async () => {
    stubFetch(url => url.includes('/access')
      ? json({ allowed: true, viewer: 'a@example.com' })
      : json({ allowed: true, days: 30, daily: [], pages: [], users: [] }))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.find('.analytics-kpis').exists()).toBe(false)
  })

  it('字段类型错误同样按加载失败处理', async () => {
    stubFetch(url => url.includes('/access')
      ? json({ allowed: true, viewer: 'a@example.com' })
      : json(summaryFor(10, { totals: { pv: '10', pageviews: 10, uv: 1, sessions: 1, avgDurationMs: 1, totalDurationMs: 1 } })))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(wrapper.text()).not.toContain('数据不可用')
  })

  it('合法的零值仍然显示 0，并与不可用状态区分', async () => {
    stubFetch(url => url.includes('/access')
      ? json({ allowed: true, viewer: 'a@example.com' })
      : json({
        allowed: true, days: 30,
        totals: { pv: 0, pageviews: 0, uv: 0, sessions: 0, avgDurationMs: 0, totalDurationMs: 0 },
        daily: [{ d: '2026-11-10', pv: 0, pageviews: 0, uv: 0, avgDurationMs: 0 }],
        pages: [], users: [],
      }))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    expect(wrapper.text()).toContain('访问量 PV')
    expect(wrapper.text()).toContain('0')
    expect(wrapper.text()).not.toContain('数据不可用')
  })
})

describe('周期切换竞态', () => {
  it('快速切换 7→90 时，旧周期的响应不能覆盖最新周期', async () => {
    const pending: { url: string; resolve: (value: Response) => void }[] = []
    vi.stubGlobal('fetch', vi.fn((url: unknown) => {
      const target = String(url)
      if (target.includes('/access')) return Promise.resolve(json({ allowed: true, viewer: 'a@example.com' }))
      return new Promise<Response>(resolve => { pending.push({ url: target, resolve }) })
    }))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    pending.shift()!.resolve(json(summaryFor(300)))
    await flushPromises()
    expect(wrapper.text()).toContain('300')

    const buttons = wrapper.findAll('.analytics-range button')
    await buttons[0]!.trigger('click')
    await flushPromises()
    await buttons[2]!.trigger('click')
    await flushPromises()
    const seven = pending.find(item => item.url.includes('days=7'))!
    const ninety = pending.find(item => item.url.includes('days=90'))!
    ninety.resolve(json(summaryFor(900)))
    await flushPromises()
    seven.resolve(json(summaryFor(700)))
    await flushPromises()
    expect(wrapper.text()).toContain('900')
    expect(wrapper.text()).not.toContain('700')
  })

  it('切换周期时会先清空旧数据，不出现新标签配旧数字', async () => {
    const pending: { url: string; resolve: (value: Response) => void }[] = []
    vi.stubGlobal('fetch', vi.fn((url: unknown) => {
      const target = String(url)
      if (target.includes('/access')) return Promise.resolve(json({ allowed: true, viewer: 'a@example.com' }))
      return new Promise<Response>(resolve => { pending.push({ url: target, resolve }) })
    }))
    const wrapper = mount(AnalyticsPage)
    await flushPromises()
    pending.shift()!.resolve(json(summaryFor(500)))
    await flushPromises()
    await wrapper.findAll('.analytics-range button')[2]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('500')
    pending.find(item => item.url.includes('days=90'))!.resolve(json(summaryFor(901)))
    await flushPromises()
    expect(wrapper.text()).toContain('901')
  })
})
