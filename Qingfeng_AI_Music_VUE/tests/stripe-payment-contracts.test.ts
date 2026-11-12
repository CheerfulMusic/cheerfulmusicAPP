import { describe, expect, it } from 'vitest'
import { isPendingOrder, mergeOrderHistory, trustedCheckoutUrl, type Order } from '../src/modules/identity/model/order-history'
import { readPendingOrders, savePendingOrders } from '../src/modules/identity/model/payment-orders'
import { validResponse } from '../src/shared/http/response-contracts'

function memoryStorage(): Storage {
  const data = new Map<string, string>()
  return {
    get length() { return data.size }, clear() { data.clear() },
    getItem(key) { return data.get(key) ?? null }, key(index) { return [...data.keys()][index] ?? null },
    removeItem(key) { data.delete(key) }, setItem(key, value) { data.set(key, value) },
  }
}
const id = '11111111-1111-4111-8111-111111111111'

describe('Stripe browser payment boundaries', () => {
  it('accepts the hosted Stripe HTTPS checkout URL', () => {
    expect(trustedCheckoutUrl('https://checkout.stripe.com/c/pay/cs_test_local#example', 'stripe'))
      .toBe('https://checkout.stripe.com/c/pay/cs_test_local#example')
  })

  it.each([
    'http://checkout.stripe.com/c/pay/session',
    'https://checkout.stripe.com.evil.example/c/pay/session',
    'https://checkout.stripe.com@evil.example/c/pay/session',
    'https://user:password@checkout.stripe.com/c/pay/session',
    'https://checkout.stripe.com:444/c/pay/session',
    'https://stripe.com/c/pay/session',
    'https://www.paypal.com/checkoutnow?token=other-provider',
    'javascript:alert(1)',
    '//checkout.stripe.com/c/pay/session',
  ])('rejects an untrusted Stripe redirect: %s', url => {
    expect(() => trustedCheckoutUrl(url, 'stripe')).toThrow()
  })

  it('does not accept a Stripe URL through another provider adapter', () => {
    for (const provider of ['paypal', 'airwallex'] as const) {
      expect(() => trustedCheckoutUrl('https://checkout.stripe.com/c/pay/session', provider)).toThrow()
    }
  })

  it('preserves separate snapshots for the same local ID in three providers', () => {
    const original: Order[] = [
      { provider: 'paypal', orderId: id, status: 'PAID', credits: 100, createdAt: '2026-11-01T00:00:00Z' },
      { provider: 'airwallex', orderId: id, status: 'PAID', credits: 700, createdAt: '2026-11-02T00:00:00Z' },
      { provider: 'stripe', orderId: id, status: 'PENDING', credits: 300, createdAt: '2026-11-03T00:00:00Z' },
    ]
    const merged = mergeOrderHistory(original, [{ provider: 'stripe', orderId: id, status: 'PAID' }])
    expect(merged).toHaveLength(3)
    expect(merged.find(order => order.provider === 'stripe')).toEqual({ ...original[2], status: 'PAID' })
    expect(merged.find(order => order.provider === 'paypal')).toEqual(original[0])
    expect(merged.find(order => order.provider === 'airwallex')).toEqual(original[1])
    expect(original[2]?.status).toBe('PENDING')
  })

  it('isolates pending hints by both provider and owner, retaining the legacy Airwallex key', () => {
    const storage = memoryStorage()
    savePendingOrders(storage, 'owner-a', [id, id], 'stripe')
    expect(readPendingOrders(storage, 'owner-a', 'stripe')).toEqual([id])
    expect(readPendingOrders(storage, 'owner-b', 'stripe')).toEqual([])
    expect(readPendingOrders(storage, 'owner-a', 'paypal')).toEqual([])
    expect(readPendingOrders(storage, 'owner-a', 'airwallex')).toEqual([])
    storage.setItem('qingfeng.paymentOrders.owner-a', JSON.stringify([id]))
    expect(readPendingOrders(storage, 'owner-a', 'airwallex')).toEqual([id])
    savePendingOrders(storage, 'owner-a', [], 'stripe')
    expect(readPendingOrders(storage, 'owner-a', 'airwallex')).toEqual([id])
  })

  it.each(['CREATING', 'PENDING', 'PAID', 'CANCELLED', 'FAILED', 'UNKNOWN'])('decodes the server Stripe order state %s without remapping it', status => {
    const order = { orderId: id, status, credits: 300, createdAt: '2026-11-03T00:00:00Z' }
    expect(validResponse('app/stripe/orders', 'GET', { orders: [order] })).toBe(true)
    expect(validResponse(`app/stripe/orders/${id}`, 'GET', order)).toBe(true)
  })

  it('keeps an uncertain PayPal creation visible for reconciliation instead of treating it as failed', () => {
    const order = { orderId: id, status: 'CREATION_UNKNOWN', credits: 100, createdAt: '2026-11-03T00:00:00Z' }
    expect(validResponse('app/paypal/orders', 'GET', { orders: [order] })).toBe(true)
    expect(validResponse(`app/paypal/orders/${id}`, 'GET', order)).toBe(true)
    expect(isPendingOrder('CREATION_UNKNOWN')).toBe(true)
    expect(isPendingOrder('PAID')).toBe(false)
  })

  it('rejects incomplete successful Stripe HTTP bodies', () => {
    expect(validResponse('app/stripe/orders', 'POST', { orderId: id })).toBe(false)
    expect(validResponse('app/stripe/orders', 'GET', { orders: [{ orderId: id, status: 'PAID', credits: 300 }] })).toBe(false)
    expect(validResponse(`app/stripe/orders/${id}`, 'GET', { orderId: id, status: 'SUCCESS', credits: 300 })).toBe(false)
    expect(validResponse(`app/stripe/orders/${id}/resume`, 'POST', { orderId: id, status: 'PENDING' })).toBe(false)
  })

  it('requires a finite priced package and explicit availability in a Stripe catalog', () => {
    expect(validResponse('app/stripe/catalog', 'GET', { enabled: false, packages: [] })).toBe(true)
    const pack = { id: 'standard-USD', title: '300 Credits', amount: 25.9, currency: 'USD', credits: 300 }
    expect(validResponse('app/stripe/catalog', 'GET', { enabled: true, packages: [pack] })).toBe(true)
    for (const amount of [0, -1, Infinity, NaN]) {
      expect(validResponse('app/stripe/catalog', 'GET', { enabled: true, packages: [{ ...pack, amount }] })).toBe(false)
    }
  })
})
