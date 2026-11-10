import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { expect, it } from 'vitest'
import AnalyticsKpiCard from '../src/modules/analytics/components/AnalyticsKpiCard.vue'
import AnalyticsTrendChart from '../src/modules/analytics/components/AnalyticsTrendChart.vue'
import AnalyticsPagePerformance from '../src/modules/analytics/components/AnalyticsPagePerformance.vue'
import AnalyticsUserList from '../src/modules/analytics/components/AnalyticsUserList.vue'

function render(component: unknown, props: Record<string, unknown>) {
  return renderToString(createSSRApp({ render: () => h(component as never, props) }))
}

const trend = [
  { d: '2026-11-09', pv: 12, uv: 4, pageviews: 8, avgDurationMs: 60_000 },
  { d: '2026-11-10', pv: 20, uv: 6, pageviews: 15, avgDurationMs: 42_000 },
]

it('KPI 卡片展示主数字、说明与真实 sparkline', async () => {
  const html = await render(AnalyticsKpiCard, { label: '访问量 PV', value: '1,234', hint: '总访问次数', icon: 'eye', spark: [3, 9, 6, 14] })
  expect(html).toContain('访问量 PV')
  expect(html).toContain('1,234')
  expect(html).toContain('总访问次数')
  expect(html).toContain('<path')
})

it('KPI 卡片数据不足时不画 sparkline', async () => {
  const html = await render(AnalyticsKpiCard, { label: '平均访问时长', value: '1 分 2 秒', hint: '基于 3 次有效停留', icon: 'clock' })
  expect(html).toContain('1 分 2 秒')
  expect(html).not.toContain('kpi-spark')
})

it('趋势图用真实数据画双折线并带坐标轴', async () => {
  const html = await render(AnalyticsTrendChart, { points: trend })
  expect(html).toContain('line pv')
  expect(html).toContain('line uv')
  expect(html).toContain('11/9')
  expect(html).toContain('11/10')
  expect(html).not.toContain('这段时间还没有访问')
})

it('趋势图全为 0 时显示专业空状态而不是一条假线', async () => {
  const html = await render(AnalyticsTrendChart, {
    points: [
      { d: '2026-11-09', pv: 0, uv: 0, pageviews: 0, avgDurationMs: 0 },
      { d: '2026-11-10', pv: 0, uv: 0, pageviews: 0, avgDurationMs: 0 },
    ],
  })
  expect(html).toContain('这段时间还没有访问')
  expect(html).not.toContain('line pv')
})

it('页面表现按行渲染访问量、占比与停留，无样本显示「—」', async () => {
  const rows = [
    { pagePath: '/create', pv: 283, avgDurationMs: 52_000, samples: 120 },
    { pagePath: '/works', pv: 0, avgDurationMs: 0, samples: 0 },
  ]
  const html = await render(AnalyticsPagePerformance, { rows, openPath: '/create', detailRows: [], detailLoading: true })
  expect(html).toContain('创作页')
  expect(html).toContain('/create')
  expect(html).toContain('283')
  expect(html).toContain('100%')
  expect(html).toContain('52 秒')
  expect(html).toContain('—')
  expect(html).toContain('aria-expanded="true"')
  expect(html).toContain('正在读取用户明细')
})

it('用户列表区分游客并展示访问次数与页面明细', async () => {
  const rows = [
    {
      creatorId: 'creator-1', accountType: 'GOOGLE', name: 'Jiang', email: 'a@example.com', phoneMasked: null,
      pv: 40, pageviews: 22, avgDurationMs: 61_000, samples: 9, firstSeen: '2026-11-09T01:00:00Z', lastSeen: '2026-11-10T02:00:00Z',
    },
    {
      creatorId: '6c768124-f26a-44bc-bc2a-7946108b1bd4', accountType: 'GUEST', name: null, email: null, phoneMasked: null,
      pv: 4, pageviews: 2, avgDurationMs: 0, samples: 0, firstSeen: '2026-11-09T01:00:00Z', lastSeen: '2026-11-09T02:00:00Z',
    },
  ]
  const html = await render(AnalyticsUserList, {
    rows,
    openUser: 'creator-1',
    detailRows: [{ pagePath: '/create', avgDurationMs: 52_000, samples: 6 }],
  })
  expect(html).toContain('1 位登录用户 · 1 位游客')
  expect(html).toContain('Jiang')
  expect(html).toContain('Google')
  expect(html).toContain('未登录游客')
  expect(html).toContain('游客')
  expect(html).toContain('22')
  expect(html).toContain('创作页')
  expect(html).toContain('6 次')
  expect(html).toContain('—')
})
