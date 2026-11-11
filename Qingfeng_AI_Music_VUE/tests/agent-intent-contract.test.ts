// W1：只测类型守卫与离线 stub 契约；意图断言由后端 IntentRouterTest 承担，此处不写空用例。
import { describe, expect, it } from 'vitest'
import fixtures from './fixtures/agent-intents.json'
import {
  isMessage, isSongCard, isToolResult, stubUserMessage,
  type SongCard,
} from '../src/modules/agent/model/conversation'
import { isAgentEvent, dedupeEvents, type AgentEvent } from '../src/modules/agent/model/events'

const validCard: SongCard = {
  songId: 'work-1', title: '回家的风', style: '民谣', lyrics: '第一段',
  audioUrl: '/api/app/works/work-1/audio/a1', status: 'SUCCEEDED', coverUrl: null, revision: 3,
}

describe('SongCard / ToolResult 类型守卫', () => {
  it('接受完整卡片并拒绝缺字段或错误类型', () => {
    expect(isSongCard(validCard)).toBe(true)
    expect(isSongCard(null)).toBe(false)
    expect(isSongCard({ ...validCard, revision: '3' })).toBe(false)
    expect(isSongCard({ ...validCard, songId: undefined })).toBe(false)
    expect(isSongCard({ ...validCard, coverUrl: 12 })).toBe(false)
  })

  it('接受四种 status 并拒绝非法 status / 非法卡片', () => {
    for (const status of ['ok', 'busy', 'error', 'clarify']) {
      expect(isToolResult({ status, songCard: null, progress: null, errorCode: null, messageId: 'm1' })).toBe(true)
    }
    expect(isToolResult({ status: 'unknown', songCard: null, progress: null, errorCode: null, messageId: 'm1' })).toBe(false)
    expect(isToolResult({ status: 'ok', songCard: { bad: true }, progress: null, errorCode: null, messageId: 'm1' })).toBe(false)
  })
})

describe('Message 与 stub 契约', () => {
  it('stub 产出的是合法 Message，且 state/kind 落在固定枚举内', () => {
    const message = stubUserMessage('stub-1', 'conv-1', '写一首关于旅行的歌')
    expect(isMessage(message)).toBe(true)
    expect(message.state).toBe('DONE')
    expect(message.kind).toBe('TEXT')
    expect(message.seq).toBe(1)
    expect(JSON.parse(message.contentJson ?? '{}')).toEqual({ text: '写一首关于旅行的歌' })
  })

  it('拒绝非法 role/kind/state 与非整数 seq', () => {
    const message = stubUserMessage('stub-1', 'conv-1', 'x')
    expect(isMessage({ ...message, role: 'system' })).toBe(false)
    expect(isMessage({ ...message, kind: 'AUDIO' })).toBe(false)
    expect(isMessage({ ...message, state: 'WAITING' })).toBe(false)
    expect(isMessage({ ...message, seq: 1.5 })).toBe(false)
  })
})

describe('SSE 事件契约（W3 前置）', () => {
  it('接受合法事件并按 (conversationId, seq) 去重', () => {
    const base = { conversationId: 'c1', messageId: 'm1', seq: 1 }
    const first: AgentEvent = { type: 'text_delta', ...base, payload: { delta: '你' } }
    const second: AgentEvent = { type: 'done', ...base, seq: 2 }
    expect(isAgentEvent(first)).toBe(true)
    expect(isAgentEvent({ ...first, type: 'nope' })).toBe(false)
    expect(dedupeEvents([first, first, second])).toHaveLength(2)
  })
})

describe('金标语料集契约', () => {
  it('共 50 条，RULE/LLM 两类齐全且字段完整', () => {
    expect(fixtures.version).toBe(1)
    expect(fixtures.cases).toHaveLength(50)
    const rule = fixtures.cases.filter(item => item.expectedSource === 'RULE')
    const llm = fixtures.cases.filter(item => item.expectedSource === 'LLM')
    expect(rule).toHaveLength(35)
    expect(llm).toHaveLength(15)
    for (const item of fixtures.cases) {
      expect(item.id).toMatch(/^[RL]\d{2}$/)
      expect(item.text.length).toBeGreaterThan(0)
      expect(['zh-CN', 'en']).toContain(item.locale)
      expect(item.expectedTool.length).toBeGreaterThan(0)
    }
  })
})
