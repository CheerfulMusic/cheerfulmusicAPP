// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import RechargePage from '../src/modules/identity/pages/RechargePage.vue'
import { accountState } from '../src/shared/identity/account-state'
import { resetCreatorSession } from '../src/shared/http/api-client'

vi.mock('../src/shared/identity/account-state', async () => ({
  accountState: (await import('vue')).ref({ authenticated: true }), refreshAccountState: vi.fn(async () => {}),
}))
vi.mock('../src/shared/identity/logto-state', async () => ({
  logtoState: (await import('vue')).ref({ authenticated: false }), refreshLogtoState: vi.fn(async () => {}),
}))
vi.mock('../src/modules/identity/api/paypal-sdk', () => ({ preparePaypalCheckout: vi.fn(async () => null) }))
vi.mock('../src/shared/identity/credit-balance', () => ({ announceCreditsChanged: vi.fn() }))

const orderId = '22222222-2222-4222-8222-222222222222'
const pack = { id: 'standard-USD', title: '300 Credits', credits: 300, amount: 25.9, currency: 'USD' }
let app: App | undefined
let calls: string[]
let pendingProvider: 'stripe' | 'paypal' | 'airwallex' | null
const reply = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })
const drain = async () => { for (let turn = 0; turn < 8; turn++) { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick() } }

beforeEach(() => {
  resetCreatorSession(); localStorage.clear(); sessionStorage.clear()
  ;(accountState as unknown as { value: { authenticated: boolean } }).value.authenticated = true
  calls = []; pendingProvider = null
  vi.stubGlobal('fetch', vi.fn(async (input: string, init: RequestInit = {}) => {
    const path = input.replace('/api/', ''), method = init.method || 'GET'; calls.push(`${method} ${path}`)
    if (path === 'public/csrf') return reply({ token: 'csrf' })
    if (path === 'public/session') return reply({ creatorId: 'embedded-owner' })
    if (path === 'app/credits') return reply({ enabled: true, balance: 100, musicCost: 15 })
    if (path === 'app/stripe/catalog') return reply({ enabled: true, packages: [pack] })
    if (path === 'app/paypal/catalog' || path === 'app/payments/catalog') return reply({ enabled: false, packages: [] })
    const histories = { 'app/stripe/orders': 'stripe', 'app/paypal/orders': 'paypal', 'app/payments/orders': 'airwallex' }
    if (path in histories && method === 'GET') return reply({ orders: pendingProvider === histories[path as keyof typeof histories]
      ? [{ orderId, status: 'PENDING', credits: 300, createdAt: '2026-11-12T00:00:00Z' }] : [] })
    if (path === `app/stripe/orders/${orderId}` && method === 'GET') return reply({ orderId, status: 'PENDING', credits: 300 })
    throw new Error(`Unexpected embedded checkout request: ${method} ${path}`)
  }))
})
afterEach(() => { app?.unmount(); app = undefined; vi.unstubAllGlobals(); vi.clearAllMocks(); resetCreatorSession() })

async function mount(embedded: boolean) {
  const root = document.createElement('div'), login = vi.fn(), orders = vi.fn()
  // Mount the actual payment component and real response decoder, not AgentNativeDialog's stub.
  app = createApp(RechargePage, { embedded, onLogin: login, onOrders: orders })
  app.component('RouterLink', defineComponent({ props: ['to'], setup(props, { slots }) { return () => h('a', { href: props.to }, slots.default?.()) } }))
  app.mount(root); await drain(); return { root, login, orders }
}

describe('real Stripe recharge inside the Agent native dialog contract', () => {
  it('emits embedded login rather than navigating away or creating a payment', async () => {
    ;(accountState as unknown as { value: { authenticated: boolean } }).value.authenticated = false
    const { root, login, orders } = await mount(true)
    const button = root.querySelector<HTMLButtonElement>('button.pay-button')
    expect(button?.textContent).toContain('登录后继续'); button!.click(); await drain()
    expect(login).toHaveBeenCalledTimes(1); expect(orders).not.toHaveBeenCalled()
    expect(root.querySelector('a.pay-button')).toBeNull()
    expect(calls.filter(call => call.startsWith('POST '))).toEqual(['POST public/session'])
  })
  it.each(['stripe', 'paypal', 'airwallex'] as const)('keeps %s pending payment blocked and emits the embedded order action', async provider => {
    pendingProvider = provider
    const { root, orders } = await mount(true)
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    const action = root.querySelector<HTMLButtonElement>('button.order-entry')
    expect(action?.textContent).toContain('查看待处理订单'); action!.click(); await drain()
    expect(orders).toHaveBeenCalledTimes(1); expect(root.querySelector('a.order-entry')).toBeNull()
    expect(calls.filter(call => call.startsWith('POST '))).toEqual(['POST public/session'])
  })
  it('preserves the embedded footer order entry even when no order needs recovery', async () => {
    const { root, orders } = await mount(true)
    const action = root.querySelector<HTMLButtonElement>('.footnote button')
    expect(action?.textContent).toContain('查看我的订单'); action!.click()
    expect(orders).toHaveBeenCalledTimes(1); expect(root.querySelector('.footnote a')).toBeNull()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(false)
  })
  it('retains standalone account navigation without emitting embedded actions', async () => {
    ;(accountState as unknown as { value: { authenticated: boolean } }).value.authenticated = false
    const { root, login, orders } = await mount(false)
    expect(root.querySelector('a.pay-button')?.getAttribute('href')).toBe('/account')
    expect(root.querySelector('.footnote a')?.getAttribute('href')).toBe('/account')
    expect(login).not.toHaveBeenCalled(); expect(orders).not.toHaveBeenCalled()
  })
})
