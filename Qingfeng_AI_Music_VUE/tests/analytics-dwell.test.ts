import { expect, it } from 'vitest'
import { accountDetail, accountName, accountTypeLabel, dwellLabel, formatDuration, guestHint, isGuest, pageUsersKey } from '../src/modules/analytics/model/dwell'
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
})
