import { expect, it } from 'vitest'
import { accountDetail, accountName, accountTypeLabel, avatarText, buildTrend, dwellLabel, filterUsers, formatDuration, guestHint, isGuest, pageName, pageUsersKey, shareOf } from '../src/modules/analytics/model/dwell'
import { validResponse } from '../src/shared/http/response-contracts'

it('formats dwell time for the dashboard and keeps zero honest', () => {
  expect(formatDuration(0)).toBe('0 秒')
  expect(formatDuration(950)).toBe('1 秒')
  expect(formatDuration(72_000)).toBe('1 分 12 秒')
  expect(formatDuration(3_900_000)).toBe('1 小时 5 分')
})

it('never renders a missing sample as zero seconds', () => {
  expect(dwellLabel(0, 0)).toBe('—')
  expect(dwellLabel(12_000, 0)).toBe('—')
  expect(dwellLabel(Number.NaN, 3)).toBe('—')
  expect(dwellLabel(12_000, 3)).toBe('12 秒')
})

it('keys drill-down caches by range so a new window cannot reuse old rows', () => {
  expect(pageUsersKey(7, '/create')).toBe('7:/create')
  expect(pageUsersKey(30, '/')).not.toBe(pageUsersKey(7, '/'))
})

it('shows the most complete identity available and labels guests explicitly', () => {
  const google = { creatorId: 'c1', accountType: 'GOOGLE', name: 'Jiang', email: 'a@example.com', phoneMasked: null }
  expect(accountName(google)).toBe('Jiang')
  expect(accountDetail(google)).toBe('a@example.com')
  expect(accountTypeLabel(google.accountType)).toBe('Google')

  const oidcEmail = { creatorId: 'c2', accountType: 'OIDC_EMAIL', name: null, email: 'b@example.com', phoneMasked: null }
  expect(accountName(oidcEmail)).toBe('b@example.com')
  expect(accountTypeLabel(oidcEmail.accountType)).toBe('邮箱登录')

  const phone = { creatorId: 'c3', accountType: 'OIDC_PHONE', name: null, email: null, phoneMasked: '****9246' }
  expect(accountName(phone)).toBe('****9246')
  expect(accountTypeLabel(phone.accountType)).toBe('手机登录')

  const guest = { creatorId: '6c768124-f26a-44bc-bc2a-7946108b1bd4', accountType: 'GUEST', name: null, email: null, phoneMasked: null }
  expect(accountName(guest)).toBe('未登录游客')
  expect(accountDetail(guest)).toBe('')
  expect(accountTypeLabel(guest.accountType)).toBe('游客')
  expect(isGuest(guest.accountType)).toBe(true)
  expect(isGuest('GOOGLE')).toBe(false)
  expect(guestHint(6)).toContain('6 位游客')
  expect(guestHint(0)).toBe('')
})

it('filters users by identity without mutating the source list', () => {
  const rows = [
    { creatorId: 'g', accountType: 'GOOGLE' },
    { creatorId: 'p', accountType: 'OIDC_PHONE' },
    { creatorId: 'x', accountType: 'GUEST' },
  ]
  expect(filterUsers(rows, 'all').map(r => r.creatorId)).toEqual(['g', 'p', 'x'])
  expect(filterUsers(rows, 'signed').map(r => r.creatorId)).toEqual(['g', 'p'])
  expect(filterUsers(rows, 'guest').map(r => r.creatorId)).toEqual(['x'])
  expect(rows).toHaveLength(3)
})

it('fills the whole range with real zero days so single-day data still draws', () => {
  const trend = buildTrend([{ d: '2026-11-10', pv: 3, uv: 2, pageviews: 2, avgDurationMs: 1000 }], 7, new Date(2026, 10, 10))
  expect(trend).toHaveLength(7)
  expect(trend[0]!.d).toBe('2026-11-04')
  expect(trend[6]).toMatchObject({ d: '2026-11-10', pageviews: 2, uv: 2 })
  expect(trend[0]).toMatchObject({ pageviews: 0, uv: 0 })
  // 缺失的日期补 0；已存在的日期保留真实 pageview 值。
  expect(buildTrend([{ d: '2026-11-10', pv: 5, uv: 1, pageviews: 4, avgDurationMs: 0 }], 1, new Date(2026, 10, 10))[0]!.pageviews).toBe(4)
})

it('computes page share, names and avatar safely', () => {
  expect(shareOf(25, 100)).toBe(0.25)
  expect(shareOf(5, 0)).toBe(0)
  expect(shareOf(-1, 10)).toBe(0)
  expect(pageName('/create')).toBe('创作页')
  expect(pageName('/unknown-path')).toBe('/unknown-path')
  expect(avatarText({ creatorId: 'c', accountType: 'GOOGLE', name: 'jiang' })).toBe('J')
  expect(avatarText({ creatorId: 'c', accountType: 'OIDC_PHONE', phoneMasked: '****9246' })).toBe('手')
  expect(avatarText({ creatorId: 'c', accountType: 'GUEST' })).toBe('游')
})

it('rejects drill-down responses without complete dwell rows', () => {
  expect(validResponse('app/analytics/page-users', 'GET', { allowed: false })).toBe(true)
  expect(validResponse('app/analytics/page-users', 'GET', {
    allowed: true, days: 30, pagePath: '/create',
    users: [{ creatorId: 'c1', accountType: 'GOOGLE', name: 'Jiang', email: 'a@example.com', phoneMasked: null, avgDurationMs: 3000, samples: 2 }],
  })).toBe(true)
  expect(validResponse('app/analytics/page-users', 'GET', {
    allowed: true, days: 30, pagePath: '/create',
    users: [{ creatorId: 'c1', accountType: 'GUEST', name: null, email: null, phoneMasked: null, avgDurationMs: 3000, samples: 2 }],
  })).toBe(true)
  expect(validResponse('app/analytics/page-users', 'GET', {
    allowed: true, days: 30, pagePath: '/create',
    users: [{ creatorId: 'c1', accountType: 'GOOGLE', name: null, email: 'a@example.com', phoneMasked: null, avgDurationMs: 3000 }],
  })).toBe(false)
  expect(validResponse('app/analytics/page-users', 'GET', {
    allowed: true, days: 30, pagePath: '/create', users: [{ creatorId: '', accountType: 'GUEST', name: null, email: null, phoneMasked: null, avgDurationMs: 0, samples: 0 }],
  })).toBe(false)
  expect(validResponse('app/analytics/page-users', 'GET', { allowed: true, days: 30, pagePath: '/create' })).toBe(false)

  expect(validResponse('app/analytics/user-pages', 'GET', { allowed: false })).toBe(true)
  expect(validResponse('app/analytics/user-pages', 'GET', {
    allowed: true, days: 30, creatorId: 'creator-1',
    pages: [{ pagePath: '/create', avgDurationMs: 3000, samples: 2 }],
  })).toBe(true)
  expect(validResponse('app/analytics/user-pages', 'GET', {
    allowed: true, days: 30, creatorId: 'creator-1',
    pages: [{ pagePath: '/create', avgDurationMs: 3000 }],
  })).toBe(false)
})

const fullSummary = () => ({
  allowed: true, days: 30,
  totals: { pv: 10, pageviews: 6, uv: 3, sessions: 4, avgDurationMs: 5000, totalDurationMs: 20000 },
  daily: [{ d: '2026-11-10', pv: 10, pageviews: 6, uv: 3, avgDurationMs: 5000 }],
  pages: [{ pagePath: '/create', pv: 6, avgDurationMs: 5000, samples: 2 }],
  users: [{
    creatorId: 'creator-1', accountType: 'GOOGLE', name: 'Jiang', email: 'a@example.com', phoneMasked: null,
    pv: 9, pageviews: 5, avgDurationMs: 5000, samples: 2, firstSeen: '2026-11-09T00:00:00Z', lastSeen: '2026-11-10T00:00:00Z',
  }],
})

it('看板 summary / access 契约区分零值、缺字段与业务错误', () => {
  expect(validResponse('app/analytics/access', 'GET', { allowed: true, viewer: 'a@example.com' })).toBe(true)
  expect(validResponse('app/analytics/access', 'GET', { allowed: false })).toBe(true)
  expect(validResponse('app/analytics/access', 'GET', { allowed: true })).toBe(false)
  expect(validResponse('app/analytics/access', 'GET', { allowed: 'yes' })).toBe(false)

  expect(validResponse('app/analytics/summary', 'GET', fullSummary())).toBe(true)
  expect(validResponse('app/analytics/summary', 'GET', { allowed: false })).toBe(true)
  expect(validResponse('app/analytics/summary', 'GET', { allowed: true, error: 'QUERY_FAILED' })).toBe(true)
  expect(validResponse('app/analytics/summary', 'GET', { allowed: true, error: 'QUERY_FAILED', totals: fullSummary().totals })).toBe(false)

  const missingTotals = { ...fullSummary(), totals: undefined }
  expect(validResponse('app/analytics/summary', 'GET', missingTotals)).toBe(false)
  const stringUv = fullSummary(); (stringUv.totals as Record<string, unknown>).uv = '3'
  expect(validResponse('app/analytics/summary', 'GET', stringUv)).toBe(false)
  const noPagePath = fullSummary(); (noPagePath.pages[0] as Record<string, unknown>).pagePath = ''
  expect(validResponse('app/analytics/summary', 'GET', noPagePath)).toBe(false)
  const noSamples = fullSummary(); delete (noSamples.pages[0] as Record<string, unknown>).samples
  expect(validResponse('app/analytics/summary', 'GET', noSamples)).toBe(false)
  const noPageviews = fullSummary(); delete (noPageviews.users[0] as Record<string, unknown>).pageviews
  expect(validResponse('app/analytics/summary', 'GET', noPageviews)).toBe(false)
  expect(validResponse('app/analytics/summary', 'GET', { ...fullSummary(), daily: 'nope' })).toBe(false)

  const zero = fullSummary()
  zero.totals = { pv: 0, pageviews: 0, uv: 0, sessions: 0, avgDurationMs: 0, totalDurationMs: 0 }
  zero.daily = [{ d: '2026-11-10', pv: 0, pageviews: 0, uv: 0, avgDurationMs: 0 }]
  zero.pages = []; zero.users = []
  expect(validResponse('app/analytics/summary', 'GET', zero)).toBe(true)
})
