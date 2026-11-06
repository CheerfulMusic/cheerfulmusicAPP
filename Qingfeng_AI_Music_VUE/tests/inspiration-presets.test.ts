import { describe, expect, it } from 'vitest'
import { inspirationPresets, inspirationSelection } from '../src/modules/studio/model/inspiration-presets'
import { english } from '../src/shared/i18n/en'

describe('Inspiration style tags', () => {
  it('shows the six requested choices in order and pairs the five music styles with explicit voices', () => {
    expect(inspirationPresets.map(preset => preset.label)).toEqual(['流行抒情', '流行摇滚', '蓝调R&B', '华语民谣', '中国风', '我还没有灵感'])
    expect(inspirationPresets.map(preset => preset.vocalGender)).toEqual(['FEMALE', 'MALE', 'MALE', 'AUTO', 'MALE', 'AUTO'])
    expect(inspirationPresets.slice(0, 5).map(preset => preset.style)).toEqual(inspirationPresets.slice(0, 5).map(preset => preset.label))
  })
  it('fills real editable text and a persisted style instead of only changing the placeholder', () => {
    const preset = inspirationPresets[0]!
    const result = inspirationSelection(preset, text => text)
    expect(result).toEqual({ story: '我想写一首慢速的抒情歌曲，以钢琴和弦乐打底，节奏温柔舒缓，清晰温柔的女声演唱。', style: '流行抒情', vocalGender: 'FEMALE' })
    result.story += '主题是旅行。'
    expect(preset.description).not.toContain('旅行')
  })
  it('fills English briefs only when chosen in the English interface and translates every tag', () => {
    for (const preset of inspirationPresets) {
      expect(english[preset.label]).toBeTruthy()
      expect(english[preset.description]).toBeTruthy()
      const selection = inspirationSelection(preset, text => english[text]!)
      expect(selection.story).toBe(english[preset.description])
      expect(selection.style).toBe(preset.style)
    }
  })
  it('the no-idea option gives an open brief and releases a previously aggressive or gendered direction', () => {
    const selection = inspirationSelection(inspirationPresets[5]!, text => text)
    expect(selection.story).toContain('寻找一个主题')
    expect(selection.vocalGender).toBe('AUTO')
    expect(selection.style).toBe('轻快流行')
  })
})
