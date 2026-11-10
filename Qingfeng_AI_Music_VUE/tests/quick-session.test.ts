// @vitest-environment jsdom
import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {createApp,type App} from 'vue'
import {useQuickSession} from '../src/modules/studio/model/use-quick-session'
import {recoverQuickIntent} from '../src/modules/studio/model/quick-intent'
import {ApiError} from '../src/shared/http/api-client'
const m=vi.hoisted(()=>({owner:'owner-a',latest:vi.fn(),start:vi.fn()}))
vi.mock('../src/shared/http/api-client',()=>({currentCreatorId:()=>m.owner,ApiError:class extends Error{constructor(message:string,public status:number,public code?:string){super(message)}}}))
vi.mock('../src/modules/studio/api/quick-creation',()=>({latestQuickCreation:m.latest,startQuickCreation:m.start}))
let app:App|undefined
function mount(){let session!:ReturnType<typeof useQuickSession>;app=createApp({setup(){session=useQuickSession();return()=>null}});app.mount(document.body.appendChild(document.createElement('div')));return session}
const accepted={id:'flow-a',workId:'work-a',status:'WRITING',revisionId:null,taskId:null,errorCode:null,canRetry:false,createdAt:new Date().toISOString()}
beforeEach(()=>{vi.clearAllMocks();localStorage.clear();sessionStorage.clear();m.owner='owner-a';m.latest.mockResolvedValue(null);m.start.mockResolvedValue(accepted)})
afterEach(()=>{app?.unmount();app=undefined;document.body.innerHTML=''})
it('opens preparation immediately, fixes the intent before posting and rejects a duplicate click',async()=>{
 const s=mount();s.prepare();expect(s.dialogOpen.value).toBe(true);expect(s.initialStatus.value).toBe('PREPARING');expect(s.locked.value).toBe(true)
 let finish!:(v:unknown)=>void;m.start.mockImplementationOnce(()=>new Promise(r=>finish=r))
 const pending=s.submit('work-a',2,'zh-CN','今天的歌')
 expect(s.submissionPending.value).toBe(true);expect(s.initialStatus.value).toBe('SUBMITTING_QUICK')
 expect(recoverQuickIntent('owner-a','work-a')?.workVersion).toBe(2)
 await s.submit('work-a',2,'zh-CN','changed');expect(m.start).toHaveBeenCalledTimes(1)
 finish(accepted);await pending
 expect(s.workId.value).toBe('work-a');expect(s.flow.value?.id).toBe('flow-a');expect(s.locked.value).toBe(true)
 expect(s.submissionPending.value).toBe(false);expect(s.dialogOpen.value).toBe(true)
})
it('retains an unknown submission through refresh and only queries on restore',async()=>{
 const s=mount();s.prepare();m.start.mockRejectedValueOnce(new ApiError('timeout',408));await s.submit('work-a',2,'en','Song')
 const key=s.intent.value!.requestKey;expect(s.unknown.value).toBe(true);expect(s.locked.value).toBe(true)
 s.reset();await s.restore('work-a','Song')
 expect(s.intent.value?.requestKey).toBe(key);expect(s.unknown.value).toBe(true);expect(m.start).toHaveBeenCalledTimes(1)
})
it('replays only the fixed request key after an explicit resume',async()=>{
 const s=mount();s.prepare();m.start.mockRejectedValueOnce(new ApiError('timeout',408));await s.submit('work-a',2,'mixed','Song')
 const original=m.start.mock.calls[0];await s.resume()
 expect(m.start.mock.calls[1]).toEqual(original);expect(s.flow.value?.id).toBe('flow-a')
})
it('finds an accepted task when resuming instead of submitting it again',async()=>{
 const s=mount();s.prepare();m.start.mockRejectedValueOnce(new ApiError('timeout',408));await s.submit('work-a',2,'zh-CN','歌')
 m.latest.mockResolvedValue(accepted);await s.resume()
 expect(m.start).toHaveBeenCalledTimes(1);expect(s.unknown.value).toBe(false);expect(s.flow.value?.id).toBe('flow-a')
})
it('unlocks input after an authoritative rejection and keeps no uncertain request',async()=>{
 const s=mount();s.prepare();m.start.mockRejectedValueOnce(new ApiError('credits',402,'INSUFFICIENT_CREDITS'))
 await expect(s.submit('work-a',2,'zh-CN','歌')).rejects.toThrow('credits')
 expect(s.locked.value).toBe(false);expect(s.dialogOpen.value).toBe(false);expect(recoverQuickIntent('owner-a','work-a')).toBeUndefined()
})
it('does not apply a late response after the user leaves for a different draft',async()=>{
 const s=mount();s.prepare();let finish!:(v:unknown)=>void;m.start.mockImplementationOnce(()=>new Promise(r=>finish=r))
 const pending=s.submit('work-a',2,'zh-CN','歌');s.reset();finish(accepted);await pending
 expect(s.workId.value).toBe('');expect(s.flow.value).toBeNull();expect(s.locked.value).toBe(false)
 expect(recoverQuickIntent('owner-a','work-a')).toBeDefined()
})
it('does not recover another account intent and preserves the minimized state',async()=>{
 const s=mount();s.prepare();s.close();await s.submit('work-a',2,'zh-CN','歌');s.reset();m.latest.mockResolvedValue(accepted);await s.restore('work-a','歌')
 expect(s.minimized.value).toBe(true);expect(s.dialogOpen.value).toBe(false)
 expect(recoverQuickIntent('owner-b','work-a')).toBeUndefined()
})
it('ignores a stale empty lookup while the submission is still being accepted',async()=>{
 const s=mount();s.prepare();let finish!:(v:unknown)=>void;m.start.mockImplementationOnce(()=>new Promise(r=>finish=r))
 const pending=s.submit('work-a',2,'zh-CN','歌');s.resolve(null)
 expect(s.unknown.value).toBe(false);finish(accepted);await pending;s.resolve(null)
 expect(s.flow.value?.id).toBe('flow-a');expect(s.unknown.value).toBe(false)
})
it('does not replace a confirmed task with a late submission timeout',async()=>{
 const s=mount();s.prepare();let fail!:(v:unknown)=>void;m.start.mockImplementationOnce(()=>new Promise((_,reject)=>fail=reject))
 const pending=s.submit('work-a',2,'zh-CN','歌');s.resolve(accepted);fail(new ApiError('timeout',503));await pending
 expect(s.flow.value?.id).toBe('flow-a');expect(s.unknown.value).toBe(false)
})
