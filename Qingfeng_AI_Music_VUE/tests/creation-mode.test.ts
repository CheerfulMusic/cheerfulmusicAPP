import {describe,it,expect} from 'vitest'
import {chooseCreationStyle,creationMode} from '../src/modules/studio/model/creation-mode'
import {inspirationPresets} from '../src/modules/studio/model/inspiration-presets'
import {english} from '../src/shared/i18n/en'
import {quickInProgress} from '../src/modules/studio/api/quick-creation'
import {legacyPreferences} from '../src/modules/works/model/music-preferences'
import {validResponse,emptyResponseAllowed} from '../src/shared/http/response-contracts'
const draft={recipient:'妈妈',occasion:'生日',story:'门口剥好的橘子是我的真实故事',message:'',style:'温暖民谣',title:'',vocalGender:'AUTO' as const}
describe('双入口与作者素材保护',()=>{
 it('新建默认快速 已有作品保留自定义入口 明确选择优先',()=>{
  expect(creationMode(undefined)).toBe('quick');expect(creationMode(undefined,true)).toBe('custom')
  expect(creationMode('quick',true)).toBe('quick');expect(creationMode('custom')).toBe('custom')
 })
 it('每个快捷风格都填入可编辑的描述并保留作者故事',()=>{
  for(const preset of inspirationPresets){
   expect(chooseCreationStyle({...draft,story:''},preset,x=>x)?.story).toBe(preset.description)
   expect(chooseCreationStyle(draft,preset,x=>x)).toMatchObject({
    story:`${preset.description}\n\n${draft.story}`,recipient:'妈妈',style:preset.style,vocalGender:preset.vocalGender,
   })
  }
 })
 it('切换语言与标签时替换完整的旧提示，只保留一份提示和作者补充',()=>{
  const pop=inspirationPresets[0]!,folk=inspirationPresets[3]!
  const previous=chooseCreationStyle(draft,pop,x=>english[x]||x)!
  const updated=chooseCreationStyle(previous,folk,x=>x)!
  expect(updated.story).toBe(`${folk.description}\n\n${draft.story}`)
  expect(chooseCreationStyle(updated,folk,x=>x)).toEqual(updated)
 })
 it('保留修改过的描述，空间不足时不截断原稿',()=>{
  const preset=inspirationPresets[0]!,edited=`${inspirationPresets[1]!.description} 副歌唱给妈妈。`
  expect(chooseCreationStyle({...draft,story:edited},preset,x=>x)?.story).toBe(`${preset.description}\n\n${edited}`)
  expect(chooseCreationStyle({...draft,story:'歌'.repeat(4000)},preset,x=>x)).toBeNull()
 })
 it('写词和谱曲中都视为正在制作 结束后可恢复编辑',()=>{
  for(const state of ['QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC','RUNNING','SAVING','SUBMISSION_UNKNOWN'])expect(quickInProgress(state)).toBe(true)
  for(const state of ['FAILED','SUCCEEDED','CANCELLED'])expect(quickInProgress(state)).toBe(false)
 })
 it('中国风标签正确映射国风设置',()=>expect(legacyPreferences({...draft,id:'id',style:'中国风',version:0,createdAt:'',updatedAt:''}).choices.genre).toEqual(['chinese']))
 it('自动推荐不暗中选择固定曲风',()=>expect(legacyPreferences({...draft,id:'id',style:'自动推荐',version:0,createdAt:'',updatedAt:''}).choices.genre).toEqual([]))
 it('尚无一键任务时允许空响应 有任务时拒绝不完整或未知状态',()=>{
  const path='app/works/work/quick-creations/latest'
  expect(emptyResponseAllowed(path,'GET')).toBe(true);expect(validResponse(path,'GET',null)).toBe(true)
  expect(validResponse(path,'GET',{})).toBe(false)
  const flow={id:'flow',workId:'work',status:'WRITING',revisionId:null,taskId:null,errorCode:null,canRetry:false,createdAt:new Date().toISOString()}
  expect(validResponse(path,'GET',flow)).toBe(true)
  expect(validResponse(path,'GET',{...flow,status:'UNRECOGNIZED'})).toBe(false)
  expect(validResponse(path,'GET',{...flow,taskId:undefined})).toBe(false)
 })
})
