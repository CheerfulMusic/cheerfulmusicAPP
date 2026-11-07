import {expect,it} from 'vitest'
import {needsGenerationRefresh,rememberGeneration,recoverGeneration,forgetGeneration,uncertainSubmission,generationProblem} from '../src/modules/works/model/generation-recovery'
it('keeps checking unknown submissions and lost HTTP responses without requiring another live task',()=>{
 expect(needsGenerationRefresh([{status:'SUBMISSION_UNKNOWN'}])).toBe(true)
 expect(needsGenerationRefresh([],true)).toBe(true)
 for(const status of ['QUEUED','SUBMITTING','RUNNING','SAVING'])expect(needsGenerationRefresh([{status}])).toBe(true)
})
it('restores an ambiguous request after reload and isolates creators and works',()=>{
 const data=new Map<string,string>(),storage={getItem:(key:string)=>data.get(key)||null,setItem:(key:string,value:string)=>{data.set(key,value)},removeItem:(key:string)=>{data.delete(key)}}
 const pending={workId:'work',revisionId:'original-revision',requestKey:'12345678-1234-1234-1234-123456789abc',createdAt:100}
 expect(rememberGeneration('owner',pending,storage)).toBe(true)
 expect(recoverGeneration('owner','work',storage)).toEqual(pending)
 expect(recoverGeneration('other-owner','work',storage)).toBeUndefined()
 expect(recoverGeneration('owner','other-work',storage)).toBeUndefined()
 expect([...data.values()][0]).not.toContain('lyrics')
 forgetGeneration('owner','work',storage);expect(recoverGeneration('owner','work',storage)).toBeUndefined()
})
it('storage failures and malformed entries do not invent another request key',()=>{
 const blocked={getItem(){throw Error('blocked')},setItem(){throw Error('blocked')},removeItem(){throw Error('blocked')}}
 const pending={workId:'w',revisionId:'r',requestKey:'12345678-1234-1234-1234-123456789abc',createdAt:100}
 expect(rememberGeneration('owner',pending,blocked)).toBe(false)
 expect(recoverGeneration('owner','w',blocked)).toBeUndefined();expect(()=>forgetGeneration('owner','w',blocked)).not.toThrow()
 expect(recoverGeneration('owner','w',{getItem:()=>'{invalid'})).toBeUndefined()
 expect(recoverGeneration('owner','w',{getItem:()=>JSON.stringify({...pending,requestKey:'bad'})})).toBeUndefined()
})
it('a coded server error is still ambiguous after POST while an explicit input rejection is not',()=>{
 for(const status of [0,408,500,502,503])expect(uncertainSubmission({status,code:'SERVER_ERROR'})).toBe(true)
 for(const status of [400,401,403,409,422,429])expect(uncertainSubmission({status,code:'INVALID_MUSIC_SETTINGS'})).toBe(false)
 expect(uncertainSubmission({})).toBe(true)
 expect(uncertainSubmission({status:503,code:'MUSIC_PROVIDER_COOLDOWN_UNAVAILABLE'})).toBe(false)
 expect(uncertainSubmission({status:503,code:'MUSIC_PROVIDER_UNAVAILABLE'})).toBe(false)
 expect(generationProblem('AUDIO_ARCHIVE_FAILED')).toContain('无需重新生成')
 expect(generationProblem('QUERY_REVIEW_REQUIRED')).toContain('原任务')
 expect(generationProblem('PROVIDER_DISPATCH_UNAVAILABLE')).toContain('尚未提交')
})
it('stops automatic reads after all tasks finish or checking is explicitly closed',()=>{
 expect(needsGenerationRefresh([])).toBe(false)
 expect(needsGenerationRefresh([{status:'FAILED'},{status:'SUCCEEDED'},{status:'CANCELLED'}])).toBe(false)
})
