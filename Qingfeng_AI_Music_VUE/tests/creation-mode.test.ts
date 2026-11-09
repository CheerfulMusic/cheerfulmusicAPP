import {describe,it,expect} from 'vitest'
import {chooseCreationStyle,creationMode} from '../src/modules/studio/model/creation-mode'
import {inspirationPresets} from '../src/modules/studio/model/inspiration-presets'
import {quickInProgress} from '../src/modules/studio/api/quick-creation'
import {legacyPreferences} from '../src/modules/works/model/music-preferences'
import {validResponse,emptyResponseAllowed} from '../src/shared/http/response-contracts'
const draft={recipient:'妈妈',occasion:'生日',story:'门口剥好的橘子是我的真实故事',message:'',style:'温暖民谣',title:'',vocalGender:'AUTO' as const}
describe('双入口与作者素材保护',()=>{
 it('新建默认快速 已有作品保留自定义入口 明确选择优先',()=>{
  expect(creationMode(undefined)).toBe('quick');expect(creationMode(undefined,true)).toBe('custom')
  expect(creationMode('quick',true)).toBe('quick');expect(creationMode('custom')).toBe('custom')
 })
 it('切换所有快捷风格均不覆盖作者故事',()=>{
  for(const [index,preset] of inspirationPresets.entries()){
   const selected=chooseCreationStyle(draft,preset,index===5,x=>x)
   expect(selected.story).toBe(draft.story);expect(selected.recipient).toBe('妈妈');expect(selected.style).toBe(preset.style)
  }
 })
 it('普通风格不自动填充故事 只有空白时选择没有灵感才填入示例',()=>{
  expect(chooseCreationStyle({...draft,story:''},inspirationPresets[0]!,false,x=>x).story).toBe('')
  expect(chooseCreationStyle({...draft,story:''},inspirationPresets[5]!,true,x=>x).story).toContain('寻找一个主题')
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
