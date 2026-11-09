// @vitest-environment jsdom
import {beforeEach,expect,it,vi} from 'vitest'
import {createApp,nextTick} from 'vue'
import CreditsPanel from '../src/modules/identity/components/CreditsPanel.vue'
import {savePendingOrders} from '../src/modules/identity/model/payment-orders'
const state=vi.hoisted(()=>({status:'PENDING',reject:false,calls:[] as string[]}))
vi.mock('../src/shared/i18n',async()=>({locale:(await import('vue')).ref('zh'),t:(v:string)=>v}))
vi.mock('../src/shared/identity/account-state',async()=>({accountState:(await import('vue')).ref({authenticated:true})}))
vi.mock('../src/shared/identity/logto-state',async()=>({logtoState:(await import('vue')).ref({authenticated:false})}))
vi.mock('../src/shared/http/api-client',()=>({currentCreatorId:()=> 'owner',ensureCreator:vi.fn(),requestJson:vi.fn(async(path:string,method='GET')=>{
 state.calls.push(method+' '+path)
 const order={orderId:'11111111-1111-4111-8111-111111111111',status:state.status,credits:300,createdAt:'2026-11-09T05:00:00Z'}
 if(path==='app/paypal/orders')return {orders:[order]}
 if(path==='app/payments/orders')return {orders:[]}
 if(path.endsWith('/close')){if(state.reject)throw new Error('approved');state.status='CANCELLED';return {...order,status:state.status}}
 if(path==='app/paypal/orders/11111111-1111-4111-8111-111111111111')return order
 if(path==='app/credits')return {enabled:true,balance:0,musicCost:15}
 if(path.endsWith('/catalog'))return {enabled:false,packages:[]}
 throw new Error(path)
})}))
const settle=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
beforeEach(()=>{localStorage.clear();state.status='PENDING';state.reject=false;state.calls=[]})
async function mount(){const root=document.createElement('div'),app=createApp(CreditsPanel);app.mount(root);await settle();return{root,app}}
it('anchors a visible order card and cancels only through server confirmation',async()=>{
 const {root,app}=await mount();expect(root.querySelector('#payment-orders')).not.toBeNull();expect(root.textContent).toContain('我的订单')
 const cancel=root.querySelector<HTMLButtonElement>('.cancel-order')!;expect(cancel.textContent).toContain('取消订单');cancel.click();await settle();await settle()
 expect(root.textContent).toContain('已取消支付');expect(state.calls).toContain('POST app/paypal/orders/11111111-1111-4111-8111-111111111111/close');expect(state.calls.some(v=>v.includes('/capture'))).toBe(false);app.unmount()
})
it('keeps cancel visible alongside approved recovery and preserves a refused order',async()=>{
 savePendingOrders(localStorage,'owner',['11111111-1111-4111-8111-111111111111'],'paypal-approved');state.reject=true
 const {root,app}=await mount();expect(root.textContent).toContain('重新确认支付');root.querySelector<HTMLButtonElement>('.cancel-order')!.click();await settle();await settle()
 expect(root.textContent).toContain('取消未成功');expect(state.status).toBe('PENDING');expect(state.calls.some(v=>v.includes('/capture'))).toBe(false);app.unmount()
})
