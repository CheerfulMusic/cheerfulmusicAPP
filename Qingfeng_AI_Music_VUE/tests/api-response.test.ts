import {afterEach,describe,it,expect,vi} from 'vitest'
import {requestJson,uploadForm,resetCreatorSession} from '../src/shared/http/api-client'
afterEach(()=>{vi.unstubAllGlobals();resetCreatorSession()})
const reply=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status})
describe('R1 响应读取与业务结果校验',()=>{
 it('PayPal SDK 配置必须注明环境，启用时必须有公开 client ID',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>reply({enabled:true,clientId:'',environment:'sandbox'})))
  await expect(requestJson('app/paypal/sdk-config')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  vi.stubGlobal('fetch',vi.fn(async()=>reply({enabled:false,clientId:'',environment:'sandbox'})))
  await expect(requestJson('app/paypal/sdk-config')).resolves.toMatchObject({enabled:false})
 })
 it('购买与积分响应缺少关键字段时不能视为成功',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>reply({enabled:true,packages:[{id:'a',title:'Starter',currency:'GBP',credits:100}]})))
  await expect(requestJson('app/payments/catalog')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  vi.stubGlobal('fetch',vi.fn(async()=>reply({orderId:'order'})))
  await expect(requestJson('app/payments/orders','POST',{packageId:'a'})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  vi.stubGlobal('fetch',vi.fn(async()=>reply({enabled:true,balance:10})))
  await expect(requestJson('app/credits')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 })
 it('账户订单列表需要明确状态和归属订单信息',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>reply({orders:[{orderId:'order',status:'PENDING',credits:100}]})))
  await expect(requestJson('app/payments/orders')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  vi.stubGlobal('fetch',vi.fn(async()=>reply({orders:[{orderId:'order',status:'PENDING',credits:100,createdAt:'2026-11-05T00:00:00Z'}]})))
  await expect(requestJson('app/payments/orders')).resolves.toMatchObject({orders:[{status:'PENDING'}]})
 })
 it('200响应体读取超时仍抛408，写入结果不能伪装成功',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(_url,init:RequestInit)=>({ok:true,status:200,text:()=>new Promise((_resolve,reject)=>init.signal!.addEventListener('abort',()=>reject(new DOMException('aborted','AbortError'))))})))
  await expect(requestJson('audit/body','POST',{},15)).rejects.toMatchObject({status:408})
 })
 it.each(['{broken','<html>proxy error</html>','', '{}','null'])('拒绝损坏或不完整的成功工作对象：%s',async raw=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(raw,{status:200})))
  await expect(requestJson('app/works/work','PUT',{})).rejects.toMatchObject({status:0,code:'INVALID_RESPONSE'})
 })
 it('缺少业务必需字段的语法有效JSON也不能清除草稿',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>reply({id:'w'})))
  await expect(requestJson('app/works','POST',{})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  await expect(requestJson('app/works/w/generations','POST',{})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 })
 it('200响应体连接中断被标记为未知，不能返回成功',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>({ok:true,status:200,text:async()=>{throw new TypeError('body interrupted')}})))
  await expect(requestJson('app/works','POST',{})).rejects.toMatchObject({status:0})
 })
 it('音乐设置必须有完整偏好，选项目录必须有显示和生成所需字段',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>reply({preferences:{},catalogVersion:1,style:'folk',negativeTags:'',summaryZh:'民谣',summaryEn:'Folk'})))
  await expect(requestJson('app/music-settings/preview','POST',{})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  vi.stubGlobal('fetch',vi.fn(async()=>reply({version:1,groups:[{id:'genre',options:[{id:'folk'}]}],recipes:[]})))
  await expect(requestJson('app/music-settings/catalog')).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 })
 it('上传体读取超时也保留未知语义',async()=>{
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>url.endsWith('/csrf')?reply({token:'token'}):url.endsWith('/session')?reply({creatorId:'creator'}):{ok:true,status:200,text:async()=>{throw new DOMException('timed out','TimeoutError')}}))
  await expect(uploadForm('app/works/w/audio',new FormData())).rejects.toMatchObject({status:408})
 })
 it('只有明确的空响应接口允许204/空200，其他204仍视为未知',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(null,{status:204})))
  await expect(requestJson('app/works/w/shares','DELETE')).resolves.toBeUndefined()
  await expect(requestJson('app/works/w/generations/t/retry-archive','POST',{})).resolves.toBeUndefined()
  await expect(requestJson('app/works/w','PUT',{})).rejects.toMatchObject({code:'INVALID_RESPONSE'})
 })
 it('错误页保留HTTP失败语义，不会返回空对象',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('<html>Bad Gateway</html>',{status:502})))
  await expect(requestJson('app/works','POST',{})).rejects.toMatchObject({status:502})
 })
 it('上传响应也校验必需字段且不自动重试',async()=>{
  const fetch=vi.fn(async(url:string)=>url.endsWith('/csrf')?reply({token:'token'}):url.endsWith('/session')?reply({creatorId:'creator'}):reply({}))
  vi.stubGlobal('fetch',fetch);await expect(uploadForm('app/works/w/audio',new FormData())).rejects.toMatchObject({code:'INVALID_RESPONSE'})
  expect(fetch).toHaveBeenCalledTimes(3)
 })
})
