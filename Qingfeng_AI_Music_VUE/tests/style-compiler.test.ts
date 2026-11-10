import { describe, expect, it } from 'vitest'
import { extractParams } from '../src/modules/agent/model/assistant'
import { composeStyleDetailed, composeWorkStyle, mergeStyle, splitStyle, styleTail, STYLE_LIMIT, WORK_STYLE_LIMIT } from '../src/modules/agent/model/style-compiler'
import { templateForSections } from '../src/modules/works/model/lyrics-structure'

describe('结构化参数编译为风格补充描述', () => {
  it('compiles bpm, instruments, language and structure into one tail', () => {
    const params = extractParams('写一首 90 BPM 的歌，钢琴和弦乐，粤语演唱，主歌副歌')
    expect(styleTail(params)).toBe('90 BPM, piano, strings, Cantonese vocals, verse-chorus structure')
  })

  it('mixes every supported language and instrument without inventing values', () => {
    expect(styleTail(extractParams('英语演唱，吉他'))).toBe('guitar, English vocals')
    expect(styleTail(extractParams('普通话，古筝、二胡'))).toBe('guzheng, erhu, Mandarin vocals')
    expect(styleTail(extractParams('随便写点什么'))).toBe('')
    expect(styleTail(undefined)).toBe('')
  })

  it('maps all six structure tags in order', () => {
    expect(styleTail(extractParams('前奏主歌副歌桥段间奏尾奏')))
      .toBe('intro-verse-chorus-bridge-interlude-outro structure')
  })

  it('splits a stored style back into base and tail', () => {
    expect(splitStyle('温暖民谣 | 90 BPM, piano')).toEqual({ base: '温暖民谣', tail: '90 BPM, piano' })
    expect(splitStyle('温暖民谣')).toEqual({ base: '温暖民谣', tail: '' })
    expect(splitStyle('')).toEqual({ base: '', tail: '' })
    // 基础风格里本身含分隔符时，以最后一个为准，不会切断用户文本。
    expect(splitStyle('A | B | 90 BPM')).toEqual({ base: 'A | B', tail: '90 BPM' })
  })

  it('is idempotent so repeated saves never duplicate the tail', () => {
    const once = mergeStyle('温暖民谣', '90 BPM, piano')
    expect(once).toBe('温暖民谣 | 90 BPM, piano')
    expect(mergeStyle(once, '90 BPM, piano')).toBe(once)
    expect(mergeStyle(splitStyle(once).base, splitStyle(once).tail)).toBe(once)
  })

  it('drops tail segments instead of truncating the base when over the limit', () => {
    const tail = `${'x'.repeat(400)}, ${'y'.repeat(400)}, ${'z'.repeat(400)}`
    const merged = mergeStyle('温暖民谣', tail, STYLE_LIMIT)
    expect(merged.length).toBeLessThanOrEqual(STYLE_LIMIT)
    expect(merged.startsWith('温暖民谣 | ')).toBe(true)
    // 只保留放得下的整项，不切半个词。
    expect(merged).toContain('x'.repeat(400))
    expect(merged).toContain('y'.repeat(400))
    expect(merged).not.toContain('z'.repeat(400))
    const noRoom = mergeStyle('基'.repeat(STYLE_LIMIT), '90 BPM')
    expect(noRoom).toBe('基'.repeat(STYLE_LIMIT))
  })

  it('keeps the user base style when there is no structured tail', () => {
    expect(mergeStyle('复古港风', '')).toBe('复古港风')
    expect(mergeStyle('', '90 BPM')).toBe('90 BPM')
    expect(mergeStyle('', '')).toBe('')
  })

  it('has a prompt for every whitelisted instrument and never echoes the chinese name', () => {
    const names = ['钢琴','吉他','弦乐','小提琴','大提琴','贝斯','鼓','打击乐','合成器','电子音色','古筝','二胡','琵琶','长笛','萨克斯','手风琴','口琴']
    expect(names).toHaveLength(17)
    for (const name of names) {
      const tail = styleTail({ bpm: 0, vocal: 'AUTO', instruments: [name], language: '', structure: '' })
      expect(tail).toBeTruthy()
      expect(tail).not.toContain(name)
    }
  })

  it('combines every parameter into one ordered tail', () => {
    const params = extractParams('90 BPM 温柔女声 钢琴 古筝 粤语演唱 主歌副歌桥段')
    expect(params).toEqual({ bpm: 90, vocal: 'FEMALE', instruments: ['钢琴','古筝'], language: 'yue', structure: '主歌-副歌-桥段' })
    expect(styleTail(params)).toBe('90 BPM, piano, guzheng, Cantonese vocals, verse-chorus-bridge structure')
  })

  it('maps recognized sections onto the existing lyrics templates', () => {
    expect(templateForSections(['主歌','副歌'])).toBe('ABB')
    expect(templateForSections(['主歌','副歌','桥段'])).toBe('ABCD')
    expect(templateForSections(['主歌'])).toBe('AAB')
    // 认不出来的段落保持默认，绝不替用户编造。
    expect(templateForSections(['前奏','间奏','尾奏'])).toBe('AUTO')
    expect(templateForSections([])).toBe('AUTO')
  })

  it('keeps the composed style inside the stored column budget without losing the base', () => {
    const params = extractParams('90 BPM，钢琴，吉他，弦乐，小提琴，大提琴，贝斯，鼓，打击乐，合成器')
    const composed = composeWorkStyle('温暖民谣', styleTail(params))
    expect(composed.length).toBeLessThanOrEqual(WORK_STYLE_LIMIT)
    // 基础风格一个字符都不能少，补充描述按需取舍。
    expect(splitStyle(composed).base).toBe('温暖民谣')
    expect(composed).toContain('90 BPM')
  })

  it('drops tail segments instead of the base when the stored style would overflow', () => {
    const composed = composeWorkStyle('温暖民谣', `${'x'.repeat(60)}, ${'y'.repeat(60)}`)
    expect(composed.length).toBeLessThanOrEqual(WORK_STYLE_LIMIT)
    expect(splitStyle(composed).base).toBe('温暖民谣')
    expect(composed).toContain('x'.repeat(60))
    expect(composed).not.toContain('y'.repeat(60))
  })

  it('never rewrites a style the user typed by hand', () => {
    expect(composeWorkStyle('复古港风', '')).toBe('复古港风')
    expect(composeWorkStyle('', '90 BPM')).toBe('90 BPM')
  })

  it('reports exactly which parameters were dropped instead of dropping them silently', () => {
    const detailed = composeStyleDetailed('温暖民谣', '90 BPM, piano, strings, guzheng', 25)
    expect(detailed.style).toBe('温暖民谣 | 90 BPM, piano')
    expect(detailed.dropped).toEqual(['strings', 'guzheng'])
    // 合成结果与纯文本版本必须完全一致，避免两套规则分叉。
    expect(composeWorkStyle('温暖民谣', '90 BPM, piano, strings, guzheng', 25)).toBe(detailed.style)
  })

  it('reports nothing dropped when every parameter fits the storage budget', () => {
    const detailed = composeStyleDetailed('温暖民谣', '90 BPM, piano', WORK_STYLE_LIMIT)
    expect(detailed.style).toBe('温暖民谣 | 90 BPM, piano')
    expect(detailed.dropped).toEqual([])
  })

  it('reports every parameter dropped when the base style alone fills the budget', () => {
    const detailed = composeStyleDetailed('基'.repeat(WORK_STYLE_LIMIT), '90 BPM, piano', WORK_STYLE_LIMIT)
    expect(detailed.style).toBe('基'.repeat(WORK_STYLE_LIMIT))
    expect(detailed.dropped).toEqual(['90 BPM', 'piano'])
  })
})
