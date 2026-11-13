import { describe, expect, it } from 'vitest'
import { validResponse } from '../src/shared/http/response-contracts'
import { orderAmount, mergeOrderHistory } from '../src/modules/identity/model/order-history'
import { preferredCurrency, stripeMethods, stripeOrderRequest, validStripeMethods, type StripePaymentMethod } from '../src/modules/identity/model/stripe-payment-methods'

const usd = { id:'standard-USD', title:'300 Credits', amount:25.9, currency:'USD', credits:300 }
const cny = { id:'standard-CNY-wechat_pay', title:'300 Credits', amount:178.8, currency:'CNY', credits:300, paymentMethod:'wechat_pay' as const }
function methods(): StripePaymentMethod[] {
  return [
    ...(['card','apple_pay'] as const).map(id => ({ id, enabled:true, reason:'', currencies:['USD' as const], currencyOptions:[{currency:'USD' as const,enabled:true,reason:''}], packages:[usd], requiresCompatibleDevice:id==='apple_pay' })),
    { id:'wechat_pay', enabled:true, reason:'', currencies:['CNY'], currencyOptions:[{currency:'USD',enabled:false,reason:'CURRENCY_UNSUPPORTED'},{currency:'CNY',enabled:true,reason:''}],packages:[cny],requiresCompatibleDevice:false },
  ]
}

describe('server-owned Stripe wallet choices', () => {
  it('keeps legacy servers card-only rather than claiming wallet activation', () => {
    const normalized=stripeMethods({enabled:true,packages:[usd]})
    expect(normalized.filter(method=>method.enabled).map(method=>method.id)).toEqual(['card'])
    expect(normalized.find(method=>method.id==='wechat_pay')?.packages).toEqual([])
    expect(validResponse('app/stripe/catalog','GET',{enabled:true,packages:[usd]})).toBe(true)
  })
  it('uses the explicit server CNY offer and never converts a USD price', () => {
    const wallet=methods()[2]!
    expect(preferredCurrency(wallet,'USD')).toBe('CNY')
    expect(stripeOrderRequest(wallet,cny,'CNY')).toEqual({packageId:'standard-CNY-wechat_pay',paymentMethod:'wechat_pay'})
    expect(() => stripeOrderRequest(wallet,usd,'USD')).toThrow()
    expect(() => stripeOrderRequest(wallet,{...cny,amount:25.9},'CNY')).toThrow()
  })
  it('sends only identifiers while preserving the Apple Pay hosted-card choice', () => {
    expect(stripeOrderRequest(methods()[1],usd,'USD')).toEqual({packageId:'standard-USD',paymentMethod:'apple_pay'})
    expect(Object.keys(stripeOrderRequest(methods()[0],usd,'USD')).sort()).toEqual(['packageId','paymentMethod'])
  })
  it('can display an activation-pending quote without permitting a purchase', () => {
    const wallet={...methods()[2]!,enabled:false,reason:'METHOD_DISABLED'}
    expect(wallet.packages[0]?.amount).toBe(178.8)
    expect(() => stripeOrderRequest(wallet,cny,'CNY')).toThrow()
  })
  it('never lets method-level flags override a closed global purchase gate', () => {
    const normalized=stripeMethods({enabled:false,packages:[usd],paymentMethods:methods()})
    expect(normalized.every(method=>!method.enabled)).toBe(true)
    expect(() => stripeOrderRequest(normalized[2],cny,'CNY')).toThrow()
  })
  it('accepts the full wallet response with server currency restrictions', () => {
    const catalog={enabled:true,packages:[usd],paymentMethods:methods()}
    expect(validStripeMethods(catalog.paymentMethods)).toBe(true)
    expect(validResponse('app/stripe/catalog','GET',catalog)).toBe(true)
    expect(catalog.paymentMethods[2]?.currencyOptions[0]?.enabled).toBe(false)
  })
  it('fails closed for incomplete or duplicate method metadata', () => {
    expect(validStripeMethods(methods().slice(0,2))).toBe(false)
    expect(validStripeMethods([methods()[0],methods()[0],methods()[2]])).toBe(false)
    expect(stripeMethods({enabled:true,packages:[usd],paymentMethods:[] })).toEqual([])
  })
  it.each([NaN,Infinity,0,-1,68.801,Number.MAX_SAFE_INTEGER])('rejects invalid currency amounts instead of rounding them: %s', amount => {
    const options=methods(); options[2]!.packages=[{...cny,amount}]
    expect(validResponse('app/stripe/catalog','GET',{enabled:true,packages:[usd],paymentMethods:options})).toBe(false)
  })
  it('rejects a quote in a currency the same method says is unsupported', () => {
    const options=methods(); options[2]!.packages=[{...usd,paymentMethod:'wechat_pay'}]
    expect(validStripeMethods(options)).toBe(false)
  })
  it('rejects duplicate offers and an offer attributed to another payment method', () => {
    const options=methods();options[2]!.packages=[cny,cny]
    expect(validStripeMethods(options)).toBe(false)
    options[2]!.packages=[{...cny,paymentMethod:'card'}]
    expect(validStripeMethods(options)).toBe(false)
  })
  it('uses only the historical order currency and amount, preserving them across a status-only update', () => {
    const order={provider:'stripe' as const,orderId:'order',status:'PENDING' as const,amount:178.8,currency:'CNY',credits:300}
    const merged=mergeOrderHistory([order],[{provider:'stripe',orderId:'order',status:'PAID'}])
    expect(orderAmount(merged[0]!)).toBe('CNY ¥178.80')
    expect(orderAmount({...order,currency:'USD',amount:25.9})).toBe('USD $25.90')
    expect(orderAmount({provider:'stripe',orderId:'old',status:'PAID'})).toBe('')
  })
  it('requires amount and currency together when a new history snapshot is present', () => {
    const order={orderId:'order',status:'PAID',credits:300}
    expect(validResponse('app/stripe/orders/order','GET',order)).toBe(true)
    expect(validResponse('app/stripe/orders/order','GET',{...order,amount:178.8})).toBe(false)
    expect(validResponse('app/stripe/orders/order','GET',{...order,amount:178.8,currency:'CNY',paymentMethod:'wechat_pay'})).toBe(true)
    expect(validResponse('app/stripe/orders/order','GET',{...order,amount:178.8,currency:'CNY',paymentMethod:'fake_wallet'})).toBe(false)
  })
})
