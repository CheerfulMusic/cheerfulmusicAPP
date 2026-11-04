import {expect,it} from 'vitest'
import {lyricPosition,plainLyricLines} from '../src/shared/player/lyric-timeline'
const lines=[{text:'first',start:16,end:23},{text:'second',start:24,end:30},{text:'last',start:120,end:125}]
it('uses exact line boundaries and leaves instrumental gaps unhighlighted',()=>{
  expect(lyricPosition(lines,0)).toEqual({active:-1,anchor:0});expect(lyricPosition(lines,16)).toEqual({active:0,anchor:0});expect(lyricPosition(lines,23.5)).toEqual({active:-1,anchor:0});expect(lyricPosition(lines,24)).toEqual({active:1,anchor:1});expect(lyricPosition(lines,200)).toEqual({active:-1,anchor:2})
})
it('finds the right line when seeking forward and backward without relying on playback ticks',()=>{
  expect(lyricPosition(lines,122).active).toBe(2);expect(lyricPosition(lines,17).active).toBe(0);expect(lyricPosition([],2).active).toBe(-1);expect(lyricPosition(lines,NaN).active).toBe(-1)
})
it('shows plain lyrics without pretending they have timing or displaying verse instructions',()=>{expect(plainLyricLines('[Verse 1]\n第一句\n\n[Chorus]\n第二句')).toEqual(['第一句','第二句'])})
