import { describe, expect, it } from 'vitest'
import { validResponse } from '../src/shared/http/response-contracts'

const totals = { pv: 10, pageviews: 6, uv: 3, sessions: 4, avgDurationMs: 1200, totalDurationMs: 5000 }
const daily = [{ d: '2026-11-10', pv: 10, pageviews: 6, uv: 3, avgDurationMs: 1200 }]
const pages = [{ pagePath: '/', pv: 6, avgDurationMs: 1200, samples: 2 }]
const user = {
  creatorId: 'creator-1', accountType: 'GOOGLE', name: 'Jiang', email: 'jiang@example.com', phoneMasked: null,
  pv: 6, pageviews: 3, avgDurationMs: 1200, samples: 2, firstSeen: '', lastSeen: '',
}
const summary = { allowed: true, days: 30, totals, daily, pages, users: [user] }

describe('数据看板响应契约', () => {
  it('完整 summary 通过校验', () => {
    expect(validResponse('app/analytics/summary', 'GET', summary)).toBe(true)
  })

  it('QUERY_FAILED 必须只带 error；夹带半份指标仍判非法，避免把不确定数据渲染成 0', () => {
    expect(validResponse('app/analytics/summary', 'GET', { allowed: true, days: 30, error: 'QUERY_FAILED' })).toBe(true)
    expect(validResponse('app/analytics/summary', 'GET', { allowed: true, days: 30, totals, error: 'QUERY_FAILED' })).toBe(false)
  })

  it('空 creator_id 不再让整份响应失效（脏行由页面过滤）', () => {
    expect(validResponse('app/analytics/summary', 'GET', { ...summary, users: [{ ...user, creatorId: '' }] })).toBe(true)
  })

  it('指标缺失仍然判非法，不把 undefined 渲染成 0', () => {
    expect(validResponse('app/analytics/summary', 'GET', { ...summary, totals: undefined })).toBe(false)
    expect(validResponse('app/analytics/summary', 'GET', { ...summary, users: [{ ...user, pv: 'many' }] })).toBe(false)
  })

  it('access 明确拒绝时不带 viewer 也合法', () => {
    expect(validResponse('app/analytics/access', 'GET', { allowed: false })).toBe(true)
    expect(validResponse('app/analytics/access', 'GET', { allowed: true, viewer: '' })).toBe(false)
  })
})
