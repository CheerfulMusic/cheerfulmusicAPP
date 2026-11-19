// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import AnalyticsCountryDistribution from '../src/modules/analytics/components/AnalyticsCountryDistribution.vue'
import AnalyticsPagePerformance from '../src/modules/analytics/components/AnalyticsPagePerformance.vue'
import AnalyticsRechargeTable from '../src/modules/analytics/components/AnalyticsRechargeTable.vue'
import { setLocale } from '../src/shared/i18n'

afterEach(() => { setLocale('zh-CN'); vi.unstubAllGlobals() })

it('localizes page names when the language changes while keeping the detail lookup key', async () => {
  const pagePath = '/artist/hidden-id'
  const wrapper = mount(AnalyticsPagePerformance, {
    props: { rows: [{ pagePath, pv: 2, avgDurationMs: 1000, samples: 1 }] },
  })
  expect(wrapper.text()).toContain('音乐人主页')
  expect(wrapper.html()).not.toContain(pagePath)
  setLocale('en')
  await wrapper.get('.perf-row').trigger('click')
  expect(wrapper.text()).toContain('Artist page')
  expect(wrapper.text()).toContain('1s')
  expect(wrapper.emitted('toggle')).toEqual([[pagePath]])
  wrapper.unmount()
})

it('paginates recharge details and combines filters across pages', async () => {
  const rows = Array.from({ length: 22 }, (_, index) => ({
    creatorId: 'signed-user', accountType: 'GOOGLE', name: index >= 20 ? 'Alice' : 'Bob',
    email: null, phoneMasked: null, packageId: 'internal-package',
    amount: 10, currency: index === 21 ? 'GBP' : 'USD', credits: 100,
    status: index === 21 ? 'PAID' : 'PENDING', createdAt: '2026-11-19T00:00:00Z',
  }))
  const wrapper = mount(AnalyticsRechargeTable, { props: { rows } })
  expect(wrapper.findAll('tbody tr')).toHaveLength(20)
  await wrapper.get('nav').findAll('button')[1]!.trigger('click')
  expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  expect(wrapper.text()).not.toContain('internal-package')
  await wrapper.get('input').setValue('alice')
  const [status, currency] = wrapper.findAll('select')
  await status!.setValue('PAID')
  await currency!.setValue('GBP')
  expect(wrapper.findAll('tbody tr')).toHaveLength(1)
  await currency!.setValue('USD')
  expect(wrapper.findAll('tbody tr')).toHaveLength(0)
  wrapper.unmount()
})

it('filters countries and shows only the selected country’s users when expanded', async () => {
  const fetch = vi.fn(async (_url: string) => new Response(JSON.stringify({
    allowed: true,
    users: [{ creatorId: 'creator-1', accountType: 'GOOGLE', name: 'Alice', email: null, phoneMasked: null,
      hits: 2, lastSeen: '2026-11-19T00:00:00Z' }],
  }), { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(AnalyticsCountryDistribution, { props: { days: 7, rows: [
    { country: '中国', countryCode: 'CN', visitors: 1, hits: 2 },
    { country: '美国', countryCode: 'US', visitors: 2, hits: 3 },
  ] } })
  await wrapper.get('select').setValue('中国')
  expect(wrapper.findAll('.country-row')).toHaveLength(1)
  await wrapper.get('.country-row').trigger('click')
  await flushPromises()
  expect(fetch.mock.calls).toHaveLength(1)
  expect(String(fetch.mock.calls[0])).toContain('days=7&country=%E4%B8%AD%E5%9B%BD')
  expect(wrapper.get('tbody').text()).toContain('Alice')
  await wrapper.get('select').setValue('美国')
  expect(wrapper.find('tbody').exists()).toBe(false)
  wrapper.unmount()
})
