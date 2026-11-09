import {afterEach,expect,it} from 'vitest'
import {formatHotCount,heatOf,sortByHeat} from '../src/modules/home/model/hot-songs'
import {setLocale} from '../src/shared/i18n'
import {validResponse} from '../src/shared/http/response-contracts'

afterEach(()=>setLocale('zh-CN'))

it('keeps the curated order until real numbers arrive',()=>{
  const list=[{id:'love'},{id:'family'},{id:'travel'}]
  expect(sortByHeat(list).map(track=>track.id)).toEqual(['love','family','travel'])
  expect(list.map(track=>track.id)).toEqual(['love','family','travel'])
})

it('weights likes, favorites and comments above a single play',()=>{
  expect(heatOf({trackId:'a',plays:10,likes:0,favorites:0,comments:0})).toBe(10)
  expect(heatOf({trackId:'a',plays:0,likes:2,favorites:0,comments:0})).toBe(6)
  expect(heatOf({trackId:'a',plays:0,likes:0,favorites:1,comments:1})).toBe(8)
})

it('formats counts in the reader language and never shows negative numbers',()=>{
  setLocale('zh-CN')
  expect(formatHotCount(0)).toBe('0')
  expect(formatHotCount(-3)).toBe('0')
  expect(formatHotCount(999)).toBe('999')
  expect(formatHotCount(12345)).toBe('1.2万')
  setLocale('en')
  expect(formatHotCount(1500)).toBe('1.5K')
  expect(formatHotCount(12000)).toBe('12K')
})

it('rejects hot song responses without complete counters',()=>{
  const stat={trackId:'love',plays:3,likes:1,favorites:0,comments:0}
  expect(validResponse('public/hot-songs','GET',{songs:[stat]})).toBe(true)
  expect(validResponse('public/hot-songs','GET',{songs:[{...stat,plays:'3'}]})).toBe(false)
  expect(validResponse('public/hot-songs','GET',{songs:[{...stat,likes:-1}]})).toBe(false)
  expect(validResponse('public/hot-songs/love/play','POST',stat)).toBe(true)
  expect(validResponse('app/hot-songs/reactions','GET',{liked:['love'],favorited:[]})).toBe(true)
  expect(validResponse('app/hot-songs/reactions','GET',{liked:'love'})).toBe(false)
  expect(validResponse('app/hot-songs/love/like','POST',{...stat,liked:true,favorited:false})).toBe(true)
  expect(validResponse('app/hot-songs/love/like','POST',{...stat,liked:true})).toBe(false)
})
