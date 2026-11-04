import {expect,it} from 'vitest'
import {needsGenerationRefresh} from '../src/modules/works/model/generation-recovery'
it('keeps checking unknown submissions and lost HTTP responses without requiring another live task',()=>{
 expect(needsGenerationRefresh([{status:'SUBMISSION_UNKNOWN'}])).toBe(true)
 expect(needsGenerationRefresh([],true)).toBe(true)
 for(const status of ['QUEUED','SUBMITTING','RUNNING','SAVING'])expect(needsGenerationRefresh([{status}])).toBe(true)
})
it('stops automatic reads after all tasks finish or checking is explicitly closed',()=>{
 expect(needsGenerationRefresh([])).toBe(false)
 expect(needsGenerationRefresh([{status:'FAILED'},{status:'SUCCEEDED'},{status:'CANCELLED'}])).toBe(false)
})
