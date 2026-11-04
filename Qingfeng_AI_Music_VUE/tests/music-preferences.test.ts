import {describe,it,expect} from 'vitest'
import {applyRecipe,legacyPreferences,toggleMusicOption,validMusicPreferences} from '../src/modules/works/model/music-preferences'
import type {MusicCatalog,MusicPreferences} from '../src/modules/works/api/music-settings'
import {reactive} from 'vue'
import {readFileSync} from 'node:fs'
const catalog=JSON.parse(readFileSync('../Qingfeng_AI_Music/qf-infrastructure/src/main/resources/music/catalog-v1.json','utf8')) as MusicCatalog
const base:MusicPreferences={version:1,recipeId:null,choices:{genre:['jazz'],exclude:['no_rap']},vocalGender:'FEMALE',freeText:'不要太悲伤',negativeText:'高音'}
describe('音乐偏好组合',()=>{
 it('恢复草稿前拒绝损坏结构和未知标签，允许尚未选全的可编辑草稿',()=>{
  expect(validMusicPreferences(base,catalog)).toBe(true)
  expect(validMusicPreferences({...base,choices:null},catalog)).toBe(false)
  expect(validMusicPreferences({...base,choices:{genre:'folk'}},catalog)).toBe(false)
  expect(validMusicPreferences({...base,choices:{genre:['unknown']}},catalog)).toBe(false)
  expect(validMusicPreferences({...base,choices:{genre:[]}},catalog)).toBe(true)
 })
 it('配方可从Vue响应式目录载入，保留自行输入、排除项和声音，且不修改共享配置',()=>{
  const original=JSON.stringify(catalog),next=applyRecipe(reactive(base),reactive(catalog.recipes[0]!))
  expect(next.choices.genre).toEqual(['folk']);expect(next.choices.exclude).toEqual(['no_rap']);expect(next.vocalGender).toBe('FEMALE');expect(next.freeText).toBe(base.freeText)
  next.choices.genre!.push('pop');expect(JSON.stringify(catalog)).toBe(original)
 })
 it('融合风格有限额，单选可替换，多选顺序保留主乐器语义',()=>{
  let p=toggleMusicOption(base,catalog,'genre','folk')!;expect(toggleMusicOption(p,catalog,'genre','pop')).toBeNull()
  p=toggleMusicOption(p,catalog,'instruments','piano')!;p=toggleMusicOption(p,catalog,'instruments','guitar')!;expect(p.choices.instruments).toEqual(['piano','guitar'])
  p=toggleMusicOption(p,catalog,'instruments','piano')!;expect(p.choices.instruments).toEqual(['guitar']);expect(p.recipeId).toBeNull()
 })
 it('旧作品的自由风格和声音在打开新面板时保留',()=>{
  const work={style:'钢琴，像说话一样自然',vocalGender:'MALE'} as Parameters<typeof legacyPreferences>[0]
  const p=legacyPreferences(work);expect(p.vocalGender).toBe('MALE');expect(p.freeText).toBe(work.style);expect(p.choices.instruments).toEqual(['piano'])
 })
})
