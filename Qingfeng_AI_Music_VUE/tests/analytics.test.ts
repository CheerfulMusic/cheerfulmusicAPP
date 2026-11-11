import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {analyticsEnabled,sessionId,setAnalyticsEnabled,sanitizePagePath,sanitizeReferrer,track,trackPageView,visitorId} from '../src/shared/analytics/track'

function memoryStorage(){
 const map=new Map<string,string>()
 return {getItem:(k:string)=>map.get(k)??null,setItem:(k:string,v:string)=>{map.set(k,v)},removeItem:(k:string)=>{map.delete(k)},clear:()=>map.clear()}
}
let beacon:ReturnType<typeof vi.fn>, fetchMock:ReturnType<typeof vi.fn>
beforeEach(()=>{
 vi.stubGlobal('localStorage',memoryStorage())
 vi.stubGlobal('sessionStorage',memoryStorage())
 vi.stubGlobal('location',{pathname:'/'})
 vi.stubGlobal('document',{referrer:''})
 vi.stubGlobal('crypto',{randomUUID:()=>'fixed-uuid'})
 beacon=vi.fn(()=>true)
 vi.stubGlobal('navigator',{sendBeacon:beacon,doNotTrack:'0'})
 fetchMock=vi.fn(async()=>({ok:true}))
 vi.stubGlobal('fetch',fetchMock)
})
afterEach(()=>{vi.unstubAllGlobals()})
const sentBody=async(index=0)=>JSON.parse(await (beacon.mock.calls[index][1] as Blob).text())

describe('埋点路径与来源脱敏',()=>{
 it('礼物 token 与作品 id 不进日志',()=>{
  expect(sanitizePagePath('/gift/'+'a'.repeat(43))).toBe('/gift/:token')
  expect(sanitizePagePath('/works/12345678-1234-1234-1234-123456789012')).toBe('/works/:id')
 })
 it('query 与 hash 一律丢弃，防止 OAuth code 入库',()=>{
  expect(sanitizePagePath('/account?code=SECRET&state=x#frag')).toBe('/account')
  expect(sanitizePagePath('https://evil.example/x')).toBe('')
 })
 it('来源只保留 origin 与路径',()=>{
  expect(sanitizeReferrer('https://www.google.com/search?q=secret&code=x')).toBe('https://www.google.com/search')
  expect(sanitizeReferrer('javascript:alert(1)')).toBe('')
  expect(sanitizeReferrer('')).toBe('')
 })
})

describe('匿名标识',()=>{
 it('访客与会话标识在有效期内复用',()=>{
  expect(visitorId()).toBe('fixed-uuid')
  expect(visitorId()).toBe('fixed-uuid')
  expect(sessionId()).toBe('fixed-uuid')
  expect(sessionId()).toBe('fixed-uuid')
 })
 it('会话过期后重建',()=>{
  sessionId()
  sessionStorage.setItem('qf.analytics.sid.at','0')
  expect(sessionId()).toBe('fixed-uuid')
  expect(Number(sessionStorage.getItem('qf.analytics.sid.at'))).toBeGreaterThan(0)
 })
})

describe('事件上报',()=>{
 it('未知事件名一律不发',()=>{
  track('drop_all_tables')
  expect(beacon).not.toHaveBeenCalled()
  expect(fetchMock).not.toHaveBeenCalled()
 })
 it('pageview 走 sendBeacon，携带脱敏字段与匿名标识',async()=>{
  setAnalyticsEnabled(true)
  vi.stubGlobal('location',{pathname:'/gift/'+'b'.repeat(43)})
  vi.stubGlobal('document',{referrer:'https://t.example/share?q=1'})
  trackPageView('gift')
  expect(beacon).toHaveBeenCalledTimes(1)
  expect(beacon.mock.calls[0][0]).toBe('/api/public/events')
  const sent=await sentBody()
  expect(sent.event).toBe('pageview')
  expect(sent.pagePath).toBe('/gift/:token')
  expect(sent.referrer).toBe('https://t.example/share')
  expect(sent.visitorId).toBe('fixed-uuid')
  expect(sent.payload).toEqual({route:'gift'})
 })
 it('sendBeacon 不可用时回退到 keepalive fetch',()=>{
  setAnalyticsEnabled(true)
  beacon.mockReturnValue(false)
  track('purchase',{plan:'standard'})
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock.mock.calls[0][0]).toBe('/api/public/events')
  expect(fetchMock.mock.calls[0][1]).toMatchObject({method:'POST',keepalive:true,credentials:'same-origin'})
 })
 it('显式关闭或 DNT 时不发任何请求',()=>{
  setAnalyticsEnabled(false)
  track('pageview')
  expect(beacon).not.toHaveBeenCalled()
  expect(analyticsEnabled()).toBe(false)
  setAnalyticsEnabled(true)
  vi.stubGlobal('navigator',{sendBeacon:beacon,doNotTrack:'1'})
  track('pageview')
  expect(beacon).not.toHaveBeenCalled()
 })
 it('存储不可用时访客标识保持稳定、关闭开关仍生效',()=>{
  const blocked={getItem:()=>{throw new Error('blocked')},setItem:()=>{throw new Error('blocked')},removeItem:()=>{throw new Error('blocked')}}
  let seq=0
  vi.stubGlobal('crypto',{randomUUID:()=>'id-'+(++seq)})
  vi.stubGlobal('localStorage',blocked)
  vi.stubGlobal('sessionStorage',blocked)
  const first=visitorId()
  expect(visitorId()).toBe(first)
  expect(sessionId()).toBe(sessionId())
  expect(()=>track('pageview')).not.toThrow()
  setAnalyticsEnabled(false)
  expect(analyticsEnabled()).toBe(false)
  setAnalyticsEnabled(true)
  expect(analyticsEnabled()).toBe(true)
 })
})
