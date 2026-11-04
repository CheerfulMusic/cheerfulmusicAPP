import {expect,it} from 'vitest'
import {songSettings} from '../src/modules/works/model/song-settings'
import type {AudioAsset,GenerationTask} from '../src/modules/works/api/detail'
const asset={id:'original',origin:'GENERATED'} as AudioAsset
const original={assetId:'original',style:'温暖民谣',vocalGender:'FEMALE'} as GenerationTask
it('shows the settings of the selected recording, regardless of later tasks and draft edits',()=>{
  const newer={assetId:'new-song',style:'独立摇滚',vocalGender:'MALE'} as GenerationTask
  expect(songSettings(asset,[newer,original])).toBe(original)
  expect(songSettings({...asset,id:'new-song'},[newer,original])).toBe(newer)
})
it('does not invent style or voice information for imported or missing recordings',()=>{
  expect(songSettings({...asset,origin:'IMPORTED'},[original])).toBeUndefined()
  expect(songSettings({...asset,id:'unmatched'},[original])).toBeUndefined()
  expect(songSettings(null,[original])).toBeUndefined()
})
