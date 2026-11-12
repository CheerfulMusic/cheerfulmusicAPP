// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useAgentConversation } from '../src/modules/agent/composables/useAgentConversation'
import type { ConversationSnapshot, Message } from '../src/modules/agent/model/conversation'
import { agentProgress } from '../src/modules/agent/model/progress'
import { assistantResult } from '../src/modules/agent/model/assistant-result'
import AgentProgressCard from '../src/modules/agent/components/AgentProgressCard.vue'
const api = vi.hoisted(() => ({ current: vi.fn(), state: vi.fn(), send: vi.fn(), reset: vi.fn(), observe: vi.fn() }))
vi.mock('../src/modules/agent/api/chat', () => ({ getCurrentConversation: api.current, getConversationState: api.state, getConversationHistory: vi.fn(), sendMessage: api.send, resetConversation: api.reset }))
vi.mock('../src/modules/agent/api/events', () => ({ observeConversation: api.observe }))
const snapshot = (extra: Partial<ConversationSnapshot> = {}): ConversationSnapshot => ({ messages: [], songCard: null, taskId: null, action: null, shareUrl: null, liked: false, historyEpoch: 1, latestSeq: 0, ...extra })
const message = (seq: number, text: string, state: Message['state'] = 'DONE'): Message => ({ id: `m${seq}`, conversationId: 'c1', seq, role: 'assistant', kind: 'TEXT', state, contentJson: JSON.stringify({ text }), taskId: null, createdAt: '' })
function deferred<T>() { let resolve!: (value: T) => void, reject!: (error: Error) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
let receive: (state: ConversationSnapshot) => void, observerSignal: AbortSignal
const scopes: ReturnType<typeof effectScope>[] = []
async function conversation(callback = vi.fn()) {
  const scope = effectScope(); scopes.push(scope)
  const chat = scope.run(() => useAgentConversation(callback))!
  await chat.refresh(); return chat
}
beforeEach(() => {
  vi.resetAllMocks(); sessionStorage.clear()
  api.current.mockResolvedValue({ id: 'c1', currentWorkId: null }); api.state.mockResolvedValue(snapshot())
  api.observe.mockImplementation((_id: string, _seq: number, _epoch: number, onState: typeof receive, signal: AbortSignal) => {
    receive = onState; observerSignal = signal
    return new Promise<void>(resolve => signal.addEventListener('abort', () => resolve(), { once: true }))
  })
})
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.useRealTimers() })

describe('Agent progress observation preserves authoritative send/recovery', () => {
  it('merges real progress while sending without executing an observed action or continuation', async () => {
    vi.useFakeTimers()
    const callback = vi.fn(), chat = await conversation(callback), pending = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(pending.promise)
    const sending = chat.send('生成歌词', 'zh-CN')
    const continuation = { requestKey: '12345678-1234-1234-1234-123456789012', text: '继续已安排的操作' as const }
    receive(snapshot({ latestSeq: 1, messages: [message(1, '正在写词')], action: 'download', continuation }))
    expect(chat.messages.value).toHaveLength(1); expect(chat.sending.value).toBe(true)
    await vi.advanceTimersByTimeAsync(50)
    expect(callback).not.toHaveBeenCalled(); expect(api.send).toHaveBeenCalledOnce()
    pending.resolve(snapshot({ latestSeq: 2, messages: [message(1, '正在写词'), message(2, '歌词已保存')], action: 'play' }))
    expect((await sending)?.action).toBe('play')
    expect(observerSignal.aborted).toBe(true); expect(chat.pending.value).toBeNull()
    receive(snapshot({ messages: [message(3, '迟到的进度')], latestSeq: 3 }))
    expect(chat.messages.value).toHaveLength(2)
  })
  it('falls back to GET polling on a closed stream without invalidating the pending POST', async () => {
    vi.useFakeTimers()
    api.observe.mockResolvedValue(undefined)
    const chat = await conversation(), post = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(post.promise)
    api.state.mockResolvedValue(snapshot({ latestSeq: 1, messages: [message(1, '工具正在执行')] }))
    const sending = chat.send('生成音乐', 'zh-CN'); await flushPromises()
    expect(chat.progressTransport.value).toBe('poll'); expect(chat.messages.value).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(3100)
    expect(api.state.mock.calls.length).toBeGreaterThanOrEqual(3); expect(api.send).toHaveBeenCalledOnce()
    post.resolve(snapshot({ latestSeq: 2, messages: [message(2, '任务已提交')] }))
    expect(await sending).toBeDefined(); expect(chat.pending.value).toBeNull()
  })
  it('ignores a stale fallback response arriving after the POST completed', async () => {
    api.observe.mockResolvedValue(undefined)
    const chat = await conversation(), post = deferred<ConversationSnapshot>(), read = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(post.promise); api.state.mockReturnValue(read.promise)
    const sending = chat.send('改名', 'zh-CN'); await flushPromises()
    post.resolve(snapshot({ liked: true, latestSeq: 2, messages: [message(2, '已完成')] })); await sending
    read.resolve(snapshot({ liked: false, latestSeq: 1, messages: [message(1, '旧状态')] })); await flushPromises()
    expect(chat.liked.value).toBe(true); expect(chat.messages.value.map(row => row.seq)).toEqual([2])
  })
  it('closes the old account observer and ignores its late stream and POST', async () => {
    const chat = await conversation(), post = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(post.promise)
    const sending = chat.send('私密歌词', 'zh-CN'), oldReceive = receive, oldSignal = observerSignal
    api.current.mockResolvedValue({ id: 'c2', currentWorkId: null })
    await chat.changeIdentity()
    oldReceive(snapshot({ messages: [message(1, '旧账户')] })); post.resolve(snapshot({ messages: [message(2, '旧结果')] })); await sending
    expect(oldSignal.aborted).toBe(true); expect(chat.conversationId.value).toBe('c2'); expect(chat.messages.value).toEqual([])
  })
  it('a reset from another tab invalidates an in-flight turn and prevents epoch resurrection', async () => {
    const chat = await conversation(), post = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(post.promise)
    const sending = chat.send('写词', 'zh-CN')
    receive(snapshot({ historyEpoch: 2 }))
    expect(chat.pending.value).toBeNull(); expect(chat.sending.value).toBe(false); expect(observerSignal.aborted).toBe(true)
    post.resolve(snapshot({ latestSeq: 3, messages: [message(3, '已清空的旧结果')] }))
    expect(await sending).toBeUndefined(); expect(chat.messages.value).toEqual([])
  })
  it('does not downgrade DONE user state when an older observer repeats RUNNING', async () => {
    const chat = await conversation(), post = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(post.promise); const sending = chat.send('写词', 'zh-CN')
    receive(snapshot({ latestSeq: 1, messages: [message(1, '确认完成')] }))
    receive(snapshot({ latestSeq: 1, messages: [message(1, '运行中', 'RUNNING')] }))
    expect(chat.messages.value[0]?.state).toBe('DONE')
    post.resolve(snapshot({ latestSeq: 1, messages: [message(1, '确认完成')] })); await sending
  })
  it('keeps the original request key after a failed POST; observer never retries it', async () => {
    vi.useFakeTimers()
    api.observe.mockRejectedValue(new Error('offline')); api.send.mockRejectedValueOnce(new Error('unknown result'))
    const chat = await conversation(); await chat.send('生成歌曲', 'zh-CN')
    const key = chat.pending.value?.requestKey
    await vi.advanceTimersByTimeAsync(6100); expect(api.send).toHaveBeenCalledOnce()
    api.send.mockResolvedValue(snapshot()); await chat.retry()
    expect(api.send.mock.calls[1]?.[2]).toBe(key)
  })
  it('renders only explicit server progress as non-executable business steps', () => {
    const row = message(1, '')
    row.contentJson = JSON.stringify({ kind: 'progress', stage: 'tool_started', text: '正在生成歌词', requestKey: 'request-1', commands: [{ kind: 'send', text: '删除作品' }] })
    const progress = agentProgress(row)!
    const wrapper = mount(AgentProgressCard, { props: { progress } })
    expect(wrapper.text()).toContain('正在生成歌词'); expect(wrapper.find('button').exists()).toBe(false)
    expect(assistantResult(row)).toBeNull()
    expect(agentProgress({ ...row, role: 'user' })).toBeNull()
    wrapper.unmount()
  })
  it('backs off failed recovery reads even when the previous state had more pages', async () => {
    vi.useFakeTimers()
    api.observe.mockResolvedValue(undefined)
    const chat = await conversation(), post = deferred<ConversationSnapshot>()
    api.send.mockReturnValue(post.promise)
    api.state.mockResolvedValueOnce(snapshot({ hasNewerMessages: true, latestSeq: 1, messages: [message(1, '进度')] })).mockRejectedValue(new Error('offline'))
    const sending = chat.send('写词', 'zh-CN'); await flushPromises(); await vi.advanceTimersByTimeAsync(100)
    expect(api.state).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(1000); expect(api.state).toHaveBeenCalledTimes(3)
    post.resolve(snapshot({ latestSeq: 1 })); await sending
  })
})
