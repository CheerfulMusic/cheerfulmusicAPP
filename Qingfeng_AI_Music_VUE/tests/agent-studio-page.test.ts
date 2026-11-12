// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { effectScope } from 'vue'
const api = vi.hoisted(() => ({ current: vi.fn(), state: vi.fn(), send: vi.fn(), reset: vi.fn(), analyse: vi.fn(), cancel: vi.fn(), forget: vi.fn() }))
vi.mock('../src/modules/agent/api/chat', () => ({ getCurrentConversation: api.current, getConversationState: api.state, sendMessage: api.send, resetConversation: api.reset }))
vi.mock('../src/shared/credits/useCredits', () => ({ useCredits: () => ({ credits: null, refreshCredits: vi.fn(async () => undefined) }) }))
vi.mock('../src/modules/agent/model/audio-analysis', () => ({ analyzeAudioFile: api.analyse, cancelAnalysis: api.cancel, forgetAnalysis: api.forget, MAX_REFERENCE_BYTES: 20 * 1024 * 1024 }))
vi.mock('../src/modules/agent/api/reference', () => ({ requestReferenceAnalysis: vi.fn(async () => undefined) }))
import AgentStudioPage from '../src/modules/agent/pages/AgentStudioPage.vue'
import { useAgentConversation } from '../src/modules/agent/composables/useAgentConversation'
import { usePlayer } from '../src/shared/player/store'
import { setLocale } from '../src/shared/i18n'
import { ApiError } from '../src/shared/http/api-client'
import type { ConversationSnapshot, Message, SongCard } from '../src/modules/agent/model/conversation'

const song: SongCard = { songId: 'work-real', title: '给妈妈的歌', style: '钢琴民谣', lyrics: '妈妈的灯\n照亮回家的路', audioUrl: '/api/app/works/work-real/audio/asset-real', status: 'SUCCEEDED', coverUrl: null, revision: 2, revisionId: 'revision-real', assetId: 'asset-real', taskId: null }
const message = (text: string, seq = 1, role: Message['role'] = 'assistant'): Message => ({ id: `m-${seq}`, conversationId: 'c1', seq, role, kind: 'TEXT', state: 'DONE', contentJson: JSON.stringify({ text }), taskId: null, createdAt: '2026-11-12T00:00:00Z' })
const state = (overrides: Partial<ConversationSnapshot> = {}): ConversationSnapshot => ({ messages: [], songCard: null, taskId: null, action: null, shareUrl: null, liked: false, ...overrides })
const wrappers: Array<{ unmount(): void }> = []
async function page(snapshot = state()) {
  api.state.mockResolvedValue(snapshot)
  const pinia = createPinia(); setActivePinia(pinia)
  const player = usePlayer(); const toggle = vi.spyOn(player, 'toggle').mockResolvedValue()
  const wrapper = mount(AgentStudioPage, { global: { plugins: [pinia] } }); wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, player, toggle }
}
beforeEach(() => {
  vi.clearAllMocks(); sessionStorage.clear(); setLocale('zh-CN')
  api.current.mockResolvedValue({ id: 'c1', currentWorkId: null })
  api.state.mockResolvedValue(state()); api.send.mockResolvedValue(state())
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount(); vi.useRealTimers() })

describe('真实 Agent 对话与作品', () => {
  it('中文输入法选词的Enter不会提前发送，确认输入后的Enter才提交', async () => {
    const { wrapper } = await page()
    const input = wrapper.find('textarea')
    await input.setValue('给妈妈写一首歌')
    await input.trigger('keydown', { key: 'Enter', isComposing: true }); await flushPromises()
    expect(api.send).not.toHaveBeenCalled()
    await input.trigger('keydown', { key: 'Enter', isComposing: false }); await flushPromises()
    expect(api.send).toHaveBeenCalledWith('c1', '给妈妈写一首歌', expect.any(String), 'zh-CN')
  })
  it('问候调用真实消息接口，不生成本地创作方案或假歌曲', async () => {
    api.send.mockResolvedValue(state({ messages: [message('你好', 1, 'user'), message('你好！我可以和你聊天、一起写歌。', 2)] }))
    const { wrapper } = await page()
    expect(wrapper.find('.agent-empty-song').exists()).toBe(true)
    expect(wrapper.find('.agent-preview-play').exists()).toBe(false)
    await wrapper.find('textarea').setValue('你好')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(api.send).toHaveBeenCalledWith('c1', '你好', expect.stringMatching(/^[\da-f-]{36}$/), 'zh-CN')
    expect(wrapper.text()).toContain('你好！我可以和你聊天、一起写歌。')
    expect(wrapper.find('.agent-brief-card').exists()).toBe(false)
    expect(wrapper.find('.agent-preview-play').exists()).toBe(false)
  })
  it('刷新恢复服务端消息、作品歌词和点赞状态；播放对象就是展示的作品版本', async () => {
    const { wrapper, toggle } = await page(state({ messages: [message('歌词已写好')], songCard: song, liked: true }))
    expect(wrapper.find('.agent-preview-meta strong').text()).toBe('给妈妈的歌')
    expect(wrapper.find('.agent-real-lyrics').text()).toContain('妈妈的灯\n照亮回家的路')
    expect(wrapper.find('[aria-pressed="true"]').text()).toBe('已喜欢')
    await wrapper.find('.agent-preview-play').trigger('click')
    expect(toggle).toHaveBeenCalledWith(expect.objectContaining({ id: 'work-real:revision-real:asset-real', title: '给妈妈的歌', audio: expect.stringContaining('/api/app/works/work-real/audio/asset-real'), provenance: 'GENERATED' }))
    expect(wrapper.text()).not.toContain('示例歌词')
  })
  it('分享和点赞按钮走对话，显示服务端真实分享链接', async () => {
    const { wrapper } = await page(state({ songCard: song }))
    api.send.mockResolvedValue(state({ songCard: song, liked: true, action: 'like' }))
    await wrapper.find('.agent-preview-actions button').trigger('click'); await flushPromises()
    expect(api.send.mock.calls[0]?.[1]).toBe('给当前作品点赞')
    expect(wrapper.find('[aria-pressed="true"]').exists()).toBe(true)
    api.send.mockResolvedValue(state({ songCard: song, liked: true, action: 'share', shareUrl: '/gift/real-token' }))
    await wrapper.findAll('.agent-preview-actions button')[2]!.trigger('click'); await flushPromises()
    expect(api.send.mock.calls[1]?.[1]).toBe('分享当前作品')
    expect(wrapper.find('.agent-share-link a').attributes('href')).toMatch(/\/gift\/real-token$/)
  })
  it('只有歌词时没有可播放的试听曲，生成音乐仍在同一会话完成', async () => {
    const { wrapper } = await page(state({ songCard: { ...song, audioUrl: '', status: 'DRAFT' } }))
    expect(wrapper.find('.agent-preview-play').exists()).toBe(false)
    expect(wrapper.findAll('.agent-preview-actions button')[1]!.attributes('disabled')).toBeDefined()
    api.send.mockResolvedValue(state({ songCard: { ...song, audioUrl: '', status: 'QUEUED' }, taskId: 'task-1', messages: [message('已提交音乐生成。')] }))
    await wrapper.find('.agent-start-button').trigger('click'); await flushPromises()
    expect(api.send.mock.calls[0]?.[1]).toBe('用当前歌词生成音乐')
    expect(wrapper.text()).toContain('音乐生成中，完成后会自动更新')
    expect(wrapper.text()).not.toContain('正在创作歌词')
  })
  it('不把恶意链接绑定到音频或分享控件', async () => {
    const { wrapper } = await page(state({ songCard: { ...song, audioUrl: 'javascript:alert(1)' }, shareUrl: 'javascript:alert(2)' }))
    expect(wrapper.find('.agent-preview-play').exists()).toBe(false)
    expect(wrapper.find('.agent-share-link').exists()).toBe(false)
  })
  it('文本播放不会暂停正在播放的同一作品；下载请求服务端附件响应', async () => {
    const { wrapper, player, toggle } = await page(state({ songCard: song }))
    player.current = { id: 'work-real:revision-real:asset-real', title: song.title, cover: '', audio: new URL(song.audioUrl, window.location.origin).href }
    player.playing = true
    api.send.mockResolvedValue(state({ songCard: song, action: 'play' }))
    await wrapper.find('textarea').setValue('播放当前作品'); await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(toggle).not.toHaveBeenCalled()
    const clicked: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { clicked.push(this.href) })
    api.send.mockResolvedValue(state({ songCard: song, action: 'download' }))
    await wrapper.findAll('.agent-preview-actions button')[1]!.trigger('click'); await flushPromises()
    expect(clicked[0]).toContain('/work-real/audio/asset-real?download=true')
    click.mockRestore()
  })
})

describe('会话恢复与任务生命周期', () => {
  it('切换账户时不携带旧请求，并忽略旧账户的迟到结果', async () => {
    let resolveOld!: (value: ConversationSnapshot) => void
    const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
    await conversation.refresh()
    api.send.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const sending = conversation.send('旧账户的私密歌词', 'zh-CN')
    await flushPromises()
    api.current.mockResolvedValue({ id: 'c2', currentWorkId: null })
    api.state.mockResolvedValue(state())
    await conversation.changeIdentity()
    expect(conversation.pending.value).toBeNull()
    resolveOld(state({ messages: [message('旧账户的私密歌词')], songCard: song }))
    await sending
    expect(conversation.conversationId.value).toBe('c2')
    expect(conversation.messages.value).toEqual([])
    expect(conversation.songCard.value).toBeNull()
    expect(sessionStorage.getItem('qingfeng.agent.pending.c1')).toContain('旧账户的私密歌词')
    scope.stop()
  })
  it('明确400拒绝可以修改内容，网络不明和409处理中不能丢弃请求键', async () => {
    const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
    await conversation.refresh()
    api.send.mockRejectedValueOnce(new ApiError('消息太长', 400))
    await conversation.send('不合要求的内容', 'zh-CN')
    expect(conversation.editRejected()).toBe('不合要求的内容')
    api.send.mockRejectedValueOnce(new ApiError('原请求处理中', 409, 'AGENT_TURN_IN_PROGRESS'))
    await conversation.send('正确内容', 'zh-CN')
    expect(conversation.editRejected()).toBeUndefined()
    expect(conversation.pending.value?.text).toBe('正确内容')
    scope.stop()
  })
  it('显式清空成功后移除未确认请求；不会发送取消音乐操作', async () => {
    const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
    await conversation.refresh()
    api.send.mockRejectedValueOnce(new Error('网络中断'))
    await conversation.send('写词', 'zh-CN')
    api.reset.mockResolvedValue(undefined)
    expect(await conversation.reset()).toBe(true)
    expect(api.reset).toHaveBeenCalledWith('c1')
    expect(conversation.pending.value).toBeNull()
    expect(sessionStorage.getItem('qingfeng.agent.pending.c1')).toBeNull()
    expect(api.send).toHaveBeenCalledTimes(1)
    scope.stop()
  })
  it('超时重试使用同一请求键，跨页面刷新仍保留，不创建第二次收费请求', async () => {
    api.send.mockRejectedValueOnce(new Error('请求超时'))
    let scope = effectScope(); let conversation = scope.run(() => useAgentConversation())!
    await conversation.refresh(); await conversation.send('生成音乐', 'zh-CN')
    const key = api.send.mock.calls[0]?.[2]
    expect(conversation.pending.value?.requestKey).toBe(key)
    scope.stop()
    scope = effectScope(); conversation = scope.run(() => useAgentConversation())!
    await conversation.refresh()
    expect(conversation.pending.value?.requestKey).toBe(key)
    api.send.mockResolvedValue(state({ songCard: song }))
    await conversation.retry()
    expect(api.send.mock.calls[1]?.[2]).toBe(key)
    expect(conversation.pending.value).toBeNull()
    expect(sessionStorage.getItem('qingfeng.agent.pending.c1')).toBeNull()
    scope.stop()
  })
  it('网络未确认时阻止另起请求；请求完成后才允许下一轮', async () => {
    const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
    await conversation.refresh()
    api.send.mockRejectedValueOnce(new Error('网络中断'))
    await conversation.send('写词', 'zh-CN'); await conversation.send('再生成', 'zh-CN')
    expect(api.send).toHaveBeenCalledTimes(1)
    scope.stop()
  })
  it('恢复排队任务并轮询真实结果，成功后停止轮询', async () => {
    vi.useFakeTimers()
    const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
    api.state.mockResolvedValueOnce(state({ songCard: { ...song, audioUrl: '', status: 'RUNNING' }, taskId: 'task-1' })).mockResolvedValue(state({ songCard: song }))
    await conversation.refresh(); expect(conversation.taskRunning.value).toBe(true)
    await vi.advanceTimersByTimeAsync(3100)
    expect(conversation.songCard.value?.audioUrl).toBe(song.audioUrl)
    expect(conversation.taskRunning.value).toBe(false)
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.state).toHaveBeenCalledTimes(2)
    scope.stop()
  })
  it('新消息期间到达的旧轮询不能覆盖新的作品', async () => {
    vi.useFakeTimers()
    let resolvePoll!: (value: ConversationSnapshot) => void
    const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
    api.state.mockResolvedValueOnce(state({ songCard: { ...song, audioUrl: '', status: 'RUNNING' }, taskId: 'task-1' }))
      .mockImplementationOnce(() => new Promise(resolve => { resolvePoll = resolve }))
    await conversation.refresh(); await vi.advanceTimersByTimeAsync(3100)
    api.send.mockResolvedValue(state({ songCard: { ...song, title: '新的作品' } }))
    await conversation.send('修改标题', 'zh-CN')
    resolvePoll(state({ songCard: song })); await flushPromises()
    expect(conversation.songCard.value?.title).toBe('新的作品')
    scope.stop()
  })
  it('服务端失败或取消状态不继续显示假进度', async () => {
    vi.useFakeTimers()
    for (const status of ['FAILED', 'CANCELLED', 'READY']) {
      const scope = effectScope(), conversation = scope.run(() => useAgentConversation())!
      api.state.mockResolvedValue(state({ songCard: { ...song, status, audioUrl: '' }, taskId: 'finished-task' }))
      await conversation.refresh(); expect(conversation.taskRunning.value).toBe(false)
      scope.stop()
    }
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.state).toHaveBeenCalledTimes(3)
  })
})
