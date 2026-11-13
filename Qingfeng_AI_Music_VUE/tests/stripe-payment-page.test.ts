// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import RechargePage from '../src/modules/identity/pages/RechargePage.vue'
import CreditsPanel from '../src/modules/identity/components/CreditsPanel.vue'
import { ensureCreator, resetCreatorSession } from '../src/shared/http/api-client'
import { readPendingOrders } from '../src/modules/identity/model/payment-orders'

vi.mock('../src/shared/identity/account-state', async () => ({
  accountState: (await import('vue')).ref({ authenticated: true }), refreshAccountState: vi.fn(async () => {}),
}))
vi.mock('../src/shared/identity/logto-state', async () => ({
  logtoState: (await import('vue')).ref({ authenticated: false }), refreshLogtoState: vi.fn(async () => {}),
}))
vi.mock('../src/modules/identity/api/paypal-sdk', () => ({ preparePaypalCheckout: vi.fn(async () => null) }))
vi.mock('../src/shared/identity/credit-balance', () => ({ announceCreditsChanged: vi.fn() }))

const id = '11111111-1111-4111-8111-111111111111'
const pack = { id: 'standard-USD', title: '300 Credits', credits: 300, amount: 25.9, currency: 'USD' }
let app: App | undefined
let calls: string[]
let stripeStatus: string
let stripeCurrent: string
let historyUnavailable: string
let failCreate: boolean
let switchOwnerOnCreate: boolean
let paypalEnabled: boolean
let musicCost: number
let creditsUnavailable: boolean
let creator: string
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const drain = async () => { for (let i = 0; i < 8; i++) { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() } }

beforeEach(() => {
  resetCreatorSession(); localStorage.clear(); sessionStorage.clear()
  calls = []; stripeStatus = 'PAID'; stripeCurrent = 'PAID'; historyUnavailable = ''; failCreate = false; switchOwnerOnCreate = false; paypalEnabled = false; creator = 'owner'
  musicCost = 15; creditsUnavailable = false
  vi.stubGlobal('fetch', vi.fn(async (input: string, init: RequestInit = {}) => {
    const path = input.replace('/api/', ''), method = init.method || 'GET'; calls.push(`${method} ${path}`)
    const order = { orderId: id, status: stripeStatus, credits: 300, createdAt: '2026-11-09T05:00:00Z' }
    if (path === 'public/csrf') return reply({ token: 'csrf' })
    if (path === 'public/session') return reply({ creatorId: creator })
    if (path === 'app/credits') {
      if (creditsUnavailable) throw new TypeError('isolated price service unavailable')
      return reply({ enabled: true, balance: stripeCurrent === 'PAID' ? 300 : 0, musicCost })
    }
    if (path === 'app/stripe/catalog') return reply({ enabled: true, packages: [pack] })
    if (path === 'app/paypal/catalog') return reply({ enabled: paypalEnabled, packages: paypalEnabled ? [pack] : [] })
    if (path === 'app/payments/catalog') return reply({ enabled: false, packages: [] })
    if (path === 'app/paypal/sdk-config') return reply({ enabled: false, clientId: '', environment: 'production' })
    if (path.endsWith('/orders') && method === 'GET') {
      if (path === historyUnavailable) throw new TypeError('isolated network failure')
      if (path === 'app/stripe/orders') return reply({ orders: [order] })
      return reply({ orders: [] })
    }
    if (path === `app/stripe/orders/${id}`) return reply({ ...order, status: stripeCurrent })
    if (path === 'app/stripe/orders' && method === 'POST') {
      if (failCreate) throw new TypeError('result unknown')
      if (switchOwnerOnCreate) { creator = 'other-owner'; resetCreatorSession(); await ensureCreator() }
      return reply({ orderId: id, checkoutUrl: 'https://untrusted.example/checkout' })
    }
    throw new Error(`Unexpected isolated request: ${method} ${path}`)
  }))
})

afterEach(() => { app?.unmount(); app = undefined; vi.unstubAllGlobals(); vi.clearAllMocks(); resetCreatorSession() })

async function mount(component: typeof RechargePage | typeof CreditsPanel) {
  const root = document.createElement('div'); app = createApp(component)
  app.component('RouterLink', defineComponent({ props: ['to'], setup(props, { slots }) { return () => h('a', { href: props.to }, slots.default?.()) } }))
  app.mount(root); await drain(); return root
}

describe('Stripe recharge and account with the real HTTP decoder', () => {
  it('shows the server song price of five and derives all generation estimates without changing USD package snapshots', async () => {
    musicCost = 5
    const root = await mount(RechargePage)
    expect(root.textContent).toContain('每次生成歌曲消耗 5 积分')
    expect([...root.querySelectorAll('.plan-info')].map(element => element.textContent?.trim())).toEqual([
      '约可生成 20 次歌曲', '约可生成 60 次歌曲', '约可生成 140 次歌曲',
    ])
    expect(root.textContent).toContain('$25.90')
    expect(root.querySelector('button.pay-button')?.textContent).toContain('银行卡')
    expect(calls).not.toContain('POST app/stripe/orders')
  })

  it.each([0, -5])('hides song price and estimates when the server price is invalid: %s', async price => {
    musicCost = price
    const root = await mount(RechargePage)
    expect(root.querySelector('.balance small')).toBeNull()
    expect(root.querySelectorAll('.plan-info')).toHaveLength(0)
    expect(root.textContent).toContain('$25.90')
    expect(calls).not.toContain('POST app/stripe/orders')
  })

  it('does not invent an old fallback generation price when the credits API cannot be read', async () => {
    creditsUnavailable = true
    const root = await mount(RechargePage)
    expect(root.querySelector('.balance small')).toBeNull()
    expect(root.querySelectorAll('.plan-info')).toHaveLength(0)
    expect(root.textContent).not.toContain('15 积分')
    expect(calls).not.toContain('POST app/stripe/orders')
  })

  it('shows the actual USD snapshot and enables Stripe without creating an order on mount', async () => {
    const root = await mount(RechargePage)
    expect(root.textContent).toContain('USD')
    expect(root.textContent).toContain('$25.90')
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(false)
    expect(root.querySelector('button.pay-button')?.textContent).toContain('银行卡')
    expect(calls.filter(call => call.startsWith('POST '))).toEqual(['POST public/session'])
  })

  it.each(['PENDING', 'CREATING', 'UNKNOWN'])('keeps new purchases closed for unresolved Stripe state %s', async status => {
    stripeStatus = status; stripeCurrent = status
    const root = await mount(RechargePage)
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    expect(root.textContent).toContain('有订单需要核对')
    expect(calls).toContain(`GET app/stripe/orders/${id}`)
    expect(calls).not.toContain('POST app/stripe/orders')
  })

  it.each(['app/stripe/orders', 'app/paypal/orders', 'app/payments/orders'])('closes Stripe new purchases when history is unavailable at %s', async path => {
    historyUnavailable = path
    const root = await mount(RechargePage)
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    expect(root.textContent).toContain('有订单需要核对')
    expect(calls).not.toContain('POST app/stripe/orders')
  })

  it('accepts a late server-confirmed payment without issuing client-side credit or capture writes', async () => {
    stripeStatus = 'PENDING'; stripeCurrent = 'PAID'
    const root = await mount(RechargePage)
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(false)
    expect(root.textContent).not.toContain('有订单需要核对')
    expect(calls.filter(call => call === 'GET app/credits')).toHaveLength(2)
    expect(calls.filter(call => call.startsWith('POST '))).toEqual(['POST public/session'])
  })

  it('does not retry an unknown order creation and disables further clicks', async () => {
    failCreate = true
    const root = await mount(RechargePage)
    root.querySelector<HTMLButtonElement>('button.pay-button')!.click(); await drain()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')!.disabled).toBe(true)
    expect(root.textContent).toContain('订单状态暂时无法确认')
    root.querySelector<HTMLButtonElement>('button.pay-button')!.click(); await drain()
    expect(calls.filter(call => call === 'POST app/stripe/orders')).toHaveLength(1)
    expect(readPendingOrders(localStorage, 'owner', 'stripe')).toEqual([])
  })

  it('blocks a returned checkout URL outside Stripe and preserves its order for recovery', async () => {
    const root = await mount(RechargePage)
    root.querySelector<HTMLButtonElement>('button.pay-button')!.click(); await drain()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')!.disabled).toBe(true)
    expect(root.textContent).toContain('订单状态暂时无法确认')
    expect(readPendingOrders(localStorage, 'owner', 'stripe')).toEqual([id])
    expect(readPendingOrders(localStorage, 'owner', 'paypal')).toEqual([])
  })

  it('submits only one Stripe order for two immediate clicks', async () => {
    const root = await mount(RechargePage)
    const button = root.querySelector<HTMLButtonElement>('button.pay-button')!
    button.click(); button.click(); await drain()
    expect(calls.filter(call => call === 'POST app/stripe/orders')).toHaveLength(1)
    expect(button.disabled).toBe(true)
  })

  it('does not save an old owner payment into either identity after an account switch', async () => {
    switchOwnerOnCreate = true
    const root = await mount(RechargePage)
    root.querySelector<HTMLButtonElement>('button.pay-button')!.click(); await drain()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')!.disabled).toBe(true)
    expect(root.textContent).toContain('订单状态暂时无法确认')
    expect(readPendingOrders(localStorage, 'owner', 'stripe')).toEqual([])
    expect(readPendingOrders(localStorage, 'other-owner', 'stripe')).toEqual([])
    expect(calls.filter(call => call === 'POST app/stripe/orders')).toHaveLength(1)
  })

  it('shows Stripe paid history through the account page without invoking PayPal capture', async () => {
    const root = await mount(CreditsPanel)
    const cards = [...root.querySelectorAll('.orders li')]
    expect(cards).toHaveLength(1)
    expect(cards[0]?.textContent).toContain('Stripe')
    expect(cards[0]?.textContent).toContain('支付已确认')
    expect(cards[0]?.textContent).toContain('300')
    expect(calls.some(call => call.includes('/capture'))).toBe(false)
    expect(calls).not.toContain('POST app/stripe/orders')
  })
})
