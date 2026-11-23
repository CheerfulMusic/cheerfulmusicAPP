// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import RechargePage from '../src/modules/identity/pages/RechargePage.vue'
import CreditsPanel from '../src/modules/identity/components/CreditsPanel.vue'
import { resetCreatorSession } from '../src/shared/http/api-client'
import { readPendingOrders } from '../src/modules/identity/model/payment-orders'
import { locale, setLocale } from '../src/shared/i18n'
import type { StripePaymentMethod } from '../src/modules/identity/model/stripe-payment-methods'

vi.mock('../src/shared/identity/account-state',async()=>({accountState:(await import('vue')).ref({authenticated:true}),refreshAccountState:vi.fn(async()=>{})}))
vi.mock('../src/shared/identity/logto-state',async()=>({logtoState:(await import('vue')).ref({authenticated:false}),refreshLogtoState:vi.fn(async()=>{})}))
vi.mock('../src/modules/identity/api/paypal-sdk',()=>({preparePaypalCheckout:vi.fn(async()=>null)}))
vi.mock('../src/shared/identity/credit-balance',()=>({announceCreditsChanged:vi.fn()}))

const tiers=[100,300,700]
const usd=tiers.map((credits,i)=>({id:['starter','standard','plus'][i]+'-USD',title:credits+' Credits',amount:[9.9,25.9,49.9][i]!,currency:'USD',credits}))
const cny=tiers.map((credits,i)=>({id:['starter','standard','plus'][i]+'-CNY-wechat_pay',title:credits+' Credits',amount:[68.8,178.8,338.8][i]!,currency:'CNY',credits,paymentMethod:'wechat_pay' as const}))
const mini = { id: 'mini-CNY-wechat_pay', title: '35 Credits', amount: 29.9, currency: 'CNY', credits: 35, paymentMethod: 'wechat_pay' as const }
const orderId='33333333-3333-4333-8333-333333333333'
let app: App|undefined
let catalog: {enabled:boolean;packages:typeof usd;paymentMethods?:StripePaymentMethod[]}
let submitted: Record<string,unknown>[]
let pending: string
let oldPaid: boolean
const response=(body:unknown)=>new Response(JSON.stringify(body),{status:200})
const drain=async()=>{for(let i=0;i<8;i++){await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}}

beforeEach(()=>{
  resetCreatorSession();localStorage.clear();sessionStorage.clear();submitted=[];pending='';oldPaid=false
  catalog={enabled:true,packages:usd,paymentMethods:[
    ...(['card','apple_pay'] as const).map(id=>({id,enabled:true,reason:'',currencies:['USD' as const],currencyOptions:[{currency:'USD' as const,enabled:true,reason:''}],packages:usd,requiresCompatibleDevice:id==='apple_pay'})),
    {id:'wechat_pay',enabled:true,reason:'',currencies:['CNY'],currencyOptions:[{currency:'USD',enabled:false,reason:'CURRENCY_UNSUPPORTED'},{currency:'CNY',enabled:true,reason:''}],packages:cny,requiresCompatibleDevice:false},
  ]}
  vi.stubGlobal('fetch',vi.fn(async(input:string,init:RequestInit={})=>{
    const path=input.replace('/api/',''),method=init.method||'GET'
    if(path==='public/csrf')return response({token:'csrf'})
    if(path==='public/session')return response({creatorId:'wallet-owner'})
    if(path==='app/credits')return response({enabled:true,balance:100,musicCost:5})
    if(path==='app/stripe/catalog')return response(catalog)
    if(path==='app/paypal/catalog'||path==='app/payments/catalog')return response({enabled:false,packages:[]})
    if(path==='app/stripe/orders'&&method==='POST'){
      submitted.push(JSON.parse(String(init.body)))
      // The real URL guard rejects this; assertions cover pre-redirect order creation and recovery.
      return response({orderId,checkoutUrl:'https://untrusted.example/checkout'})
    }
    if(path==='app/stripe/orders'&&method==='GET')return response({orders:pending?[{orderId,status:pending,credits:300,createdAt:'2026-11-13T00:00:00Z'}]:oldPaid?[{orderId,status:'PAID',credits:300,createdAt:'2026-11-13T00:00:00Z',amount:178.8,currency:'CNY',paymentMethod:'wechat_pay'}]:[]})
    if(path.endsWith('/orders'))return response({orders:[]})
    if(path===`app/stripe/orders/${orderId}`)return response({orderId,status:pending||'PAID',credits:300})
    throw new Error('Unexpected wallet request '+method+' '+path)
  }))
})
afterEach(()=>{app?.unmount();app=undefined;vi.unstubAllGlobals();vi.clearAllMocks();resetCreatorSession()})
async function mount(component:typeof RechargePage|typeof CreditsPanel=RechargePage){
  const root=document.createElement('div');app=createApp(component)
  app.component('RouterLink',defineComponent({props:['to'],setup(props,{slots}){return()=>h('a',{href:props.to},slots.default?.())}}))
  app.mount(root);await drain();return root
}
async function choose(root:HTMLElement,method:string){root.querySelector<HTMLButtonElement>(`[data-method="${method}"]`)!.click();await nextTick()}

describe('wallet choices in the actual recharge component',()=>{
  it('shows the three Stripe choices without creating an order on display',async()=>{
    const root=await mount()
    for(const method of ['card','apple_pay','wechat_pay'])expect(root.querySelector(`[data-method="${method}"]`)).not.toBeNull()
    expect(root.querySelector('[data-method="card"]')?.getAttribute('aria-pressed')).toBe('true')
    expect(root.querySelector('.summary-total')?.textContent).toContain('$25.90')
    expect(submitted).toEqual([])
  })
  it('shows approved CNY quotes from the server and keeps unsupported WeChat USD disabled',async()=>{
    const root=await mount();await choose(root,'wechat_pay')
    expect([...root.querySelectorAll('.plan-price')].map(node=>node.textContent)).toEqual(['¥68.80','¥178.80','¥338.80'])
    expect(root.querySelector<HTMLButtonElement>('[data-currency="USD"]')?.disabled).toBe(true)
    expect(root.querySelector('[data-currency="CNY"]')?.getAttribute('aria-pressed')).toBe('true')
    expect(root.querySelector('[data-testid="wechat-usd-unavailable"]')?.textContent).toContain('不支持微信美元付款')
    expect(root.querySelector('.summary-total')?.textContent).toContain('¥178.80')
    expect(submitted).toEqual([])
    await choose(root,'card')
    expect(root.querySelector('.summary-total')?.textContent).toContain('$25.90')
  })
  it('posts the selected WeChat offer and method exactly once, with no client amount or currency',async()=>{
    const root=await mount();await choose(root,'wechat_pay')
    const pay=root.querySelector<HTMLButtonElement>('button.pay-button')!;pay.click();pay.click();await drain()
    expect(submitted).toEqual([{packageId:'standard-CNY-wechat_pay',paymentMethod:'wechat_pay'}])
    expect(readPendingOrders(localStorage,'wallet-owner','stripe')).toEqual([orderId])
    expect(pay.disabled).toBe(true)
  })
  it('shows the 35-credit offer only for WeChat CNY and submits its server offer ID', async () => {
    catalog.paymentMethods![2]!.packages = [mini, ...cny]
    const root = await mount()
    await choose(root, 'wechat_pay')
    expect([...root.querySelectorAll('.plan-price')].map(node => node.textContent))
      .toEqual(['¥29.90', '¥68.80', '¥178.80', '¥338.80'])
    root.querySelector<HTMLButtonElement>('.plan')!.click()
    await nextTick()
    expect(root.querySelector('.summary-total')?.textContent).toContain('¥29.90')
    await choose(root, 'card')
    expect(root.querySelector('.summary-total')?.textContent).toContain('$25.90')
    expect(root.querySelectorAll('.plan')).toHaveLength(3)
    await choose(root, 'wechat_pay')
    root.querySelector<HTMLButtonElement>('.plan')!.click()
    await nextTick()
    root.querySelector<HTMLButtonElement>('button.pay-button')!.click()
    await drain()
    expect(submitted).toEqual([{ packageId: 'mini-CNY-wechat_pay', paymentMethod: 'wechat_pay' }])
  })
  it('offers Apple Pay honestly as a hosted card wallet and sends the choice',async()=>{
    const root=await mount();await choose(root,'apple_pay')
    expect(root.querySelector('[data-testid="apple-pay-compatibility"]')?.textContent).toContain('仅在兼容设备且钱包可用时显示')
    expect(root.querySelector('.summary-total')?.textContent).toContain('$25.90')
    root.querySelector<HTMLButtonElement>('button.pay-button')!.click();await drain()
    expect(submitted).toEqual([{packageId:'standard-USD',paymentMethod:'apple_pay'}])
  })
  it('previews a disabled WeChat method without creating a payment',async()=>{
    catalog.paymentMethods![2]!.enabled=false;catalog.paymentMethods![2]!.reason='METHOD_DISABLED'
    const root=await mount();await choose(root,'wechat_pay')
    expect(root.textContent).toContain('待开通')
    expect(root.querySelector('.summary-total')?.textContent).toContain('¥178.80')
    const button=root.querySelector<HTMLButtonElement>('button.pay-button')!;expect(button.disabled).toBe(true);button.click();await drain()
    expect(submitted).toEqual([])
  })
  it('does not invent a price when the server has no quote for the chosen tier',async()=>{
    catalog.paymentMethods![2]!.packages=[cny[0]!]
    const root=await mount();await choose(root,'wechat_pay')
    expect(root.querySelector('.summary-total')?.textContent).toContain('—')
    expect(root.querySelector('.summary-total')?.textContent).not.toContain('178.80')
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    expect(root.querySelectorAll<HTMLButtonElement>('.plan')[1]?.disabled).toBe(true)
  })
  it('does not claim Apple Pay or WeChat availability for an old catalog',async()=>{
    delete catalog.paymentMethods
    const root=await mount();await choose(root,'wechat_pay')
    expect(root.textContent).toContain('尚未确认此支付方式可用')
    expect(root.querySelector('.summary-total')?.textContent).toContain('—')
    expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
    expect(submitted).toEqual([])
  })
  it.each(['PENDING','CREATION_UNKNOWN'])('retains the cross-method purchase block for unresolved state %s',async status=>{
    pending=status
    const root=await mount();await choose(root,'wechat_pay')
    const button=root.querySelector<HTMLButtonElement>('button.pay-button')!;expect(button.disabled).toBe(true);button.click();await drain()
    expect(submitted).toEqual([])
    expect(root.textContent).toContain('有订单需要核对')
  })
  it('displays a paid CNY history snapshot without reusing the current USD catalog price',async()=>{
    oldPaid=true
    const root=await mount(CreditsPanel)
    expect(root.querySelector('.history .order-amount')?.textContent).toContain('CNY ¥178.80')
    expect(root.querySelector('.history .order-amount')?.textContent).toContain('微信支付')
    expect(root.querySelector('.recharge-link')?.getAttribute('href')).toBe('/recharge')
    expect(submitted).toEqual([])
  })

  it('opens the actual recharge page from the account without loading packages or creating an order there', async () => {
    const previousLocale = locale.value
    setLocale('zh-CN')
    try {
      const router = createRouter({
        history: createMemoryHistory(),
        routes: [{ path: '/account', component: CreditsPanel }, { path: '/recharge', component: RechargePage }],
      })
      await router.push('/account')
      const root = document.createElement('div')
      app = createApp({ render: () => h(RouterView) }).use(router)
      app.mount(root)
      await drain()

      const link = root.querySelector<HTMLAnchorElement>('a.recharge-link')!
      expect(link.textContent?.trim()).toBe('充值积分')
      setLocale('en')
      await nextTick()
      expect(link.textContent?.trim()).toBe('Recharge credits')
      setLocale('zh-CN')
      await nextTick()
      expect(root.querySelector('.packages')).toBeNull()
      expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).endsWith('/catalog'))).toBe(false)

      link.click()
      await drain()
      expect(router.currentRoute.value.path).toBe('/recharge')
      expect(root.querySelector('h1')?.textContent).toBe('充值创作积分')
      expect(root.querySelector('[data-method="card"]')).not.toBeNull()
      expect(vi.mocked(fetch).mock.calls.some(([url, options]) =>
        String(url).endsWith('/orders') && options?.method === 'POST')).toBe(false)
      expect(submitted).toEqual([])
    } finally {
      setLocale(previousLocale)
    }
  })
})
