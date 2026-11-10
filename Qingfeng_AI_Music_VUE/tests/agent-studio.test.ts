import { afterEach, describe, expect, it, vi } from 'vitest'
import { analyzeRequest, extractParams, inspirationBoards, modeOptions, quickPrompts, withReference } from '../src/modules/agent/model/assistant'
import { clearAgentBrief, readAgentBrief, shouldApplyAgentBrief, writeAgentBrief } from '../src/modules/agent/model/brief'

afterEach(() => { vi.unstubAllGlobals() })

describe('Agent creation assistant', () => {
  it('recognizes the theme keywords from the sample prompts and keeps a usable style', () => {
    expect(analyzeRequest('写一首关于旅行的歌').style).toBe('温暖民谣')
    expect(analyzeRequest('用粤语唱一首情歌').style).toBe('复古港风')
    expect(analyzeRequest('帮我写一首生日快乐歌').style).toBe('轻快流行')
    expect(analyzeRequest('给我一首电影风格的歌曲').style).toBe('电影配乐')
    expect(analyzeRequest('随便写点什么').style).toBe('温暖民谣')
  })
  it('builds an editable creative brief with interests and the selected mode', () => {
    const reply = analyzeRequest('创作一首夏日海岛风', 'write', ['旅行', '美食'])
    expect(reply.brief.story).toContain('创作一首夏日海岛风')
    expect(reply.brief.story).toContain('旅行、美食')
    expect(reply.brief.tags).toEqual(['旅行', '美食'])
    expect(analyzeRequest('把这段歌词换一种说法', 'rewrite').brief.story).toContain('改写这段歌词')
    expect(analyzeRequest('再唱一段', 'extend').brief.story).toContain('延长一段')
  })
  it('offers three real style choices in the style-recommendation mode', () => {
    const reply = analyzeRequest('今天有点累', 'style')
    expect(reply.suggestions).toHaveLength(3)
    expect(reply.suggestions[0]).toBe(reply.style)
    expect(new Set(reply.suggestions).size).toBe(3)
  })
  it('ships the six quick prompts, two inspiration boards and five play modes', () => {
    expect(quickPrompts).toHaveLength(6)
    expect(inspirationBoards.map(board => board.length)).toEqual([3, 3])
    expect(modeOptions.map(option => option.id)).toEqual(['write', 'rewrite', 'extend', 'style', 'more'])
    for (const board of inspirationBoards) for (const card of board) expect(card.cover.startsWith('/media/')).toBe(true)
  })
  it('hands the brief to the studio and only clears it after the studio takes it', () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    })
    expect(readAgentBrief()).toBeUndefined()
    writeAgentBrief({ story: '写一首关于旅行的歌', style: '温暖民谣', mood: '自由随性', tags: ['旅行'] })
    expect(readAgentBrief()).toEqual({ story: '写一首关于旅行的歌', style: '温暖民谣', mood: '自由随性', tags: ['旅行'] })
    // 读取不再消费：创作页刷新或初始化失败后仍能恢复同一份 brief。
    expect(readAgentBrief()).toEqual({ story: '写一首关于旅行的歌', style: '温暖民谣', mood: '自由随性', tags: ['旅行'] })
    clearAgentBrief()
    expect(readAgentBrief()).toBeUndefined()
    storage.set('qingfeng.agent.brief', '{not json')
    expect(readAgentBrief()).toBeUndefined()
  })
  it('keeps the stored style inside the studio whitelist', () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    })
    storage.set('qingfeng.agent.brief', JSON.stringify({ story: '随便一段描述', style: '任意注入的风格', mood: '平静', tags: [] }))
    expect(readAgentBrief()?.style).toBe('温暖民谣')
    storage.set('qingfeng.agent.brief', JSON.stringify({ story: '随便一段描述', style: '国风古韵', mood: '平静', tags: [] }))
    expect(readAgentBrief()?.style).toBe('国风古韵')
  })
  it('writes the reference music into the brief exactly once', () => {
    const reference = { name: 'demo.m4a', duration: 128.5, size: 1024, type: 'audio/mp4' }
    const first = analyzeRequest('写一首关于旅行的歌', 'write', ['旅行'], reference)
    expect(first.brief.story).toContain('参考音乐《demo.m4a》')
    expect(first.brief.story).toContain('时长 02:08')
    expect(first.brief.reference).toEqual(reference)
    // 多轮补充细节时不会重复堆叠同一条参考。
    const again = withReference(first.brief, reference)
    expect(again.story.match(/参考音乐《/g)).toHaveLength(1)
    // 没有参考音乐时不改动原描述。
    expect(withReference(first.brief, undefined).story).toBe(first.brief.story)
  })
  it('drops unusable reference values coming from session storage', () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    })
    storage.set('qingfeng.agent.brief', JSON.stringify({ story: '一段描述', style: '温暖民谣', mood: '', tags: [], reference: { name: '   ', duration: 3 } }))
    expect(readAgentBrief()?.reference).toBeUndefined()
    storage.set('qingfeng.agent.brief', JSON.stringify({ story: '一段描述', style: '温暖民谣', mood: '', tags: [], reference: { name: 'ok.mp3', duration: -1, size: 0, type: 42 } }))
    expect(readAgentBrief()?.reference).toEqual({ name: 'ok.mp3', duration: 0, size: 0, type: '' })
  })
  it('extracts structured parameters only when the user actually said them', () => {
    expect(extractParams('写一首 90 BPM 的慢歌，温柔女声，钢琴和弦乐，粤语演唱，主歌副歌')).toEqual({
      bpm: 90, vocal: 'FEMALE', instruments: ['钢琴', '弦乐'], language: 'yue', structure: '主歌-副歌',
    })
    expect(extractParams('随便写点什么')).toEqual({ bpm: 0, vocal: 'AUTO', instruments: [], language: '', structure: '' })
    // 越界的 BPM 与未知语言一律不采信。
    expect(extractParams('500 bpm 英文演唱').bpm).toBe(0)
    expect(extractParams('500 bpm 英文演唱').language).toBe('en')
    expect(analyzeRequest('想要一首男声摇滚').brief.params?.vocal).toBe('MALE')
  })
  it('re-reads the same brief after a refresh so structured parameters survive', () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    })
    writeAgentBrief({
      story: '写给妈妈的歌', style: '抒情流行', mood: '温暖怀念', tags: [],
      params: { bpm: 88, vocal: 'FEMALE', instruments: ['钢琴'], language: 'zh', structure: '主歌-副歌' },
    })
    expect(readAgentBrief()?.params).toMatchObject({ bpm: 88, vocal: 'FEMALE', instruments: ['钢琴'], language: 'zh' })
    // 刷新＝再读一次：brief 不再是一次性消费，参数也不会丢。
    expect(readAgentBrief()?.params).toMatchObject({ bpm: 88, vocal: 'FEMALE' })
  })
  it('applies the agent brief only to a new draft from the agent page', () => {
    expect(shouldApplyAgentBrief({ from: 'agent' })).toBe(true)
    expect(shouldApplyAgentBrief({ from: 'agent', occasion: '生日' })).toBe(true)
    // 打开已有作品时绝不能覆盖用户已保存的内容。
    expect(shouldApplyAgentBrief({ from: 'agent', work: 'w1' })).toBe(false)
    expect(shouldApplyAgentBrief({ work: 'w1' })).toBe(false)
    expect(shouldApplyAgentBrief({})).toBe(false)
    expect(shouldApplyAgentBrief({ from: 'home' })).toBe(false)
  })
  it('drops illegal structured parameters coming from session storage', () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    })
    storage.set('qingfeng.agent.brief', JSON.stringify({ story: '一段描述', style: '温暖民谣', mood: '', tags: [],
      params: { bpm: 999, vocal: 'ROBOT', instruments: ['钢琴', '', 3], language: 'fr', structure: 5 } }))
    // 只保留合法乐器，其余非法字段全部丢弃。
    expect(readAgentBrief()?.params).toEqual({ bpm: 0, vocal: 'AUTO', instruments: ['钢琴'], language: '', structure: '' })
    storage.set('qingfeng.agent.brief', JSON.stringify({ story: '一段描述', style: '温暖民谣', mood: '', tags: [],
      params: { bpm: 88, vocal: 'FEMALE', instruments: ['钢琴'], language: 'zh', structure: '' } }))
    expect(readAgentBrief()?.params).toEqual({ bpm: 88, vocal: 'FEMALE', instruments: ['钢琴'], language: 'zh', structure: '' })
  })
  it('never stores a story longer than the studio textarea allows', () => {
    const storage = new Map<string, string>()
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
    })
    writeAgentBrief({ story: '海'.repeat(5000), style: '温暖民谣', mood: '', tags: [] })
    expect(readAgentBrief()?.story).toHaveLength(4000)
  })
  it('degrades safely when session storage is blocked', () => {
    vi.stubGlobal('sessionStorage', { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') }, removeItem() { throw new Error('blocked') } })
    expect(() => writeAgentBrief({ story: 'hello', style: '温暖民谣', mood: '', tags: [] })).not.toThrow()
    expect(readAgentBrief()).toBeUndefined()
  })
})
