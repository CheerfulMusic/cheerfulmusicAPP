import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {installPageLeaveTracking,reportPageLeave,trackPageView} from '../src/shared/analytics/track'

function memoryStorage(){
 const map=new Map<string,string>()
 return {getItem:(k:string)=>map.get(k)??null,setItem:(k:string,v:string)=>{map.set(k,v)},removeItem:(k:string)=>{map.delete(k)},clear:()=>map.clear()}
}
let beacon:ReturnType<typeof vi.fn>
let listeners:Record<string,Array<()=>void>>
beforeEach(()=>{
 vi.stubGlobal('localStorage',memoryStorage())
 vi.stubGlobal('sessionStorage',memoryStorage())
 vi.stubGlobal('location',{pathname:'/'})
 vi.stubGlobal('crypto',{randomUUID:()=>'fixed-uuid'})
 listeners={}
 const docListeners=listeners
 vi.stubGlobal('document',{referrer:'',visibilityState:'visible',addEventListener:(type:string,fn:()=>void)=>{(docListeners[type]??=[]).push(fn)}})
 vi.stubGlobal('window',{addEventListener:(type:string,fn:()=>void)=>{(docListeners[type]??=[]).push(fn)}})
 beacon=vi.fn(()=>true)
 vi.stubGlobal('navigator',{sendBeacon:beacon,doNotTrack:'0'})
 vi.stubGlobal('fetch',vi.fn(async()=>({ok:true})))
 trackPageView('start')
 beacon.mockClear()
})
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals()})
const sent=async()=>Promise.all(beacon.mock.calls.map(async call=>JSON.parse(await (call[1] as Blob).text())))

describe('页面停留时长',()=>{
 it('路由切换时先上报上一页停留，再上报新页浏览',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  trackPageView('home')
  beacon.mockClear()
  vi.setSystemTime(new Date('2026-01-01T00:00:05Z'))
  trackPageView('works')
  const events=await sent()
  expect(events).toHaveLength(2)
  expect(events[0]).toMatchObject({event:'page_leave',payload:{route:'home',durationMs:5000}})
  expect(events[1]).toMatchObject({event:'pageview',payload:{route:'works'}})
  expect(events[0].pagePath).toBe('/')
 })
 it('同一段停留只上报一次',()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  trackPageView('home')
  beacon.mockClear()
  vi.setSystemTime(new Date('2026-01-01T00:00:01Z'))
  reportPageLeave()
  reportPageLeave()
  expect(beacon).toHaveBeenCalledTimes(1)
 })
 it('页面关闭时补发一次停留时长',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  trackPageView('home')
  installPageLeaveTracking()
  beacon.mockClear()
  vi.setSystemTime(new Date('2026-01-01T00:00:02Z'))
  listeners['pagehide']?.forEach(fn=>fn())
  listeners['pagehide']?.forEach(fn=>fn())
  const events=await sent()
  expect(events).toHaveLength(1)
  expect(events[0]).toMatchObject({event:'page_leave',pagePath:'/',payload:{durationMs:2000}})
 })
 it('超长停留被钳制在 24 小时',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  trackPageView('home')
  beacon.mockClear()
  vi.setSystemTime(new Date('2026-01-03T00:00:00Z'))
  reportPageLeave()
  const events=await sent()
  expect(events[0].payload.durationMs).toBe(86_400_000)
 })
 it('停留时长算在进入页面时的地址上，不会被后续路由带走',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  ;(location as unknown as {pathname:string}).pathname='/works'
  trackPageView('works')
  beacon.mockClear()
  vi.setSystemTime(new Date('2026-01-01T00:00:08Z'))
  ;(location as unknown as {pathname:string}).pathname='/account'
  reportPageLeave()
  const events=await sent()
  expect(events).toHaveLength(1)
  expect(events[0]).toMatchObject({event:'page_leave',pagePath:'/works',payload:{route:'works',durationMs:8000}})
 })
 it('切到后台暂停计时，回来后继续累加，整页只发一条',async()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-01-01T00:00:00Z'))
  trackPageView('home')
  installPageLeaveTracking()
  beacon.mockClear()
  const doc=document as unknown as {visibilityState:string}
  vi.setSystemTime(new Date('2026-01-01T00:00:03Z'))
  doc.visibilityState='hidden';listeners['visibilitychange']?.forEach(fn=>fn())
  vi.setSystemTime(new Date('2026-01-01T00:01:00Z'))
  doc.visibilityState='visible';listeners['visibilitychange']?.forEach(fn=>fn())
  vi.setSystemTime(new Date('2026-01-01T00:01:05Z'))
  reportPageLeave()
  const events=await sent()
  expect(events).toHaveLength(1)
  expect(events[0].payload.durationMs).toBe(8000)
 })
})
