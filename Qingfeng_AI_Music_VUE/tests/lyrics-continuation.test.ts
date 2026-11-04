import { afterEach, expect, it, vi } from 'vitest'
import { continuationRequestKey, retireContinuationRequestKey } from '../src/modules/studio/model/lyrics-continuation'
afterEach(()=>{vi.unstubAllGlobals();vi.resetModules()})
it('reuses the continuation request after reload while separating changed story versions and languages',async()=>{
  const storage=new Map<string,string>()
  vi.stubGlobal('sessionStorage',{getItem:(key:string)=>storage.get(key),setItem:(key:string,value:string)=>storage.set(key,value)})
  const first=await import('../src/modules/studio/model/lyrics-continuation')
  const key=first.continuationRequestKey('work-1',0,'zh-CN');vi.resetModules()
  const reloaded=await import('../src/modules/studio/model/lyrics-continuation')
  expect(reloaded.continuationRequestKey('work-1',0,'zh-CN')).toBe(key)
  expect(reloaded.continuationRequestKey('work-1',1,'zh-CN')).not.toBe(key)
  expect(reloaded.continuationRequestKey('work-1',0,'en')).not.toBe(key)
})
it('prevents repeated clicks within the page when browser storage is unavailable',async()=>{
  vi.stubGlobal('sessionStorage',{getItem(){throw new Error('blocked')},setItem(){throw new Error('blocked')}})
  const module=await import('../src/modules/studio/model/lyrics-continuation')
  expect(module.continuationRequestKey('work-2',0,'en')).toBe(module.continuationRequestKey('work-2',0,'en'))
})
it('only retires the failed work key while preserving other works and languages',()=>{
  const data=new Map<string,string>()
  vi.stubGlobal('sessionStorage',{getItem:(k:string)=>data.get(k)||null,setItem:(k:string,v:string)=>data.set(k,v),removeItem:(k:string)=>data.delete(k)})
  const work=crypto.randomUUID(),other=crypto.randomUUID()
  const first=continuationRequestKey(work,0,'zh-CN'),english=continuationRequestKey(work,0,'en'),second=continuationRequestKey(other,0,'zh-CN')
  expect(continuationRequestKey(work,0,'zh-CN')).toBe(first)
  retireContinuationRequestKey(work,0,'zh-CN')
  const retry=continuationRequestKey(work,0,'zh-CN');expect(retry).not.toBe(first);expect(continuationRequestKey(work,0,'zh-CN')).toBe(retry)
  expect(continuationRequestKey(other,0,'zh-CN')).toBe(second);expect(continuationRequestKey(work,0,'en')).toBe(english)
})
it('retains the new key for retries even when browser storage is unavailable',()=>{
  vi.stubGlobal('sessionStorage',{getItem(){throw new Error('blocked')},setItem(){throw new Error('blocked')},removeItem(){throw new Error('blocked')}})
  const work=crypto.randomUUID(),before=continuationRequestKey(work,1,'mixed');retireContinuationRequestKey(work,1,'mixed')
  const after=continuationRequestKey(work,1,'mixed');expect(after).not.toBe(before);expect(continuationRequestKey(work,1,'mixed')).toBe(after)
})
