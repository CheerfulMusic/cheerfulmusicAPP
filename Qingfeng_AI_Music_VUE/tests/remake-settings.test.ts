import {beforeEach,expect,it,vi} from 'vitest'
import {restoreRemakeSettings} from '../src/modules/works/model/remake-settings'
import type {Work} from '../src/modules/studio'
import type {GenerationTask} from '../src/modules/works/api/detail'
import type {MusicDirection,MusicPreferences} from '../src/modules/works/api/music-settings'
const api=vi.hoisted(()=>({snapshot:vi.fn(),load:vi.fn(),save:vi.fn()}))
vi.mock('../src/modules/works/api/music-settings',()=>({loadTaskMusicSettings:api.snapshot,loadMusicSettings:api.load,saveMusicSettings:api.save}))
const work={id:'work',version:2,style:'温暖民谣',vocalGender:'FEMALE'} as Work
const source={id:'original-task',style:'温暖民谣',vocalGender:'FEMALE'} as GenerationTask
const prefs:MusicPreferences={version:1,recipeId:null,choices:{genre:['folk'],instruments:['guitar'],emotion:['warm'],tempo:['slow']},vocalGender:'FEMALE',freeText:'保留木吉他与温柔的声音',negativeText:'不要电子鼓'}
const original={preferences:prefs,style:'acoustic folk',negativeTags:'electronic drums',summaryZh:'温暖民谣',summaryEn:'Warm folk',catalogVersion:1} satisfies MusicDirection
beforeEach(()=>{vi.clearAllMocks();api.snapshot.mockResolvedValue(original);api.load.mockResolvedValue({work,direction:original});api.save.mockResolvedValue({work:{...work,version:3},direction:original})})
it('reuses the selected audio task snapshot and does not write when its settings are unchanged',async()=>{
 await expect(restoreRemakeSettings(work,source,()=>true)).resolves.toBe(work);expect(api.snapshot).toHaveBeenCalledWith('work','original-task');expect(api.save).not.toHaveBeenCalled()
})
it('restores all original music preferences including voice, instruments, mood, tempo and exclusions',async()=>{
 api.load.mockResolvedValue({work:{...work,style:'摇滚'},direction:{...original,preferences:{...prefs,vocalGender:'MALE',choices:{genre:['rock']},negativeText:''}}})
 const changed=await restoreRemakeSettings(work,source,()=>true);expect(changed?.version).toBe(3);expect(api.save).toHaveBeenCalledExactlyOnceWith('work',2,prefs);expect(api.save.mock.calls[0]![2]).not.toBe(prefs)
})
it('ignores property ordering without changing the preference or option order',async()=>{
 api.load.mockResolvedValue({work,direction:{...original,preferences:{negativeText:prefs.negativeText,freeText:prefs.freeText,vocalGender:prefs.vocalGender,choices:{tempo:['slow'],emotion:['warm'],instruments:['guitar'],genre:['folk']},recipeId:null,version:1}}})
 await restoreRemakeSettings(work,source,()=>true);expect(api.save).not.toHaveBeenCalled()
})
it('does not overwrite a newer edit from another browser tab',async()=>{
 api.load.mockResolvedValue({work:{...work,version:3},direction:original});await expect(restoreRemakeSettings(work,source,()=>true)).rejects.toMatchObject({code:'WORK_VERSION_CONFLICT'});expect(api.save).not.toHaveBeenCalled()
})
it('does not write or continue when the selected audio changes while settings are loading',async()=>{
 await expect(restoreRemakeSettings(work,source,()=>false)).resolves.toBeNull();expect(api.save).not.toHaveBeenCalled()
})
it('supports a legacy task only when the recorded style and voice remain unchanged and no advanced direction exists',async()=>{
 api.snapshot.mockResolvedValue(null);api.load.mockResolvedValue({work,direction:null});await expect(restoreRemakeSettings(work,source,()=>true)).resolves.toBe(work);expect(api.save).not.toHaveBeenCalled()
})
it('stops instead of guessing missing original options or using a newer style',async()=>{
 api.snapshot.mockResolvedValue(null);await expect(restoreRemakeSettings(work,source,()=>true)).rejects.toThrow('原音乐设置记录不完整');api.load.mockResolvedValue({work:{...work,style:'摇滚'},direction:null});await expect(restoreRemakeSettings(work,source,()=>true)).rejects.toThrow('原音乐设置记录不完整');expect(api.save).not.toHaveBeenCalled()
})
it('does not fall back to current preferences when the original snapshot request fails',async()=>{
 api.snapshot.mockRejectedValue(new Error('snapshot unavailable'));await expect(restoreRemakeSettings(work,source,()=>true)).rejects.toThrow('snapshot unavailable');expect(api.save).not.toHaveBeenCalled()
})
