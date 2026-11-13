// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import CreditsPanel from '../src/modules/identity/components/CreditsPanel.vue'
import { resetCreatorSession } from '../src/shared/http/api-client'
import { announceCreditsChanged } from '../src/shared/identity/credit-balance'
vi.mock('../src/shared/identity/account-state',async()=>({accountState:(await import('vue')).ref({authenticated:true})}))
vi.mock('../src/shared/identity/logto-state',async()=>({logtoState:(await import('vue')).ref({authenticated:false})}))
vi.mock('../src/shared/identity/credit-balance',()=>({announceCreditsChanged:vi.fn()}))
const id='44444444-4444-4444-8444-444444444444'
let app:App|undefined, state:string, result:string, calls:string[], fail:boolean
const response=(v:unknown)=>new Response(JSON.stringify(v),{status:200})
const drain=async()=>{for(let i=0;i<10;i++){await new Promise(r=>setTimeout(r,0));await nextTick()}}
beforeEach(()=>{
  resetCreatorSession();localStorage.clear();sessionStorage.clear();state='PENDING';result='CANCELLED';calls=[];fail=false
  vi.spyOn(window,'confirm').mockReturnValue(true)
  vi.stubGlobal('fetch',vi.fn(async(input:string,init:RequestInit={})=>{
    const path=input.replace('/api/',''),method=init.method||'GET';calls.push(`${method} ${path}`)
    if(path==='public/csrf')return response({token:'csrf'})
    if(path==='public/session')return response({creatorId:'cancel-owner'})
    if(path==='app/credits')return response({enabled:true,balance:1,musicCost:5})
    if(path.endsWith('/catalog'))return response({enabled:false,packages:[]})
    const order={orderId:id,status:state,createdAt:'2026-11-13T00:00:00Z',credits:1,amount:0.5,currency:'USD'}
    if(path===`app/stripe/orders/${id}/cancel`){if(fail)throw new Error('offline');state=result;return response({...order,status:state})}
    if(path===`app/stripe/orders/${id}`)return response(order)
    if(path.endsWith('/orders'))return response({orders:path==='app/stripe/orders'?[order]:[]})
    throw new Error('Unexpected request '+path)
  }))
})
afterEach(()=>{app?.unmount();app=undefined;vi.restoreAllMocks();vi.unstubAllGlobals();vi.clearAllMocks();resetCreatorSession()})
async function mount(){const root=document.createElement('div');app=createApp(CreditsPanel);app.component('RouterLink',defineComponent({setup(_, {slots}){return()=>h('a',slots.default?.())}}));app.mount(root);await drain();return root}
it('cancels via server and retains the cancelled order in history',async()=>{const root=await mount();root.querySelector<HTMLButtonElement>('.cancel-order')!.click();await drain();expect(calls).toContain(`POST app/stripe/orders/${id}/cancel`);expect(root.textContent).toContain('已取消支付');expect(root.textContent).toContain('原支付链接已关闭');expect(root.querySelector('.cancel-order')).toBeNull()})
it('does nothing when user declines cancellation',async()=>{const root=await mount();vi.mocked(window.confirm).mockReturnValue(false);root.querySelector<HTMLButtonElement>('.cancel-order')!.click();await drain();expect(calls.filter(c=>c.endsWith('/cancel'))).toHaveLength(0);expect(state).toBe('PENDING')})
it('shows paid outcome instead of falsely reporting cancellation',async()=>{result='PAID';const root=await mount();root.querySelector<HTMLButtonElement>('.cancel-order')!.click();await drain();expect(root.textContent).toContain('订单已付款，不能取消');expect(announceCreditsChanged).toHaveBeenCalled();expect(root.querySelector('.cancel-order')).toBeNull()})
it('keeps unknown outcomes pending and permits a later retry',async()=>{fail=true;const root=await mount();root.querySelector<HTMLButtonElement>('.cancel-order')!.click();await drain();expect(root.textContent).toContain('取消未成功');expect(root.textContent).toContain('订单等待支付确认');expect(root.querySelector<HTMLButtonElement>('.cancel-order')!.disabled).toBe(false)})
it.each(['PAID','CANCELLED','CREATING','CREATION_UNKNOWN'])('does not offer direct cancellation for %s',async status=>{state=status;const root=await mount();expect(root.querySelector('.cancel-order')).toBeNull()})
