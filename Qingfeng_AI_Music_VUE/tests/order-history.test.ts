import { describe, expect, it } from 'vitest'
import { mergeOrderHistory, trustedCheckoutUrl, type Order } from '../src/modules/identity/model/order-history'
import { validResponse } from '../src/shared/http/response-contracts'

describe('order history', () => {
  it('retains paid orders and updates pending orders without losing their date', () => {
    const original: Order[] = [
      { provider:'paypal', orderId:'one', status:'PAID', credits:100, createdAt:'2026-11-04T10:00:00Z' },
      { provider:'paypal', orderId:'two', status:'PENDING', credits:300, createdAt:'2026-11-05T10:00:00Z' },
    ]
    const result = mergeOrderHistory(original, [{provider:'paypal', orderId:'two', status:'PAID', credits:300}])
    expect(result.map(o => o.orderId)).toEqual(['two', 'one'])
    expect(result[0]).toMatchObject({status:'PAID', createdAt:'2026-11-05T10:00:00Z'})
  })
  it('keeps providers separate and does not invent details for an unknown order', () => {
    const result = mergeOrderHistory([{provider:'paypal',orderId:'same',status:'PAID',credits:100}], [{provider:'airwallex',orderId:'same',status:'UNKNOWN'}])
    expect(result).toHaveLength(2)
    expect(result.find(o => o.provider === 'airwallex')).toEqual({provider:'airwallex',orderId:'same',status:'UNKNOWN'})
  })
  it('only accepts a verified PayPal HTTPS checkout host', () => {
    expect(trustedCheckoutUrl('https://www.sandbox.paypal.com/checkoutnow?token=abc', 'paypal')).toContain('www.sandbox.paypal.com')
    for (const url of ['https://www.paypal.com.evil.test/', 'http://www.paypal.com/', 'https://evil@www.paypal.com/', 'https://www.paypal.com:444/']) {
      expect(() => trustedCheckoutUrl(url, 'paypal')).toThrow()
    }
  })
  it('rejects incomplete or unexpected resume results', () => {
    const path = 'app/paypal/orders/id/resume'
    expect(validResponse(path, 'POST', {orderId:'id',status:'CHECKOUT'})).toBe(false)
    expect(validResponse(path, 'POST', {orderId:'id',status:'CHECKOUT',checkoutUrl:'https://www.paypal.com/'})).toBe(true)
    expect(validResponse(path, 'POST', {orderId:'id',status:'APPROVED'})).toBe(true)
    expect(validResponse(path, 'POST', {orderId:'id',status:'FAILED'})).toBe(false)
  })
})
