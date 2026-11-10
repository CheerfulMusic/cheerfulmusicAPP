import { describe, expect, it } from 'vitest'
import { analyzeRequest } from '../src/modules/agent/model/assistant'
import { agentLanguage, agentStructure } from '../src/modules/agent/model/param-compiler'
import { composeWorkStyle, splitStyle, styleTail, WORK_STYLE_LIMIT } from '../src/modules/agent/model/style-compiler'

describe('结构化参数从识别到落库形态的完整编译', () => {
  it('turns one spoken sentence into a storable style, a lyric language and a lyric template', () => {
    const brief = analyzeRequest('写一首 90 BPM 的慢歌，温柔女声，钢琴和弦乐，粤语演唱，主歌副歌').brief
    expect(brief.params).toEqual({ bpm: 90, vocal: 'FEMALE', instruments: ['钢琴', '弦乐'], language: 'yue', structure: '主歌-副歌' })

    // 风格：基础风格保留，补充描述追加，整体不超落库列宽。
    const style = composeWorkStyle(brief.style, styleTail(brief.params))
    expect(style.length).toBeLessThanOrEqual(WORK_STYLE_LIMIT)
    expect(splitStyle(style).base).toBe(brief.style)
    expect(splitStyle(style).tail).toBe('90 BPM, piano, strings, Cantonese vocals, verse-chorus structure')

    // 语言：粤语在歌词侧走中文，风格描述里单独说明。
    expect(agentLanguage(brief.params)).toBe('zh-CN')
    // 段落：主歌副歌映射到现成的歌词模板。
    expect(agentStructure(brief.params)).toEqual({ templateId: 'ABB', chorusFirst: false })
  })

  it('keeps english lyrics when the user asks for english', () => {
    const brief = analyzeRequest('an english song with piano').brief
    expect(agentLanguage(brief.params)).toBe('en')
  })

  it('leaves the lyric language and template untouched when nothing was recognized', () => {
    const brief = analyzeRequest('随便写点什么').brief
    expect(agentLanguage(brief.params)).toBeUndefined()
    expect(agentStructure(brief.params)).toBeUndefined()
    expect(styleTail(brief.params)).toBe('')
    expect(composeWorkStyle(brief.style, '')).toBe(brief.style)
  })

  it('never invents a section the user did not ask for', () => {
    const brief = analyzeRequest('一段前奏就够了').brief
    expect(agentStructure(brief.params)).toBeUndefined()
  })

  it('maps a bridge request onto the template that actually has one', () => {
    const brief = analyzeRequest('主歌副歌加桥段').brief
    expect(agentStructure(brief.params)).toEqual({ templateId: 'ABCD', chorusFirst: false })
  })
})
