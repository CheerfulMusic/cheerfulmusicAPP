import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { expect, it } from 'vitest'
import AnalyticsPageUsers from '../src/modules/analytics/components/AnalyticsPageUsers.vue'

type Row = {
  creatorId: string; accountType: string; name: string | null; email: string | null; phoneMasked: string | null
  avgDurationMs: number; samples: number
}
type Props = { rows: Row[]; loading?: boolean; error?: string }

async function render(props: Props) {
  return renderToString(createSSRApp({ render: () => h(AnalyticsPageUsers, props) }))
}

const rows: Row[] = [
  { creatorId: 'creator-1', accountType: 'GOOGLE', name: 'Jiang', email: 'a@example.com', phoneMasked: null, avgDurationMs: 72_000, samples: 2 },
  { creatorId: '6c768124-f26a-44bc-bc2a-7946108b1bd4', accountType: 'GUEST', name: null, email: null, phoneMasked: null, avgDurationMs: 0, samples: 0 },
]

it('renders the per-user dwell table with full identity and an explicit guest label', async () => {
  const html = await render({ rows })
  expect(html).toContain('用户')
  expect(html).toContain('身份')
  expect(html).toContain('平均停留')
  expect(html).toContain('有效停留次数')
  expect(html).toContain('Jiang')
  expect(html).toContain('a@example.com')
  expect(html).toContain('Google')
  expect(html).toContain('未登录游客')
  expect(html).toContain('游客')
  expect(html).toContain('6c768124-f26a-44bc-bc2a-7946108b1bd4')
  expect(html).toContain('1 分 12 秒')
  expect(html).toContain('共 2 位用户')
  expect(html).toContain('其中 1 位游客')
})

it('shows a dash instead of zero seconds when a row has no samples', async () => {
  const html = await render({ rows: [{ creatorId: 'creator-2', accountType: 'GUEST', name: null, email: null, phoneMasked: null, avgDurationMs: 0, samples: 0 }] })
  expect(html).toContain('—')
  expect(html).not.toContain('0 秒')
})

it('renders loading, error and empty states without leaking stale rows', async () => {
  expect(await render({ rows: [], loading: true })).toContain('正在读取用户明细')
  expect(await render({ rows: [], error: '用户明细读取失败，请稍后重试。' })).toContain('用户明细读取失败')
  const empty = await render({ rows: [] })
  expect(empty).toContain('还没有用户停留记录')
  expect(empty).not.toContain('<table')
})
