// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import RechargePage from '../src/modules/identity/pages/RechargePage.vue'
import { resetCreatorSession } from '../src/shared/http/api-client'

vi.mock('../src/shared/identity/account-state',async()=>({accountState:(await import('vue')).ref({authenticated:true}),refreshAccountState:vi.fn(async()=>{})}))
vi.mock('../src/shared/identity/logto-state',async()=>({logtoState:(await import('vue')).ref({authenticated:false}),refreshLogtoState:vi.fn(async()=>{})}))
vi.mock('../src/modules/identity/api/paypal-sdk',()=>({preparePaypalCheckout:vi.fn(async()=>null)}))
vi.mock('../src/shared/identity/credit-balance',()=>({announceCreditsChanged:vi.fn()}))

const id='44444444-4444-4444-8444-444444444444'
const pack={id:'standard-USD',title:'300 Credits',amount:25.9,currency:'USD',credits:300}
let app:App|undefined
let channel:'paypal'|'payments'|'stripe'
let legacyReadOnly:unknown
let calls:string[]
const response=(value:unknown)=>new Response(JSON.stringify(value),{status:200})
const drain=async()=>{for(let i=0;i<8;i++){await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}}

beforeEach(()=>{
  resetCreatorSession();localStorage.clear();sessionStorage.clear();calls=[];channel='paypal';legacyReadOnly=true
  vi.stubGlobal('fetch',vi.fn(async(input:string,init:RequestInit={})=>{
    const path=input.replace('/api/',''),method=init.method||'GET';calls.push(`${method} ${path}`)
    if(path==='public/csrf')return response({token:'csrf'})
    if(path==='public/session')return response({creatorId:'legacy-readonly-owner'})
    if(path==='app/credits')return response({enabled:true,balance:100,musicCost:5})
    if(path==='app/stripe/catalog')return response({enabled:true,packages:[pack]})
    if(path==='app/paypal/catalog'||path==='app/payments/catalog')return response({enabled:false,packages:[]})
    const pending={orderId:id,status:'PENDING',credits:100,createdAt:'2026-11-12T00:00:00Z',legacyReadOnly}
    if(path===`app/stripe/orders/${id}`)return response(pending)
    if(['app/paypal/orders','app/payments/orders','app/stripe/orders'].includes(path)&&method==='GET')return response({orders:path===`app/${channel}/orders`?[pending]:[]})
    throw new Error('Unexpected legacy-readonly checkout request '+method+' '+path)
  }))
})
afterEach(()=>{app?.unmount();app=undefined;vi.unstubAllGlobals();vi.clearAllMocks();resetCreatorSession()})
async function mount(){
  const root=document.createElement('div');app=createApp(RechargePage)
  app.component('RouterLink',defineComponent({props:['to'],setup(props,{slots}){return()=>h('a',{href:props.to},slots.default?.())}}))
  app.mount(root);await drain();return root
}

describe('server-confirmed legacy isolation in the actual recharge page',()=>{
  it.each(['paypal','payments'] as const)('does not let an explicitly read-only old %s order block new Stripe purchases',async provider=>{
    channel=provider;const root=await mount()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(false)
    expect(root.textContent).not.toContain('有订单需要核对')
    expect(root.querySelector('.summary-total')?.textContent).toContain('$25.90')
    expect(root.querySelector('.summary-line')?.textContent).toContain('300')
    expect(root.querySelectorAll('.plan')).toHaveLength(3)
    expect(calls.filter(call=>call.startsWith('POST '))).toEqual(['POST public/session'])
  })
  it.each([
    ['paypal',false],['paypal',undefined],['paypal','true'],
    ['payments',false],['payments',undefined],['payments','true'],
  ] as const)('retains the old %s unresolved-order block when the isolation marker is %s',async(provider,marker)=>{
    channel=provider;legacyReadOnly=marker;const root=await mount()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    expect(root.textContent).toContain('有订单需要核对')
    expect(calls.filter(call=>call.startsWith('POST '))).toEqual(['POST public/session'])
  })
  it('still checks a pending Stripe order and blocks it even if an unrelated legacy marker is present',async()=>{
    channel='stripe';const root=await mount()
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    expect(root.textContent).toContain('有订单需要核对')
    expect(calls).toContain(`GET app/stripe/orders/${id}`)
    expect(calls.filter(call=>call.startsWith('POST '))).toEqual(['POST public/session'])
  })
})
