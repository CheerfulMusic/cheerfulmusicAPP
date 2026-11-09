// @vitest-environment jsdom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick,type App} from 'vue'
import RechargePage from '../src/modules/identity/pages/RechargePage.vue'
import CreditsPanel from '../src/modules/identity/components/CreditsPanel.vue'
import {resetCreatorSession} from '../src/shared/http/api-client'
import {readPendingOrders,savePendingOrders} from '../src/modules/identity/model/payment-orders'

vi.mock('../src/shared/identity/account-state',async()=>({accountState:(await import('vue')).ref({authenticated:true}),refreshAccountState:vi.fn(async()=>{})}))
vi.mock('../src/shared/identity/logto-state',async()=>({logtoState:(await import('vue')).ref({authenticated:false}),refreshLogtoState:vi.fn(async()=>{})}))
const id='11111111-1111-4111-8111-111111111111'
let status:string,historyFails:boolean,calls:string[],app:App|undefined
const reply=(value:unknown)=>new Response(JSON.stringify(value),{status:200})
const drain=async()=>{for(let i=0;i<6;i++){await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}}
beforeEach(()=>{
 resetCreatorSession();localStorage.clear();sessionStorage.clear();status='CANCELLED';historyFails=false;calls=[]
 vi.stubGlobal('fetch',vi.fn(async(input:string,init:RequestInit={})=>{
  const path=input.replace('/api/',''),method=init.method||'GET';calls.push(method+' '+path)
  const order={orderId:id,status,credits:300,createdAt:'2026-11-09T05:00:00Z'}
  if(path==='public/csrf')return reply({token:'csrf'})
  if(path==='public/session')return reply({creatorId:'owner'})
  if(path==='app/credits')return reply({enabled:true,balance:0,musicCost:15})
  if(path==='app/paypal/catalog')return reply({enabled:true,packages:[{id:'standard-USD',title:'300 Credits',credits:300,amount:12.99,currency:'USD'}]})
  if(path==='app/payments/catalog')return reply({enabled:false,packages:[]})
  if(path==='app/paypal/sdk-config')return reply({enabled:false,clientId:'',environment:'production'})
  if(path==='app/payments/orders')return reply({orders:[]})
  if(path==='app/paypal/orders'){if(historyFails)throw new TypeError('offline');return reply({orders:[order]})}
  if(path===`app/paypal/orders/${id}/close`){status='CANCELLED';return reply({...order,status})}
  if(path===`app/paypal/orders/${id}`)return reply(order)
  throw new Error('Unexpected request: '+method+' '+path)
 }))
})
afterEach(()=>{app?.unmount();app=undefined;vi.unstubAllGlobals();resetCreatorSession()})
async function mount(component:typeof RechargePage|typeof CreditsPanel){
 const root=document.createElement('div');app=createApp(component)
 app.component('RouterLink',defineComponent({props:['to'],setup(props,{slots}){return()=>h('a',{href:props.to},slots.default?.())}}))
 app.mount(root);await drain();return root
}

it('uses the real HTTP decoder and enables recharge for server-confirmed cancellation',async()=>{
 const root=await mount(RechargePage)
 expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(false)
 expect(root.textContent).not.toContain('有订单需要核对')
 expect(calls.some(c=>c==='POST app/paypal/orders'||c.includes('/capture'))).toBe(false)
})
it('cancels through the real decoder, reloads history and removes terminal recovery hints',async()=>{
 status='PENDING';savePendingOrders(localStorage,'owner',[id],'paypal');savePendingOrders(localStorage,'owner',[id],'paypal-approved')
 const root=await mount(CreditsPanel)
 root.querySelector<HTMLButtonElement>('.cancel-order')!.click();await drain()
 expect(status).toBe('CANCELLED');expect(root.textContent).toContain('已取消支付')
 expect(root.textContent).not.toContain('暂时无法核对全部订单')
 expect(root.querySelector('.cancel-order')).toBeNull()
 expect(readPendingOrders(localStorage,'owner','paypal')).toEqual([])
 expect(readPendingOrders(localStorage,'owner','paypal-approved')).toEqual([])
 expect(calls).toContain(`POST app/paypal/orders/${id}/close`)
 expect(calls.some(c=>c.includes('/capture'))).toBe(false)
 app!.unmount();app=undefined
 const recharge=await mount(RechargePage)
 expect(recharge.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(false)
 expect(recharge.textContent).not.toContain('有订单需要核对')
})
it.each(['PENDING','CREATING','UNKNOWN','UNRECOGNIZED'])('keeps recharge blocked for unresolved or invalid status %s',async value=>{
 status=value;const root=await mount(RechargePage)
 expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
 expect(calls).not.toContain('POST app/paypal/orders')
})
it('keeps recharge blocked when order history cannot be read',async()=>{
 historyFails=true;const root=await mount(RechargePage)
 expect(root.querySelector<HTMLButtonElement>('button.pay-button')?.disabled).toBe(true)
})
