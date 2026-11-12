import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEventParser, readEventStream } from '../src/modules/agent/api/sse'
import { observeConversation } from '../src/modules/agent/api/events'
vi.mock('../src/platform', () => ({ platform: { kind: 'web' } }))
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

const state = { messages: [], songCard: null, taskId: null, action: null, shareUrl: null, liked: false, latestSeq: 0, historyEpoch: 1 }
const stream = (text: string) => new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode(text)); controller.close() } })

describe('read-only Agent SSE protocol', () => {
  it.each(['\n', '\r\n', '\r'])('parses fragmented %j frames and multiline data', separator => {
    const received: unknown[] = [], parser = createEventParser(event => received.push(event))
    const source = `: heartbeat${separator}event: state${separator}data: {${separator}data: "text": "进度"}${separator}${separator}`
    for (const char of source) parser.push(char)
    parser.finish()
    expect(received).toEqual([{ event: 'state', data: '{\n"text": "进度"}' }])
  })
  it('decodes UTF-8 characters split across network chunks', async () => {
    const bytes = new TextEncoder().encode('event: state\ndata: 歌曲🎵\n\n'), received: unknown[] = []
    const body = new ReadableStream<Uint8Array>({ start(controller) { for (const byte of bytes) controller.enqueue(Uint8Array.of(byte)); controller.close() } })
    await readEventStream(body, new AbortController().signal, event => received.push(event))
    expect(received).toEqual([{ event: 'state', data: '歌曲🎵' }])
  })
  it('rejects a truncated event without delivering its partial state', async () => {
    const receive = vi.fn()
    await expect(readEventStream(stream('event: state\ndata: {"messages":[]'), new AbortController().signal, receive)).rejects.toThrow('incomplete')
    expect(receive).not.toHaveBeenCalled()
  })
  it('stops a pending reader when its identity is disposed', async () => {
    const cancelled = vi.fn(), controller = new AbortController()
    const reading = readEventStream(new ReadableStream({ cancel: cancelled }), controller.signal, vi.fn())
    controller.abort(); await reading
    expect(cancelled).toHaveBeenCalledOnce()
  })
  it('observes with GET cookies and accepts only the existing snapshot contract', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(stream(`event: ping\ndata: {}\n\nevent: state\ndata: ${JSON.stringify(state)}\n\n`), { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } }))
    vi.stubGlobal('fetch', fetcher)
    const receive = vi.fn()
    await observeConversation('c1', 12, 1, receive, new AbortController().signal)
    expect(fetcher).toHaveBeenCalledWith('/api/app/agent/conversations/c1/events?afterSeq=12&historyEpoch=1', expect.objectContaining({ method: 'GET', credentials: 'same-origin' }))
    expect(receive).toHaveBeenCalledExactlyOnceWith(state)
    expect(fetcher).toHaveBeenCalledOnce()
  })
  it.each([
    ['event: state\ndata: {"fake":"success"}\n\n', 'text/event-stream'],
    ['event: state\ndata: {broken}\n\n', 'text/event-stream'],
    ['event: error\ndata: {"code":"UNAVAILABLE","message":"safe"}\n\n', 'text/event-stream'],
    ['<html>login</html>', 'text/html'],
  ])('rejects invalid/error streams so the observer can fall back', async (body, contentType) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream(body), { headers: { 'Content-Type': contentType } })))
    const receive = vi.fn()
    await expect(observeConversation('c1', 0, 1, receive, new AbortController().signal)).rejects.toThrow()
    expect(receive).not.toHaveBeenCalled()
  })
  it('rejects cross-conversation messages even in a valid snapshot', async () => {
    const other = { ...state, messages: [{ id: 'm', conversationId: 'c2', seq: 1, role: 'assistant', kind: 'TEXT', state: 'DONE', contentJson: '{"text":"private"}', taskId: null, createdAt: '' }] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream(`event: state\ndata: ${JSON.stringify(other)}\n\n`), { headers: { 'Content-Type': 'text/event-stream' } })))
    await expect(observeConversation('c1', 0, 1, vi.fn(), new AbortController().signal)).rejects.toThrow('another conversation')
  })
  it('ends a silent stream within the watchdog deadline', async () => {
    vi.useFakeTimers()
    const cancelled = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(new ReadableStream({ cancel: cancelled }), { headers: { 'Content-Type': 'text/event-stream' } })))
    const observing = observeConversation('c1', 0, 1, vi.fn(), new AbortController().signal)
    await vi.advanceTimersByTimeAsync(20001); await observing
    expect(cancelled).toHaveBeenCalledOnce()
  })
})
