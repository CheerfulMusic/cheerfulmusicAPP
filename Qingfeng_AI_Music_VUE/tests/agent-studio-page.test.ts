// @vitest-environment happy-dom
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

const spies = vi.hoisted(() => ({
  push: vi.fn(),
  recordPlay: vi.fn(),
  loadMyReactions: vi.fn(() => Promise.resolve()),
}))
const analysisSpies = vi.hoisted(() => ({
  analyzeAudioFile: vi.fn(),
  cancelAnalysis: vi.fn(),
  forgetAnalysis: vi.fn(),
}))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: spies.push }) }))
vi.mock('../src/modules/home/model/hot-songs', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/modules/home/model/hot-songs')>()
  return { ...actual, recordPlay: spies.recordPlay, loadMyReactions: spies.loadMyReactions }
})
// 只替换「解码 + Worker」这一层，DSP 本身由 tests/audio-features.test.ts 用真实合成信号覆盖。
vi.mock('../src/modules/agent/model/audio-analysis', async importOriginal => {
  const actual = await importOriginal<typeof import('../src/modules/agent/model/audio-analysis')>()
  return { ...actual, analyzeAudioFile: analysisSpies.analyzeAudioFile, cancelAnalysis: analysisSpies.cancelAnalysis, forgetAnalysis: analysisSpies.forgetAnalysis }
})

import AgentStudioPage from '../src/modules/agent/pages/AgentStudioPage.vue'
import { AudioAnalysisError } from '../src/modules/agent/model/audio-analysis'
import type { AudioFeatures } from '../src/modules/agent/model/audio-features'
import { setLocale } from '../src/shared/i18n'
import { usePlayer } from '../src/shared/player/store'

const FEATURES: AudioFeatures = {
  duration: 128, sampleRate: 22050, mono: true, bpm: 120.2, bpmConfidence: 0.92,
  rms: 0.31, peak: 0.87, dynamicRange: 0.42, bands: { low: 0.52, mid: 0.31, high: 0.17 },
  brightness: 0.22, noisiness: 0.18, energyCurve: new Array(8).fill(0.5), analyzedSeconds: 90,
}

const createObjectURL = vi.fn(() => 'blob:agent-reference')
const revokeObjectURL = vi.fn(() => undefined)
const originals = {
  create: (URL as unknown as { createObjectURL?: unknown }).createObjectURL,
  revoke: (URL as unknown as { revokeObjectURL?: unknown }).revokeObjectURL,
}

/** 只用于替代浏览器解码：metadata 一定读得到，便于断言时长确实进了 brief。 */
class FakeAudio {
  duration = 128.5
  preload = ''
  onloadedmetadata: (() => void) | null = null
  onerror: (() => void) | null = null
  removeAttribute() { /* 探测元素无需真正卸载音源 */ }
  set src(_value: string) { void Promise.resolve().then(() => this.onloadedmetadata?.()) }
}

async function flush() {
  await Promise.resolve()
  await nextTick()
  await Promise.resolve()
  await nextTick()
}
const wait = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

async function mountPage() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const player = usePlayer()
  const toggle = vi.spyOn(player, 'toggle').mockImplementation(() => Promise.resolve())
  const wrapper = mount(AgentStudioPage, { global: { plugins: [pinia] } })
  await flush()
  return { wrapper, player, toggle }
}

/** 参考音乐现在由输入栏曲别针触发隐藏的 file input；这里直接对其派发 change，等价于点击上传。 */
async function attach(wrapper: VueWrapper, file: File | Record<string, unknown>) {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flush()
}

/** 发送一条需求并等待创作方案卡片出现；返回卡片里进入创作页的入口（尾句按钮）。 */
async function sendAndGetCreateEntry(wrapper: VueWrapper, text: string): Promise<DOMWrapper<Element>> {
  await wrapper.find('.agent-composer-box input').setValue(text)
  await wrapper.find('form.agent-composer-box').trigger('submit')
  await wait(480)
  await flush()
  const cards = wrapper.findAll('.agent-plan')
  return cards[cards.length - 1]!.find('.agent-plan-tail')
}

/** 打开输入框左侧的「创作偏好」菜单，模式与兴趣标签都收在这里。 */
async function openTools(wrapper: VueWrapper) {
  await wrapper.find('.agent-composer-plus').trigger('click')
  await flush()
}

function storedBrief() {
  const raw = sessionStorage.getItem('qingfeng.agent.brief')
  return raw ? JSON.parse(raw) as { story: string; style: string; reference?: Record<string, unknown>; params?: Record<string, unknown> } : undefined
}

beforeAll(() => {
  Object.assign(URL, { createObjectURL, revokeObjectURL })
  vi.stubGlobal('Audio', FakeAudio)
})
afterAll(() => {
  Object.assign(URL, { createObjectURL: originals.create, revokeObjectURL: originals.revoke })
  vi.unstubAllGlobals()
})
beforeEach(() => {
  sessionStorage.clear()
  spies.push.mockClear()
  spies.recordPlay.mockClear()
  spies.loadMyReactions.mockClear()
  createObjectURL.mockClear()
  revokeObjectURL.mockClear()
  analysisSpies.analyzeAudioFile.mockReset()
  analysisSpies.analyzeAudioFile.mockResolvedValue({ features: FEATURES, key: 'hash-1' })
  analysisSpies.cancelAnalysis.mockClear()
  analysisSpies.forgetAnalysis.mockClear()
  setLocale('zh-CN')
})

describe('Agent 创作页交互回归（设计稿：开场白 + 对话 + 作品卡）', () => {
  it('进入页面读取点赞关系，右栏作品卡片复用既有本地化预览数据源', async () => {
    const { wrapper } = await mountPage()
    expect(spies.loadMyReactions).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.agent-preview-title').text()).toBe('把平凡唱给你')
    expect(wrapper.find('.agent-preview-sub').text()).toBe('原创歌曲 · 爱情主题')
    expect(wrapper.find('.agent-preview-actions button').attributes('aria-pressed')).toBe('false')
  })

  it('开场白、底部提示与右栏标题都走词条，切语言实时生效', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.agent-hero h1').text()).toBe('今天，想创作一首什么歌？')
    expect(wrapper.find('.agent-hero p').text()).toBe('告诉我你的灵感，剩下的交给我。')
    expect(wrapper.find('.agent-hint').text()).toContain('AI 可以理解灵感、写歌词、编曲并生成歌曲')
    setLocale('en')
    await nextTick()
    expect(wrapper.find('.agent-hero h1').text()).toBe('What kind of song do you want to create today?')
    expect(wrapper.find('.agent-hero p').text()).toBe('Tell me your inspiration and I will handle the rest.')
    expect(wrapper.find('.agent-hint').text()).toContain('AI understands your inspiration')
    expect(wrapper.find('.agent-preview-title').text()).toBe('Love in the Everyday')
  })

  it('设计稿没有的模块不再出现：快速灵感卡、模式行、旧标语、作品卡头部', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.agent-quick-card').exists()).toBe(false)
    expect(wrapper.find('.agent-modes').exists()).toBe(false)
    expect(wrapper.find('.agent-slogan').exists()).toBe(false)
    expect(wrapper.find('.agent-preview-head').exists()).toBe(false)
    expect(wrapper.find('.agent-preview-count').exists()).toBe(false)
    expect(wrapper.find('.agent-conversation-head').exists()).toBe(false)
  })

  it('用户消息在右、助手消息在左，双方各自带头像', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.agent-composer-box input').setValue('我想写一首关于末班车和离别的抒情歌')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    const user = wrapper.find('.agent-message.user')
    expect(user.exists()).toBe(true)
    expect(user.find('.agent-user-avatar').exists()).toBe(true)
    expect(user.find('.agent-bot-avatar').exists()).toBe(false)
    const assistant = wrapper.find('.agent-message.assistant')
    expect(assistant.find('.agent-bot-avatar').exists()).toBe(true)
    expect(assistant.find('.agent-user-avatar').exists()).toBe(false)
    expect(assistant.find('.agent-bubble p').text()).toBe('我理解了，这是一首关于离别的温柔抒情歌曲。')
  })

  it('创作方案卡片按设计稿给出一行参数与下一步文案', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.agent-composer-box input').setValue('写一首 90 BPM 的慢歌，温柔女声，钢琴和弦乐，粤语演唱')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    const plan = wrapper.find('.agent-plan')
    expect(plan.exists()).toBe(true)
    expect(plan.find('.agent-plan-head').text()).toContain('创作方案')
    const params = plan.find('.agent-plan-params').text()
    expect(params).toContain('曲风：')
    expect(params).toContain('人声：')
    expect(params).toContain('情绪：')
    expect(params).toContain('节奏：')
    expect(params).toContain('女声')
    // 设计稿的方案卡只有四枚定性参数：BPM 精确值不再占展示位，由 params 传给创作页。
    expect(params).toContain('中速')
    expect(plan.find('.agent-plan-tail').text()).toContain('接下来，我为你创作歌词，并生成歌曲')
  })

  it('设计稿没有的按钮不再出现：方案卡不再有独立操作行', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.agent-composer-box input').setValue('我想写一首关于末班车和离别的抒情歌')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    expect(wrapper.find('.agent-plan-actions').exists()).toBe(false)
    expect(wrapper.find('.agent-plan-tags').exists()).toBe(false)
    expect(wrapper.find('.agent-bubble-tail').exists()).toBe(false)
    // 进入创作页的入口保留在方案卡尾句上，外观仍是设计稿里的一句普通文字。
    expect(wrapper.find('.agent-plan-tail').exists()).toBe(true)
  })

  it('右栏作品卡按设计稿给出标题、副标题、播放行与三按钮，歌词可展开', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.agent-preview-cover img').exists()).toBe(true)
    expect(wrapper.find('.agent-preview-playrow .agent-preview-play').exists()).toBe(true)
    expect(wrapper.find('.agent-preview-time').text()).toContain('/')
    const actions = wrapper.findAll('.agent-preview-actions > *')
    expect(actions).toHaveLength(3)
    expect(actions[0]!.text()).toContain('点赞')
    expect(actions[1]!.text()).toContain('下载')
    expect(actions[2]!.text()).toContain('分享')
    const toggle = wrapper.find('.agent-preview-lyrics-head button')
    expect(toggle.text()).toContain('展开')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    await toggle.trigger('click')
    await flush()
    expect(wrapper.find('.agent-preview-lyrics-head button').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.agent-preview-lyrics-head button').text()).toContain('收起')
  })

  it('右栏作品名与副标题来自这条需求，不再出现半截标题', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.agent-composer-box input').setValue('我想写一首关于末班车和离别的抒情歌')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    expect(wrapper.find('.agent-preview-title').text()).toBe('末班车')
    expect(wrapper.find('.agent-preview-sub').text()).toBe('原创歌曲 · 温柔女声')
    const lyrics = wrapper.findAll('.agent-preview-lyrics p').map(node => node.text())
    expect(lyrics).toEqual(['黄昏落在站台尽头', '最后一班车缓缓驶走', '那句未说出口的再见…'])
  })

  it('创作偏好收在输入框左侧的加号菜单里，不占页面主体', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.agent-composer-menu').exists()).toBe(false)
    await openTools(wrapper)
    expect(wrapper.find('.agent-composer-menu').exists()).toBe(true)
    expect(wrapper.findAll('.agent-menu-modes button')).toHaveLength(5)
    expect(wrapper.findAll('.agent-tag-list button').length).toBeGreaterThan(0)
  })

  it('busy 期间再次发送给出可见提示，不清空输入也不新增消息', async () => {
    const { wrapper } = await mountPage()
    const input = wrapper.find('.agent-composer-box input')
    const form = wrapper.find('form.agent-composer-box')
    await input.setValue('第一句需求')
    await form.trigger('submit')
    expect(wrapper.find('.agent-composer-send').attributes('aria-busy')).toBe('true')
    expect(wrapper.find('.agent-typing').text()).toContain('正在创作歌词')
    await input.setValue('第二句需求')
    await form.trigger('submit')
    expect(wrapper.find('.agent-voice-hint').text()).toContain('上一条还在整理')
    expect((input.element as HTMLInputElement).value).toBe('第二句需求')
    expect(wrapper.findAll('.agent-message.user')).toHaveLength(1)
  })

  it('每个创作方案卡片只提交自己的需求，最新一条不被旧 brief 覆盖', async () => {
    const { wrapper } = await mountPage()
    const input = wrapper.find('.agent-composer-box input')
    await input.setValue('写一首关于旅行的歌')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    expect(wrapper.find('.agent-plan').exists()).toBe(true)
    expect(storedBrief()).toBeUndefined()
    await input.setValue('用粤语唱一首情歌')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    const create = await sendAndGetCreateEntry(wrapper, '用粤语唱一首情歌')
    await create.trigger('click')
    await flush()
    const brief = storedBrief()
    expect(brief?.story).toContain('用粤语唱一首情歌')
    expect(brief?.story).not.toContain('写一首关于旅行的歌')
    expect(brief?.style).toBe('复古港风')
    expect(spies.push).toHaveBeenCalledWith({ path: '/create', query: { from: 'agent' } })
  })

  it('参考音乐支持上传、文案为 M4A，并真正写进后续创作描述', async () => {
    const { wrapper } = await mountPage()
    expect(wrapper.find('.agent-composer-clip').exists()).toBe(true)
    await attach(wrapper, new File(['fake'], 'ref.m4a', { type: 'audio/mp4' }))
    await flush()
    expect(wrapper.find('.agent-upload-file span').text()).toBe('ref.m4a')
    expect(wrapper.find('.agent-upload-file small').text()).toBe('02:08')
    expect(createObjectURL).toHaveBeenCalled()
    expect(wrapper.find('.agent-voice-hint').text()).toContain('已加入参考音乐')
    const create = await sendAndGetCreateEntry(wrapper, '写一首关于旅行的歌')
    await create.trigger('click')
    await flush()
    const brief = storedBrief()
    expect(brief?.story).toContain('参考音乐《ref.m4a》')
    expect(brief?.story).toContain('时长 02:08')
    expect(brief?.reference).toMatchObject({ name: 'ref.m4a', size: 4, type: 'audio/mp4' })
    expect((brief?.reference as { features?: AudioFeatures }).features?.bpm).toBe(120.2)
    expect(brief?.story).toContain('约 120 BPM')
  })

  it('不支持的格式与超大文件给出明确报错且不接管', async () => {
    const { wrapper } = await mountPage()
    await attach(wrapper, new File(['x'], 'note.txt', { type: 'text/plain' }))
    expect(wrapper.find('.agent-upload-error').text()).toContain('只支持 MP3、WAV、M4A 等音频文件')
    await attach(wrapper, { name: 'big.mp3', type: 'audio/mpeg', size: 30 * 1024 * 1024 })
    expect(wrapper.find('.agent-upload-error').text()).toContain('20MB')
    expect(wrapper.find('.agent-upload-file').exists()).toBe(false)
  })

  it('移除参考音乐时先停播再释放 Object URL', async () => {
    const { wrapper, player } = await mountPage()
    await attach(wrapper, new File(['fake'], 'ref.mp3', { type: 'audio/mpeg' }))
    player.current = { id: 'agent-upload', title: 'ref.mp3', cover: '/media/cover.svg', audio: 'blob:agent-reference' }
    const close = vi.spyOn(player, 'close')
    await wrapper.find('.agent-upload-file button[aria-label="移除"]').trigger('click')
    expect(close).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:agent-reference')
    expect(wrapper.find('.agent-upload-file').exists()).toBe(false)
  })

  it('上传后展示可信特征，分析结果进入 brief', async () => {
    const { wrapper } = await mountPage()
    await attach(wrapper, new File(['fake'], 'ref.mp3', { type: 'audio/mpeg' }))
    await flush()
    await flush()
    const chip = wrapper.find('.agent-analysis').text()
    expect(chip).toContain('120 BPM')
    expect(chip).toContain('低频偏重')
    const create = await sendAndGetCreateEntry(wrapper, '写一首关于旅行的歌')
    await create.trigger('click')
    await flush()
    expect(storedBrief()?.story).toContain('约 120 BPM')
  })

  it('识别出的结构化参数会展示并交给创作页', async () => {
    const { wrapper } = await mountPage()
    await wrapper.find('.agent-composer-box input').setValue('写一首 90 BPM 的慢歌，温柔女声，钢琴和弦乐，粤语演唱')
    await wrapper.find('form.agent-composer-box').trigger('submit')
    await wait(480)
    await flush()
    const params = wrapper.find('.agent-plan-params').text()
    expect(params).toContain('女声')
    expect(params).toContain('中速')
    const create = await sendAndGetCreateEntry(wrapper, '写一首 90 BPM 的慢歌，温柔女声，钢琴和弦乐，粤语演唱')
    await create.trigger('click')
    await flush()
    expect(storedBrief()?.params).toMatchObject({ bpm: 90, vocal: 'FEMALE', language: 'yue' })
  })

  it('分析失败时给出提示但仍可创作', async () => {
    analysisSpies.analyzeAudioFile.mockRejectedValue(new AudioAnalysisError('DECODE_FAILED', 'x'))
    const { wrapper } = await mountPage()
    await attach(wrapper, new File(['fake'], 'ref.mp3', { type: 'audio/mpeg' }))
    await flush()
    await flush()
    expect(wrapper.find('.agent-analysis.is-failed').text()).toContain('无法解码')
    const create = await sendAndGetCreateEntry(wrapper, '写一首关于旅行的歌')
    await create.trigger('click')
    await flush()
    const brief = storedBrief()
    expect(brief?.story).toContain('参考音乐《ref.mp3》')
    expect(brief?.story).not.toContain('BPM')
    expect(spies.push).toHaveBeenCalled()
  })

  it('快速换文件时旧文件的结果不会覆盖新文件', async () => {
    let resolveFirst: ((value: { features: AudioFeatures; key: string }) => void) | undefined
    analysisSpies.analyzeAudioFile
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockResolvedValueOnce({ features: { ...FEATURES, bpm: 90.4 }, key: 'hash-2' })
    const { wrapper } = await mountPage()
    await attach(wrapper, new File(['a'], 'first.mp3', { type: 'audio/mpeg' }))
    await attach(wrapper, new File(['bb'], 'second.mp3', { type: 'audio/mpeg' }))
    await flush()
    resolveFirst?.({ features: FEATURES, key: 'hash-1' })
    await flush()
    expect(wrapper.find('.agent-analysis').text()).toContain('90 BPM')
    expect(wrapper.find('.agent-analysis').text()).not.toContain('120 BPM')
  })

  it('重复选择同一个文件也会重新触发分析', async () => {
    const { wrapper } = await mountPage()
    const input = wrapper.find('input[type="file"]')
    const file = new File(['same'], 'same.mp3', { type: 'audio/mpeg' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flush()
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flush()
    expect(analysisSpies.analyzeAudioFile).toHaveBeenCalledTimes(2)
  })

  it('分析未完成时开始创作，最多等 1.5 秒后带元信息继续', async () => {
    analysisSpies.analyzeAudioFile.mockImplementation(() => new Promise(() => undefined))
    const { wrapper } = await mountPage()
    await attach(wrapper, new File(['fake'], 'slow.mp3', { type: 'audio/mpeg' }))
    await flush()
    // 先以真实计时器把需求发出去，等创作方案卡片出现（不需要 fake timer）。
    const create = await sendAndGetCreateEntry(wrapper, '写一首关于旅行的歌')
    vi.useFakeTimers()
    await create.trigger('click')
    await flush()
    // 分析还没回来：此刻不应该写 brief，说明确实在等而不是直接跳过。
    expect(storedBrief()).toBeUndefined()
    await vi.advanceTimersByTimeAsync(1600)
    await flush()
    vi.useRealTimers()
    const brief = storedBrief()
    expect(brief?.story).toContain('参考音乐《slow.mp3》')
    expect((brief?.reference as { features?: AudioFeatures }).features).toBeUndefined()
    expect(spies.push).toHaveBeenCalledWith({ path: '/create', query: { from: 'agent' } })
  }, 8000)

  it('清除参考音乐会取消分析并清掉缓存引用', async () => {
    const { wrapper } = await mountPage()
    await attach(wrapper, new File(['fake'], 'ref.mp3', { type: 'audio/mpeg' }))
    await flush()
    await flush()
    await wrapper.find('.agent-upload-file button[aria-label="移除"]').trigger('click')
    await flush()
    expect(analysisSpies.cancelAnalysis).toHaveBeenCalled()
    expect(analysisSpies.forgetAnalysis).toHaveBeenCalledWith('hash-1')
    expect(wrapper.find('.agent-analysis').exists()).toBe(false)
    expect(wrapper.find('.agent-upload-file').exists()).toBe(false)
  })

  it('页面卸载时取消分析线程', async () => {
    const { wrapper } = await mountPage()
    wrapper.unmount()
    expect(analysisSpies.cancelAnalysis).toHaveBeenCalled()
  })

  it('只有真正开始试听才计播放次数', async () => {
    const { wrapper, toggle } = await mountPage()
    await wrapper.find('.agent-preview-play').trigger('click')
    expect(toggle).toHaveBeenCalledTimes(1)
    expect(spies.recordPlay).toHaveBeenCalledWith('love')
  })

  it('连点同一个模式标签只给一次提示，五个标签各给一次且不触发发送', async () => {
    const { wrapper } = await mountPage()
    await openTools(wrapper)
    const modes = wrapper.findAll('.agent-menu-modes button')
    expect(modes).toHaveLength(5)
    expect(modes.every(button => button.attributes('type') === 'button')).toBe(true)
    const initial = wrapper.findAll('.agent-message').length
    for (let i = 0; i < 5; i++) {
      await modes[0]!.trigger('click')
      await flush()
      expect(wrapper.findAll('.agent-message')).toHaveLength(initial + 1)
    }
    expect(modes[0]!.attributes('aria-pressed')).toBe('true')
    for (let i = 1; i < modes.length; i++) {
      await modes[i]!.trigger('click')
      await flush()
    }
    expect(wrapper.findAll('.agent-message')).toHaveLength(initial + modes.length)
    // 回头再点第一个模式也不重复追加，且标签点击绝不会变成一次发送。
    await modes[0]!.trigger('click')
    await flush()
    expect(wrapper.findAll('.agent-message')).toHaveLength(initial + modes.length)
    expect(wrapper.findAll('.agent-message.user')).toHaveLength(0)
  })

  it('输入区按设计稿是上下两行：上面输入文字，下面三个圆形工具按钮', async () => {
    const { wrapper } = await mountPage()
    const input = wrapper.find('.agent-composer-box input[type="text"]')
    expect(input.exists()).toBe(true)
    expect(wrapper.find('.agent-composer-box textarea').exists()).toBe(false)
    expect(input.attributes('placeholder')).toBe('告诉我你的创作需求...')
    const tools = wrapper.find('.agent-composer-tools')
    expect(tools.find('.agent-composer-plus').exists()).toBe(true)
    expect(tools.find('.agent-composer-clip').exists()).toBe(true)
    expect(tools.find('.agent-composer-mic').exists()).toBe(true)
    // 三个工具按钮都排在输入行下方，发送键单独占右侧一列。
    const order = wrapper.find('.agent-composer-box input[type="text"]').element.compareDocumentPosition(tools.element)
    expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(wrapper.find('.agent-composer-send').exists()).toBe(true)
  })
})
