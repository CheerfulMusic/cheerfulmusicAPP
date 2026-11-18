// @vitest-environment happy-dom
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AnalyticsPage from '../src/modules/analytics/pages/AnalyticsPage.vue'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json' },
})
const people = [
  { creatorId: 'old', accountType: 'GOOGLE', name: 'Old Active', pageviews: 20, lastSeen: '2026-11-14T00:00:00Z' },
  { creatorId: 'guest', accountType: 'GUEST', name: 'Guest Middle', pageviews: 10, lastSeen: '2026-11-15T00:00:00Z' },
  { creatorId: 'new', accountType: 'OIDC_EMAIL', name: 'Recent Visitor', pageviews: 1, lastSeen: '2026-11-16T00:00:00Z' },
].map(row => ({ email: null, phoneMasked: null, pv: row.pageviews * 2, avgDurationMs: 1000, samples: 1, firstSeen: row.lastSeen, ...row }))
function summary(latest = false) {
  return {
    allowed: true, days: 30,
    totals: { pv: 62, pageviews: 31, uv: 3, sessions: 3, avgDurationMs: 1000, totalDurationMs: 3000 },
    daily: [], pages: [], users: latest ? [...people].reverse() : people,
  }
}
const wrappers: VueWrapper[] = []
function start(handler: (url: string) => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn((url: unknown) => Promise.resolve(
    String(url).includes('/access') ? json({ allowed: true, viewer: 'viewer@example.com' }) : handler(String(url)),
  )))
  const wrapper = mount(AnalyticsPage)
  wrappers.push(wrapper)
  return wrapper
}
const names = (wrapper: VueWrapper) => wrapper.findAll('.users-row .user-main strong').map(row => row.text())
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })

describe('数据看板用户排序', () => {
  it('默认活跃；最新向服务端重新取数，并保留仅登录筛选', async () => {
    const urls: string[] = []
    const wrapper = start(url => { urls.push(url); return json(summary(url.includes('userSort=latest'))) })
    await flushPromises()
    expect(urls[0]).toContain('days=30&userSort=active')
    expect(names(wrapper)).toEqual(['Old Active', 'Guest Middle', 'Recent Visitor'])
    await wrapper.get('[aria-label="按身份筛选用户"]').findAll('button')[1]!.trigger('click')
    expect(names(wrapper)).toEqual(['Old Active', 'Recent Visitor'])
    await wrapper.get('[aria-label="用户排序"]').findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(urls.at(-1)).toContain('userSort=latest')
    expect(names(wrapper)).toEqual(['Recent Visitor', 'Old Active'])
    expect(wrapper.get('[aria-label="按身份筛选用户"]').findAll('button')[1]!.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[aria-label="用户排序"]').findAll('button')[1]!.attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('按最近访问时间从新到旧排序')
    await wrapper.get('[aria-label="用户排序"]').findAll('button')[0]!.trigger('click')
    await flushPromises()
    expect(names(wrapper)).toEqual(['Old Active', 'Recent Visitor'])
  })

  it('最新排序可组合仅游客，刷新及切换统计周期后仍保留', async () => {
    const urls: string[] = []
    const wrapper = start(url => { urls.push(url); return json(summary(url.includes('userSort=latest'))) })
    await flushPromises()
    await wrapper.get('[aria-label="按身份筛选用户"]').findAll('button')[2]!.trigger('click')
    await wrapper.get('[aria-label="用户排序"]').findAll('button')[1]!.trigger('click')
    await flushPromises()
    await wrapper.get('.analytics-refresh').trigger('click')
    await flushPromises()
    expect(urls.at(-1)).toContain('days=30&userSort=latest')
    await wrapper.get('.analytics-range').findAll('button')[0]!.trigger('click')
    await flushPromises()
    expect(urls.at(-1)).toContain('days=7&userSort=latest')
    expect(names(wrapper)).toEqual(['Guest Middle'])
    expect(wrapper.get('[aria-label="按身份筛选用户"]').findAll('button')[2]!.attributes('aria-pressed')).toBe('true')
  })

  it('排序请求失败不把旧榜单标为最新；重试仍请求最新排序', async () => {
    let fail = true
    const urls: string[] = []
    const wrapper = start(url => {
      urls.push(url)
      return url.includes('userSort=latest') && fail ? json({}, 500) : json(summary(url.includes('userSort=latest')))
    })
    await flushPromises()
    await wrapper.get('[aria-label="用户排序"]').findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('加载失败')
    expect(names(wrapper)).toEqual([])
    fail = false
    await wrapper.get('.analytics-retry').trigger('click')
    await flushPromises()
    expect(urls.at(-1)).toContain('userSort=latest')
    expect(names(wrapper)).toEqual(['Recent Visitor', 'Guest Middle', 'Old Active'])
  })

  it('排序切换后旧周期的晚到响应不能覆盖新周期列表', async () => {
    const pending: { url: string; resolve: (response: Response) => void }[] = []
    let defer = false
    const wrapper = start(url => defer
      ? new Promise<Response>(resolve => pending.push({ url, resolve }))
      : json(summary()))
    await flushPromises()
    defer = true
    const kpis = wrapper.get('.analytics-kpis').element
    await wrapper.get('[aria-label="用户排序"]').findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('.analytics-kpis').element).toBe(kpis)
    expect(wrapper.text()).toContain('正在加载用户列表')
    expect(names(wrapper)).toContain('Old Active')
    await wrapper.get('.analytics-range').findAll('button')[0]!.trigger('click')
    await flushPromises()
    expect(pending).toHaveLength(2)
    expect(pending.every(request => request.url.includes('userSort=latest'))).toBe(true)
    pending[1]!.resolve(json(summary(true)))
    await flushPromises()
    expect(names(wrapper)).toEqual(['Recent Visitor', 'Guest Middle', 'Old Active'])
    pending[0]!.resolve(json(summary(false)))
    await flushPromises()
    expect(names(wrapper)).toEqual(['Recent Visitor', 'Guest Middle', 'Old Active'])
  })
})
