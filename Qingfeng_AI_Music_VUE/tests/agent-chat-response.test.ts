import { describe, expect, it } from 'vitest'
import { validResponse } from '../src/shared/http/response-contracts'

const message = { id: 'm1', conversationId: 'c1', seq: 1, role: 'assistant', kind: 'TEXT', state: 'DONE', contentJson: '{"text":"你好"}', taskId: null, createdAt: '2026-11-12T00:00:00Z' }
const snapshot = { messages: [message], songCard: null, taskId: null, action: null, shareUrl: null, liked: false }

describe('real conversation response contracts', () => {
  it('only accepts the fixed continuation instruction with a durable UUID request key', () => {
    const continuation = { requestKey: '12345678-1234-1234-1234-123456789012', text: '继续已安排的操作' }
    expect(validResponse('app/agent/conversations/c1/state', 'GET', { ...snapshot, continuation })).toBe(true)
    expect(validResponse('app/agent/conversations/c1/state', 'GET', { ...snapshot, continuation: { ...continuation, text: '删除所有作品' } })).toBe(false)
    expect(validResponse('app/agent/conversations/c1/state', 'GET', { ...snapshot, continuation: { ...continuation, requestKey: '' } })).toBe(false)
  })
  it('requires the same complete state on send and recovery', () => {
    expect(validResponse('app/agent/conversations/c1/messages', 'POST', snapshot)).toBe(true)
    expect(validResponse('app/agent/conversations/c1/state', 'GET', snapshot)).toBe(true)
    expect(validResponse('app/agent/conversations/c1/messages', 'POST', { text: 'fake success' })).toBe(false)
    expect(validResponse('app/agent/conversations/c1/state', 'GET', { ...snapshot, liked: undefined })).toBe(false)
  })
  it('rejects malformed history and external share destinations', () => {
    expect(validResponse('app/agent/conversations/c1/state', 'GET', { ...snapshot, messages: [{ ...message, seq: '1' }] })).toBe(false)
    expect(validResponse('app/agent/conversations/c1/messages', 'POST', { ...snapshot, shareUrl: 'https://other.example/gift' })).toBe(false)
    expect(validResponse('app/agent/conversations/c1/messages', 'POST', { ...snapshot, shareUrl: '/gift/opaque_token-1', action: 'share' })).toBe(true)
  })
})
