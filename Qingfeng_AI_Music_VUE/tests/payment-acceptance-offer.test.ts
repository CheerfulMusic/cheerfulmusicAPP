// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import RechargePage from '../src/modules/identity/pages/RechargePage.vue'
import { requestJson, resetCreatorSession } from '../src/shared/http/api-client'
import { selectableCreditPack, stripeOrderRequest, validCreditPack, type CreditPack, type StripeCatalog, type StripePaymentMethodId } from '../src/modules/identity/model/stripe-payment-methods'

vi.mock('../src/shared/identity/account-state',async()=>({accountState:(await import('vue')).ref({authenticated:true}),refreshAccountState:vi.fn(async()=>{})}))
vi.mock('../src/shared/identity/logto-state',async()=>({logtoState:(await import('vue')).ref({authenticated:false}),refreshLogtoState:vi.fn(async()=>{})}))
vi.mock('../src/modules/identity/api/paypal-sdk',()=>({preparePaypalCheckout:vi.fn(async()=>null)}))
vi.mock('../src/shared/identity/credit-balance',()=>({announceCreditsChanged:vi.fn()}))

const expiry=()=>new Date(Date.now()+3600000).toISOString()
function offer(method:StripePaymentMethodId|'paypal',expiresAt=expiry()):CreditPack {
  return {id:`accept-1234abcd-${method}-${method==='wechat_pay'?'CNY':'USD'}`,title:'Temporary payment acceptance',
    amount:method==='paypal'?0.01:method==='wechat_pay'?3:0.5,currency:method==='wechat_pay'?'CNY':'USD',credits:1,
    acceptance:true,expiresAt,maxPurchases:1,...method==='paypal'?{}:{paymentMethod:method}}
}
function stripeCatalog():StripeCatalog {
  return {enabled:true,packages:[],paymentMethods:(['card','apple_pay','wechat_pay'] as const).map(id=>({id,enabled:true,reason:'',
    currencies:[id==='wechat_pay'?'CNY':'USD'],currencyOptions:[{currency:id==='wechat_pay'?'CNY':'USD',enabled:true,reason:''}],
    packages:[offer(id)],requiresCompatibleDevice:id==='apple_pay'}))}
}
let app:App|undefined, catalog:StripeCatalog, paypal:CreditPack[], submissions:{path:string;body:unknown}[]
const reply=(data:unknown)=>new Response(JSON.stringify(data),{status:200})
const drain=async()=>{for(let i=0;i<8;i++){await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}}
beforeEach(()=>{
  resetCreatorSession();localStorage.clear();sessionStorage.clear();catalog=stripeCatalog();paypal=[];submissions=[]
  vi.stubGlobal('fetch',vi.fn(async(input:string,init:RequestInit={})=>{
    const path=input.replace('/api/',''),method=init.method??'GET'
    if(path==='public/csrf')return reply({token:'csrf'})
    if(path==='public/session')return reply({creatorId:'acceptance-owner'})
    if(path==='app/credits')return reply({enabled:true,balance:100,musicCost:5})
    if(path==='app/stripe/catalog')return reply(catalog)
    if(path==='app/paypal/catalog')return reply({enabled:paypal.length>0,packages:paypal})
    if(path==='app/paypal/sdk-config')return reply({enabled:false,clientId:'',environment:'live'})
    if(path.endsWith('/orders')&&method==='POST'){
      submissions.push({path,body:JSON.parse(String(init.body))})
      // Deliberately rejected destination: these tests never leave the local DOM.
      return reply({orderId:'33333333-3333-4333-8333-333333333333',checkoutUrl:'https://untrusted.example/checkout',paypalOrderId:'native-order'})
    }
    if(path.endsWith('/orders'))return reply({orders:[]})
    throw new Error('Unexpected acceptance request '+method+' '+path)
  }))
})
afterEach(()=>{app?.unmount();app=undefined;vi.restoreAllMocks();vi.unstubAllGlobals();resetCreatorSession()})
async function mount(){
  const root=document.createElement('div');app=createApp(RechargePage)
  app.component('RouterLink',defineComponent({props:['to'],setup(props,{slots}){return()=>h('a',{href:props.to},slots.default?.())}}))
  app.mount(root);await drain();return root
}

describe('private minimum offer in actual recharge component',()=>{
  it.each(['card','apple_pay','wechat_pay'] as const)('uses the server-only %s quote exactly once',async method=>{
    const root=await mount();root.querySelector<HTMLButtonElement>(`[data-method="${method}"]`)!.click();await nextTick()
    expect(root.querySelector('[data-testid="acceptance-offer"]')?.textContent).toContain('每个支付渠道限购一次')
    expect(root.querySelector('.summary-total')?.textContent).toContain(method==='wechat_pay'?'¥3.00':'$0.50')
    expect([...root.querySelectorAll<HTMLButtonElement>('.plan')].every(button=>button.disabled)).toBe(true)
    expect(submissions).toEqual([])
    const pay=root.querySelector<HTMLButtonElement>('.pay-button')!;pay.click();pay.click();await drain()
    expect(submissions).toEqual([{path:'app/stripe/orders',body:{packageId:offer(method).id,paymentMethod:method}}])
  })
  it('uses the one-cent PayPal snapshot without posting amount or currency',async()=>{
    paypal=[offer('paypal')]
    const root=await mount()
    expect(root.querySelector('.summary-total')?.textContent).toContain('$0.01')
    root.querySelector<HTMLButtonElement>('.pay-button')!.click();await drain()
    expect(submissions).toEqual([{path:'app/paypal/orders',body:{packageId:offer('paypal').id}}])
  })
  it('displays an expired quote but disables buying and never invents a normal fallback',async()=>{
    catalog.paymentMethods![0]!.packages=[offer('card','2020-01-01T00:00:00Z')]
    const root=await mount()
    expect(root.querySelector('[data-testid="select-acceptance-offer"]')?.textContent).toContain('报价已过期')
    expect(root.querySelector<HTMLButtonElement>('.pay-button')!.disabled).toBe(true)
    expect(root.querySelector('.summary-total')?.textContent).toContain('—')
    expect(submissions).toEqual([])
  })
  it('rechecks time at the actual click, before the display timer can tick',async()=>{
    const root=await mount(),expires=Date.parse(catalog.paymentMethods![0]!.packages[0]!.expiresAt!)
    vi.spyOn(Date,'now').mockReturnValue(expires)
    root.querySelector<HTMLButtonElement>('.pay-button')!.click();await drain()
    expect(submissions).toEqual([])
  })
  it('does not fabricate a temporary package when server access is absent or already used',async()=>{
    catalog.paymentMethods!.forEach(method=>{method.packages=[]})
    const root=await mount()
    expect(root.querySelector('[data-testid="acceptance-offer"]')).toBeNull()
    expect(root.querySelector<HTMLButtonElement>('.pay-button')!.disabled).toBe(true)
    expect(submissions).toEqual([])
  })
  it('keeps the selected ordinary tier when changing between normal card and Apple Pay catalogs',async()=>{
    catalog.paymentMethods!.forEach(method=>{method.packages=method.id==='wechat_pay'?[]:[
      {id:'starter-USD',title:'100 credits',amount:9.9,currency:'USD',credits:100},
      {id:'standard-USD',title:'300 credits',amount:25.9,currency:'USD',credits:300}]})
    const root=await mount();root.querySelector<HTMLButtonElement>('.plan')!.click();await nextTick()
    root.querySelector<HTMLButtonElement>('[data-method="apple_pay"]')!.click();await nextTick()
    expect(root.querySelector('.summary-total')?.textContent).toContain('$9.90')
    expect(root.querySelector('[data-testid="acceptance-offer"]')).toBeNull()
    expect(submissions).toEqual([])
  })
})

describe('private quote contract and expiration',()=>{
  it('accepts complete private metadata and rejects malformed or missing limits',()=>{
    const good=offer('card');expect(validCreditPack(good)).toBe(true)
    for(const broken of [{...good,credits:100},{...good,maxPurchases:2},{...good,maxPurchases:undefined},
      {...good,expiresAt:undefined},{...good,expiresAt:'tomorrow'},{...good,expiresAt:'2027-01-01T00:00:00+00:00'},
      {...good,acceptance:false},{...good,acceptance:'true'}])expect(validCreditPack(broken)).toBe(false)
  })
  it('expires exactly at the UTC boundary and blocks Stripe order construction',()=>{
    const pack=offer('card'),now=Date.parse(pack.expiresAt!)
    expect(selectableCreditPack(pack,now-1)).toBe(true);expect(selectableCreditPack(pack,now)).toBe(false)
    const method=stripeCatalog().paymentMethods![0]!;method.packages=[pack]
    vi.spyOn(Date,'now').mockReturnValue(now)
    expect(()=>stripeOrderRequest(method,pack,'USD')).toThrow('not available')
  })
  it('preserves ordinary package selection with no acceptance metadata',()=>{
    const pack={id:'standard-USD',title:'300 credits',amount:25.9,currency:'USD',credits:300}
    expect(selectableCreditPack(pack)).toBe(true)
    const method=stripeCatalog().paymentMethods![0]!;method.packages=[pack]
    expect(stripeOrderRequest(method,pack,'USD')).toEqual({packageId:'standard-USD',paymentMethod:'card'})
  })
  it('validates catalog metadata before exposing it to the page',async()=>{
    catalog.paymentMethods![0]!.packages=[{...offer('card'),maxPurchases:2}]
    await expect(requestJson('app/stripe/catalog')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  })
})
