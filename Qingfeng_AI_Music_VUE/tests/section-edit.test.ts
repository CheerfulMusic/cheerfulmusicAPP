import {describe,it,expect} from 'vitest'
import {lyricSlices,hintedLine,suggestedWindow,validWindow,replaceLyrics,windowLines,alignWindow} from '../src/modules/works/model/section-edit'
describe('局部改词定位',()=>{
  it('扩展范围覆盖完整的相邻演唱句，不截断邻句',()=>{expect(alignWindow({start:10,end:20},[{start:12,end:16},{start:18,end:22},{start:24,end:30}],120)).toEqual({start:10,end:22})})
  it('只替换指定歌词行，保留中英、emoji、原换行和其他段落',()=>{
    const source='[Verse]\r\nHome 🌙 等着你\r\n门口的灯还亮着\r\n[Chorus]\r\nHome 🌙 等着你'
    const rows=lyricSlices(source);expect(rows).toHaveLength(3)
    expect(hintedLine(rows,{text:'Home 🌙 等着你',occurrence:1})).toBe(2)
    const row=rows[2]!,result=replaceLyrics(source,row.start,row.end,'Home 🌙 守着你')
    expect(result).toBe('[Verse]\r\nHome 🌙 等着你\r\n门口的灯还亮着\r\n[Chorus]\r\nHome 🌙 守着你')
  })
  it('不能确定重复歌词的位置时要求用户选择，不随便选第一句',()=>{
    const rows=lyricSlices('想起门口的灯\n想起门口的灯\n你还在等我')
    expect(hintedLine(rows,{text:'门口的灯'})).toBe(-1)
    expect(hintedLine(rows,{text:'想起门口的灯',occurrence:1})).toBe(1)
    expect(hintedLine(rows,{text:'不存在的歌词'})).toBe(-1)
  })
  it('前奏、结尾及短歌曲的初始范围满足接口约束',()=>{
    for(const hint of[undefined,{text:'歌词',start:0,end:2},{text:'歌词',start:117,end:120}]){
      const range=suggestedWindow(120,hint);expect(validWindow(range.start,range.end,120)).toBe(true)
    }
    expect(suggestedWindow(19)).toEqual({start:0,end:0})
    expect(validWindow(10,19,120)).toBe(false);expect(validWindow(0,61,120)).toBe(false);expect(validWindow(-1,12,120)).toBe(false);expect(validWindow(0,NaN,120)).toBe(false)
  })
  it('真实时间范围带入附近歌词，第二遍副歌定位到第二处',()=>{
    const rows=lyricSlices('[Verse]\n我在等你\n[Chorus]\n灯还亮着\n雨停了\n[Chorus]\n灯还亮着\n雨停了')
    const times=[{text:'我在等你',start:10,end:13},{text:'灯还亮着',start:20,end:23},{text:'雨停了',start:24,end:27},{text:'灯还亮着',start:70,end:73},{text:'雨停了',start:74,end:77}]
    expect(windowLines(rows,times,68,78)).toEqual([3,4])
  })
})
