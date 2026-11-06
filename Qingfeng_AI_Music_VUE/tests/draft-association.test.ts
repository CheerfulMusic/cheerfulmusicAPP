import { afterEach, expect, it, vi } from 'vitest'
import { associatedGuestCreatorId, currentCreatorId, ensureCreator, resetCreatorSession } from '../src/shared/http/api-client'
import { createRecoveryStore, readRecovery, recoveryKey } from '../src/shared/drafts/recovery'
afterEach(()=>{resetCreatorSession();vi.unstubAllGlobals()})
function storage(){const values=new Map<string,string>();vi.stubGlobal('localStorage',{
 get length(){return values.size},key:(n:number)=>[...values.keys()][n]||null,getItem:(key:string)=>values.get(key)||null,
 setItem:(key:string,value:string)=>values.set(key,value),removeItem:(key:string)=>values.delete(key),
});return values}
const valid=(value:unknown):value is {text:string}=>!!value&&typeof (value as {text?:unknown}).text==='string'
function server(associated:boolean){vi.stubGlobal('fetch',vi.fn(async (url:string)=>new Response(JSON.stringify(
 url.endsWith('/csrf')?{token:'csrf'}:associated?{creatorId:'account',associatedGuestCreatorId:'guest'}:{creatorId:'account'}),{status:200})))}
it('restores drafts after redirect using only the association confirmed by the session endpoint',async()=>{
 const values=storage();server(true)
 createRecoveryStore('old-document').write(recoveryKey('guest','story','draft'),{text:'未保存故事',dirty:true})
 await ensureCreator();expect(currentCreatorId()).toBe('account');expect(associatedGuestCreatorId()).toBe('guest')
 expect(readRecovery(recoveryKey('account','story','draft'),valid)?.text).toBe('未保存故事')
 const count=values.size;resetCreatorSession();await ensureCreator();expect(values.size).toBe(count)
})
it('does not move guest drafts merely because another account appears in the browser',async()=>{
 storage();server(false)
 createRecoveryStore('old').write(recoveryKey('guest','story','draft'),{text:'私人故事',dirty:true})
 await ensureCreator();expect(associatedGuestCreatorId()).toBe('')
 expect(readRecovery(recoveryKey('account','story','draft'),valid)).toBeUndefined()
})
it('reports a failed migration and retries after storage recovers without losing the original draft',async()=>{
 storage();server(true)
 createRecoveryStore('old').write(recoveryKey('guest','story','draft'),{text:'保留故事',dirty:true})
 const set=localStorage.setItem.bind(localStorage);localStorage.setItem=()=>{throw new Error('quota')}
 await expect(ensureCreator()).rejects.toMatchObject({code:'LOCAL_DRAFT_MIGRATION_FAILED'})
 expect(currentCreatorId()).toBe('');expect(readRecovery(recoveryKey('guest','story','draft'),valid)?.text).toBe('保留故事')
 localStorage.setItem=set;await ensureCreator()
 expect(readRecovery(recoveryKey('account','story','draft'),valid)?.text).toBe('保留故事')
})
