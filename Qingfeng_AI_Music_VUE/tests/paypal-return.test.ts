import { describe, expect, it } from 'vitest'
import { canCapturePaypal, paypalReturn } from '../src/modules/identity/model/paypal-return'

describe('PayPal return hints', () => {
  const id = '12345678-1234-1234-1234-123456789abc'
  it('recognizes a single local approved or cancelled order', () => {
    expect(paypalReturn(`?paypal_order=${id}&token=provider`)).toEqual({orderId:id,approved:true})
    expect(paypalReturn(`?paypal_cancel=${id}`)).toEqual({orderId:id,approved:false})
  })
  it('rejects malformed and ambiguous hints', () => {
    expect(paypalReturn('?paypal_order=bad')).toBeNull()
    expect(paypalReturn(`?paypal_order=${id}&paypal_order=${id}`)).toBeNull()
    expect(paypalReturn(`?paypal_order=${id}&paypal_cancel=${id}`)).toBeNull()
  })
  it('only captures approved orders belonging to the current identity', () => {
    const approved = paypalReturn(`?paypal_order=${id}`)
    expect(canCapturePaypal(approved, [id], true)).toBe(true)
    expect(canCapturePaypal(approved, [], true)).toBe(false)
    expect(canCapturePaypal(approved, [id], false)).toBe(false)
    expect(canCapturePaypal(paypalReturn(`?paypal_cancel=${id}`), [id], true)).toBe(false)
  })
})
