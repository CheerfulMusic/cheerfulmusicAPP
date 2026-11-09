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
  trackPageView('home')
  beacon.mockClear()
  reportPageLeave()
  reportPageLeave()
  expect(beacon).toHaveBeenCalledTimes(1)
 })
 it('页面关闭时补发一次停留时长',async()=>{
  trackPageView('home')
  beacon.mockClear()
  installPageLeaveTracking()
  listeners['pagehide']?.forEach(fn=>fn())
  listeners['pagehide']?.forEach(fn=>fn())
  const events=await sent()
  expect(events).toHaveLength(1)
  expect(events[0].event).toBe('page_leave')
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
})
