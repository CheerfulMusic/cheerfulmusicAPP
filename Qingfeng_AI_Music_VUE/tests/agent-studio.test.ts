import { afterEach, describe, expect, it, vi } from 'vitest'
import { analyzeRequest, inspirationBoards, modeOptions, quickPrompts, withReference } from '../src/modules/agent/model/assistant'
import { clearAgentBrief, readAgentBrief, writeAgentBrief } from '../src/modules/agent/model/brief'

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
