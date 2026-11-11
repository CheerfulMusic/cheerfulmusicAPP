<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { defaultCover, locale, t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { usePreviewTracks } from '../../home/model/tracks'
import { isLiked, isPending, loadMyReactions, recordPlay, signedIn, toggleReaction } from '../../home/model/hot-songs'
import { rewriteInspiration } from '../../studio/api/works'
import { ApiError } from '../../../shared/http/api-client'
import CreditCostHint from '../../../shared/credits/CreditCostHint.vue'
import { useCredits } from '../../../shared/credits/useCredits'
import { guardCredits } from '../../../shared/credits/credit-gate'
import { writeAgentBrief } from '../model/brief'
import { AudioAnalysisError, analyzeAudioFile, cancelAnalysis, forgetAnalysis } from '../model/audio-analysis'
import { requestReferenceAnalysis } from '../api/reference'
import { analyzeRequest, bandWord, hasParams, interestTags, modeOptions, quickPrompts, toneWord, withReference, type AgentBrief, type AgentMode, type AgentParams, type AgentReference, type ReferenceInsight } from '../model/assistant'

interface Message { id: number; role: 'assistant' | 'user'; text: string; greeting?: boolean; brief?: AgentBrief; suggestions?: string[]; polishing?: boolean; polishError?: string; polishCreditShort?: boolean }
interface VoiceResult { results: ArrayLike<ArrayLike<{ transcript: string }>> }
interface VoiceRecognition { lang: string; interimResults: boolean; onresult: ((event: VoiceResult) => void) | null; onerror: (() => void) | null; onend: (() => void) | null; start: () => void; stop?: () => void }
interface UploadState { name: string; url: string; size: number; type: string; duration: number }
interface ReferenceAnalysisState {
  status: 'idle' | 'analyzing' | 'ready' | 'failed'
  key?: string
  features?: import('../model/audio-features').AudioFeatures
  insight?: ReferenceInsight
  insightStatus: 'idle' | 'loading' | 'ready'
  failure?: string
}

const router = useRouter(), player = usePlayer()
const uploadTrackId = 'agent-upload'
const maxUploadBytes = 20 * 1024 * 1024
const greetingLines = computed(() => [t('你好！我是你的音乐创作助手~'), t('告诉我你的心情、兴趣，或一个小故事，'), t('我来为你生成专属的歌曲 ♫')])
let messageId = 0, replyTimer: number | undefined, noticeTimer: number | undefined, recognition: VoiceRecognition | undefined
const messages = ref<Message[]>([{ id: messageId, role: 'assistant', text: '', greeting: true }])
const chat = ref<HTMLElement>(), fileInput = ref<HTMLInputElement>()
const input = ref(''), busy = ref(false), notice = ref(''), voiceHint = ref('')
const { credits, refreshCredits } = useCredits()
const polishKey = ref('')
const mode = ref<AgentMode>('write'), interests = ref<string[]>(['时尚'])
const brief = ref<AgentBrief | undefined>()
const uploaded = ref<UploadState | undefined>(), uploadError = ref('')
const showInterests = ref(false)
// 分析只在本机进行；每次换文件或清除都会让 token 失效，旧结果不可能覆盖新文件。
const analysis = ref<ReferenceAnalysisState>({ status: 'idle', insightStatus: 'idle' })
let analysisToken = 0, analysisPromise: Promise<void> | undefined
const placeholder = computed(() => {
  const option = modeOptions.find(item => item.id === mode.value)!
  return locale.value === 'en' ? option.placeholderEn : option.placeholder
})

const promptPaths: Record<string, string> = {
  heart: 'M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9Z',
  film: 'M4 4h16v16H4V4Zm4 0v16M16 4v16M4 9h4M4 15h4M16 9h4M16 15h4',
  sun: 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10ZM12 2v2M12 20v2M4 12H2M22 12h-2M5 5l1.5 1.5M17.5 17.5 19 19M19 5l-1.5 1.5M6.5 17.5 5 19',
  star: 'm12 3 2.6 6.3L21 11l-6.4 1.7L12 19l-2.6-6.3L3 11l6.4-1.7L12 3Z',
  palm: 'M12 21V10m0 0C9 6 5 7 4 9c3-1 5 0 8 1Zm0 0c3-4 7-3 8-1-3-1-5 0-8 1Zm0 0c-1-4 1-6 3-6-1 2-2 4-3 6Z',
  gift: 'M3 10h18v10H3V10ZM2 6h20v4H2V6Zm10 0v14M12 6C9 6 7 5 7 3.5S9 2 10 3s2 3 2 3Zm0 0c3 0 5-1 5-2.5S15 2 14 3s-2 3-2 3Z',
}
// 沿用既有试听数据源（含本地化标题/标签），右栏作品卡片用它驱动播放器，使生成前也能真实试听。
const previewTracks = usePreviewTracks()
const previewOrder = [2, 0, 7]
const previewIndex = ref(0)
const previewCount = previewOrder.length
const lyricBoards = [
  ['下一站，去有海风的地方', '把地图折进口袋', '让风替我轻轻哼唱'],
  ['想和你，把平凡的日子慢慢过成爱', '清晨的灯还亮着', '我们不说晚安'],
  ['沿途的风景慢慢后退', '你在身旁不说话', '也是很好的回答'],
]
const track = computed(() => previewTracks.value[previewOrder[previewIndex.value % previewCount]!] ?? previewTracks.value[0]!)
const lyricLines = computed(() => lyricBoards[previewIndex.value % lyricBoards.length]!)
const isPreviewLoaded = computed(() => player.current?.id === track.value.id)
const previewDuration = computed(() => isPreviewLoaded.value && player.duration ? player.duration : track.value.durationSeconds)
const previewTime = computed(() => isPreviewLoaded.value ? player.time : 0)
const previewProgress = computed(() => Math.min(100, previewTime.value / (previewDuration.value || 1) * 100))
const isCurrentPlaying = computed(() => isPreviewLoaded.value && (player.playing || player.starting))
const activeLyric = computed(() => isPreviewLoaded.value ? Math.min(2, Math.floor(previewTime.value / (previewDuration.value / 3))) : 0)
const analysisBpm = computed(() => {
  const features = analysis.value.features
  return features && features.bpm > 0 ? `${Math.round(features.bpm)} BPM` : ''
})
const analysisBand = computed(() => analysis.value.features ? t(bandWord(analysis.value.features.bands)) : '')
const analysisTone = computed(() => {
  const features = analysis.value.features
  if (!features) return ''
  const word = toneWord(features.brightness)
  return word ? t(word) : ''
})
const fmt = (value: number) => `${Math.floor((value || 0) / 60).toString().padStart(2, '0')}:${Math.floor((value || 0) % 60).toString().padStart(2, '0')}`

/** 当前作品：对话中出现过的最后一个创作方案；没有时右栏展示占位，播放器仍可试听示例。 */
const currentBrief = computed<AgentBrief | undefined>(() => brief.value ?? [...messages.value].reverse().find(message => message.brief)?.brief)
const workTitle = computed(() => {
  const b = currentBrief.value
  if (!b) return t('你的作品')
  const line = (b.story || '').split(/[，。\n]/)[0]?.trim()
  return line ? line.slice(0, 12) : `${b.style} · ${b.mood}`
})
const workCover = computed(() => defaultCover())
const workLyrics = computed(() => {
  const b = currentBrief.value
  if (b) return (b.story || '').split(/[，。\n]/).map(line => line.trim()).filter(Boolean).slice(0, 4)
  return lyricLines.value
})

/** 一次性提示：busy 期间的重复操作、上传校验等都在这里给出可见反馈，不再静默丢弃。 */
function notify(message: string) {
  notice.value = message
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 4200)
}
async function scrollChat() { await nextTick(); chat.value?.scrollTo({ top: chat.value.scrollHeight, behavior: 'smooth' }) }
function toggleInterest(tag: string) { interests.value = interests.value.includes(tag) ? interests.value.filter(item => item !== tag) : [...interests.value, tag] }
/** 同一模式的提示只出现一次：连点模式标签不重复追加消息，也不重复触发聊天区定位。 */
const hintedModes = new Set<AgentMode>()
function chooseMode(option: typeof modeOptions[number]) {
  mode.value = option.id
  if (hintedModes.has(option.id)) return
  hintedModes.add(option.id)
  messages.value.push({ id: ++messageId, role: 'assistant', text: t(option.hint) })
  void scrollChat()
}
/** 快捷提示 / 灵感卡片不覆盖已输入的内容：两者合并成一条需求一起发送。 */
function composeText(extra?: string): string {
  const typed = input.value.trim()
  const prompt = (extra || '').trim()
  if (!prompt) return typed
  if (!typed || typed === prompt) return prompt
  return `${typed}；${prompt}`
}
function send(extra?: string) {
  const text = composeText(extra)
  if (!text) return
  if (busy.value) { notify(t('上一条还在整理，稍等一下再发送。')); return }
  input.value = ''; notice.value = ''; voiceHint.value = ''
  messages.value.push({ id: ++messageId, role: 'user', text })
  busy.value = true
  const reply = analyzeRequest(text, mode.value, interests.value, reference())
  // 右栏作品卡片在生成期间显示「正在创作歌词…」，与对话里的加载态一致。
  const line = `${t('收到！主题是')}${reply.topic}${t('，我为你选了')}${reply.style}${t('，情绪关键词是')}${reply.mood}${t('。可以直接开始创作，也可以继续补充细节 ♫')}`
  replyTimer = window.setTimeout(() => {
    messages.value.push({ id: ++messageId, role: 'assistant', text: line, brief: reply.brief, suggestions: reply.suggestions })
    brief.value = withReference(reply.brief, reference())
    busy.value = false
    void scrollChat()
  }, 420)
  void scrollChat()
}
function step(direction: number) { previewIndex.value = (previewIndex.value + direction + previewCount) % previewCount }
/**
 * 润色走的是同一个付费改写接口：免费额度用完后每次扣积分。
 * 余额不足时先刷新一次再判断；后端在调用模型前还会再校验一次余额。
 */
async function blockedByRewrite() {
  const gate = await guardCredits(() => credits.value, refreshCredits, view => view.rewriteFreeRemaining === 0 ? view.rewriteCost : 0)
  if (!gate.blocked) return false
  notify(t('积分不足，当前余额 {balance} 积分，改写需要 {cost} 积分。', { balance: gate.balance as number, cost: gate.cost }))
  return true
}
async function polish(message: Message) {
  if (!message.brief || message.polishing) return
  const source = message.brief.story
  message.polishing = true; message.polishError = ''
  try {
    // 润色走的是同一个付费改写接口：免费额度用完后每次扣积分，余额不足直接拦下。
    if (await blockedByRewrite()) return
    const key = polishKey.value || (polishKey.value = crypto.randomUUID())
    const result = await rewriteInspiration(source, locale.value === 'en' ? 'en' : 'zh-CN', key)
    polishKey.value = ''
    if (result.text && message.brief?.story === source) {
      const next = { ...message.brief, story: result.text }
      message.brief = next
      if (brief.value?.story === source) brief.value = next
    }
  } catch (error) {
    // 后端已把这次请求记为失败：用同一个键重试只会得到「上次未完成」，此时换新键重新发起。
    if (error instanceof ApiError && error.code === 'REWRITE_REQUEST_FAILED') polishKey.value = ''
    // 积分不足要明确告知并给出充值入口，不能和「服务暂时不可用」混成一句话。
    message.polishCreditShort = error instanceof ApiError && error.code === 'INSUFFICIENT_CREDITS'
    message.polishError = message.polishCreditShort
      ? t('积分不足，当前余额 {balance} 积分，改写需要 {cost} 积分。', { balance: credits.value?.balance ?? 0, cost: credits.value?.rewriteCost ?? 1 })
      : (error instanceof ApiError && error.message) ? error.message : t('AI 整理暂不可用，已按原文继续。')
  }
  finally { message.polishing = false; void refreshCredits().catch(() => undefined) }
}
function polishLatest() {
  const latest = [...messages.value].reverse().find(message => message.brief)
  if (!latest) { notify(t('先告诉我你的创作需求，再让 AI 帮忙润色。')); return }
  void polish(latest)
}
/** 输入框里的最新需求优先，不被更早整理好的 brief 覆盖；没有新输入时才沿用上一轮结果。 */
async function beginCreation(source?: AgentBrief) {
  if (busy.value) { notify(t('正在整理上一条需求，稍等一下再开始创作。')); return }
  // 分析最多等 1.5 秒：拿不到就用已有元信息继续，绝不让创作卡在分析上。
  if (analysisPromise) await Promise.race([analysisPromise.catch(() => undefined), new Promise(resolve => setTimeout(resolve, 1500))])
  const typed = input.value.trim()
  const next = typed
    ? analyzeRequest(typed, mode.value, interests.value, reference()).brief
    : withReference(source ?? brief.value ?? analyzeRequest('写一首关于今天的歌', mode.value, interests.value).brief, reference())
  writeAgentBrief(next)
  await router.push({ path: '/create', query: { from: 'agent' } })
}
function startVoice() {
  const constructors = window as unknown as { SpeechRecognition?: new () => VoiceRecognition; webkitSpeechRecognition?: new () => VoiceRecognition }
  const Recognition = constructors.SpeechRecognition || constructors.webkitSpeechRecognition
  if (!Recognition) { voiceHint.value = t('当前浏览器不支持语音输入，可以直接打字告诉我。'); return }
  const voice = new Recognition()
  recognition = voice
  voice.lang = locale.value === 'en' ? 'en-US' : 'zh-CN'
  voice.interimResults = false
  voice.onresult = event => {
    const text = event.results[0]?.[0]?.transcript?.trim()
    // 语音结果追加到已有输入后面，避免把用户刚打的字覆盖掉。
    if (text) input.value = input.value.trim() ? `${input.value.trim()} ${text}` : text
  }
  voice.onerror = () => { voiceHint.value = t('语音识别没有成功，可以直接打字告诉我。') }
  voice.onend = () => { voiceHint.value = ''; if (recognition === voice) recognition = undefined }
  try { voice.start() } catch { voiceHint.value = t('语音识别没有成功，可以直接打字告诉我。') }
}
function reference(): AgentReference | undefined {
  if (!uploaded.value) return undefined
  const { name, duration, size, type } = uploaded.value
  const state = analysis.value
  return {
    name, size, type,
    duration: state.features?.duration || duration,
    ...(state.features ? { features: state.features } : {}),
    ...(state.insight ? { insight: state.insight } : {}),
  }
}

function languageLabel(language: AgentParams['language']): string {
  if (language === 'en') return t('英文演唱')
  if (language === 'yue') return t('粤语演唱')
  return t('中文演唱')
}

function failureText(error: unknown): string {
  const code = error instanceof AudioAnalysisError ? error.code : undefined
  if (code === 'UNSUPPORTED') return t('当前浏览器不支持音频分析。')
  if (code === 'DECODE_FAILED') return t('这段音频无法解码，已保留文件名与时长。')
  if (code === 'TOO_LARGE') return t('参考音乐不能超过 20MB。')
  if (code === 'TIMEOUT') return t('音频分析超时，已按文件信息继续。')
  return t('音频分析失败，已按文件信息继续。')
}

async function loadInsight(features: import('../model/audio-features').AudioFeatures, token: number) {
  const insight = await requestReferenceAnalysis({
    text: input.value.trim(), fileName: uploaded.value?.name || '', duration: features.duration,
    locale: locale.value === 'en' ? 'en' : 'zh-CN', features,
  })
  if (token !== analysisToken) return
  analysis.value = { ...analysis.value, insightStatus: 'ready', ...(insight ? { insight } : {}) }
}

async function runAnalysis(file: File, token: number) {
  try {
    const result = await analyzeAudioFile(file)
    if (token !== analysisToken) return
    analysis.value = { status: 'ready', key: result.key, features: result.features, insightStatus: signedIn() ? 'loading' : 'idle' }
    if (signedIn()) void loadInsight(result.features, token)
  } catch (error) {
    if (token !== analysisToken) return
    if (error instanceof AudioAnalysisError && error.code === 'CANCELLED') return
    analysis.value = { status: 'failed', insightStatus: 'idle', failure: failureText(error) }
  }
}
/** 用独立的临时 URL 读时长，读完立刻释放，避免影响试听用的那个 URL。 */
function readDuration(file: File): Promise<number> {
  return new Promise(resolve => {
    if (typeof Audio === 'undefined') { resolve(0); return }
    const probe = new Audio()
    const url = URL.createObjectURL(file)
    let settled = false
    const done = (value: number) => {
      if (settled) return
      settled = true
      probe.removeAttribute('src')
      URL.revokeObjectURL(url)
      resolve(value)
    }
    probe.preload = 'metadata'
    probe.onloadedmetadata = () => done(Number.isFinite(probe.duration) ? probe.duration : 0)
    probe.onerror = () => done(0)
    probe.src = url
  })
}
/** 释放参考音乐：先停掉正在播放的这首，再回收 URL，避免播放器指向已失效地址。 */
function releaseUpload() {
  if (!uploaded.value) return
  if (player.current?.id === uploadTrackId) player.close()
  URL.revokeObjectURL(uploaded.value.url)
  uploaded.value = undefined
}
async function acceptFile(file: File | undefined) {
  if (!file) return
  const looksAudio = file.type.startsWith('audio/') || /\.(mp3|wav|m4a|aac|ogg|flac)$/i.test(file.name)
  if (!looksAudio) { uploadError.value = t('只支持 MP3、WAV、M4A 等音频文件。'); return }
  if (file.size > maxUploadBytes) { uploadError.value = t('参考音乐不能超过 20MB。'); return }
  uploadError.value = ''
  releaseUpload()
  const url = URL.createObjectURL(file)
  uploaded.value = { name: file.name, url, size: file.size, type: file.type || file.name.split('.').pop() || '', duration: 0 }
  const duration = await readDuration(file)
  if (uploaded.value?.url !== url) return
  uploaded.value = { ...uploaded.value, duration }
  analysisToken += 1
  const token = analysisToken
  cancelAnalysis()
  analysis.value = { status: 'analyzing', insightStatus: 'idle' }
  analysisPromise = runAnalysis(file, token)
  notify(duration ? t('已加入参考音乐，正在本机分析。') : t('已加入参考音乐，但没读到时长。'))
}
function onFile(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  // 清空选择，保证重复挑选同一个文件也能再次触发 change。
  target.value = ''
  void acceptFile(file)
}
function clearUpload() {
  uploadError.value = ''
  analysisToken += 1
  cancelAnalysis()
  forgetAnalysis(analysis.value.key)
  analysis.value = { status: 'idle', insightStatus: 'idle' }
  analysisPromise = undefined
  releaseUpload()
  if (fileInput.value) fileInput.value.value = ''
}
function playUpload() {
  if (!uploaded.value) return
  void player.toggle({ id: uploadTrackId, title: uploaded.value.name, cover: defaultCover(), audio: uploaded.value.url, provenance: 'IMPORTED' })
}
function togglePreview() {
  const starting = !isCurrentPlaying.value
  void player.toggle(track.value)
  // 只有真正开始播放才计次，暂停/收起不重复累加。
  if (starting) recordPlay(track.value.id)
}
function seekPreview(event: Event) { if (isPreviewLoaded.value) player.seek(Number((event.target as HTMLInputElement).value)) }
async function copyLink() {
  try { await navigator.clipboard.writeText(new URL(track.value.audio, window.location.origin).href); voiceHint.value = t('已复制试听链接') }
  catch { voiceHint.value = t('复制失败，可以直接下载试听。') }
}
function useAsInspiration() { send(`我想要一首${track.value.style}风格的歌`) }
onMounted(() => { void loadMyReactions(); void refreshCredits().catch(() => undefined) })
// 登录状态变化后重新读取点赞关系，避免刷新后喜欢状态丢失。
watch(signedIn, value => { if (value) void loadMyReactions() })
onBeforeUnmount(() => {
  window.clearTimeout(replyTimer)
  window.clearTimeout(noticeTimer)
  try { recognition?.stop?.() } catch { /* 浏览器已自行结束时 stop 会抛错，忽略即可。 */ }
  analysisToken += 1
  cancelAnalysis()
  if (uploaded.value) { if (player.current?.id === uploadTrackId) player.close(); URL.revokeObjectURL(uploaded.value.url) }
})
</script>
<template>
  <div class="agent-studio">
    <div class="agent-scenery" aria-hidden="true"><span/><span/><span/></div>

    <section class="agent-conversation" :aria-label="t('Agent 创作对话')">
      <header class="agent-conversation-head">
        <span class="agent-conversation-pill"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2ZM19 15l.9 2.6L22.5 18.5l-2.6.9L19 22l-.9-2.6-2.6-.9 2.6-.9L19 15Z"/></svg>{{ t('Agent 创作') }}<i class="agent-nav-new">New</i></span>
        <p class="agent-conversation-sub">{{ t('用一句话，把心情变成一首歌') }}</p>
      </header>
      <div class="agent-dots" aria-hidden="true"><i v-for="n in 5" :key="n" :class="{ active: n === 1 }"/></div>
      <div ref="chat" class="agent-chat" role="log" :aria-label="t('创作对话记录')">
        <div v-for="message in messages" :key="message.id" class="agent-message" :class="message.role">
          <div v-if="message.role === 'assistant'" class="agent-bot-avatar" aria-hidden="true">
            <svg viewBox="0 0 48 48"><path d="M24 5v5M17 3l2 4M31 3l-2 4" fill="none" stroke="#3c7bd6" stroke-width="2.6" stroke-linecap="round"/><rect x="8" y="10" width="32" height="27" rx="13" fill="#4a90e2"/><rect x="12" y="14" width="24" height="18" rx="9" fill="#eaf5ff"/><circle cx="19" cy="23" r="3.4" fill="#2b6cc4"/><circle cx="29" cy="23" r="3.4" fill="#2b6cc4"/><path d="M20 29c2.4 1.6 5.6 1.6 8 0" fill="none" stroke="#2b6cc4" stroke-width="1.8" stroke-linecap="round"/><circle cx="24" cy="6" r="2.6" fill="#ff6a4d"/></svg>
          </div>
          <div class="agent-bubble">
            <template v-if="message.greeting"><p v-for="line in greetingLines" :key="line">{{ line }}</p></template>
            <p v-else>{{ message.text }}</p>
            <div v-if="message.brief" class="agent-brief-card">
              <strong class="agent-brief-head"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2Z"/></svg>{{ t('已整理好创作灵感') }}</strong>
              <p class="agent-brief-story">{{ message.brief.story }}</p>
              <p class="agent-brief-meta"><span>{{ t('风格：') }}{{ message.brief.style }}</span><span>{{ t('情绪：') }}{{ message.brief.mood }}</span></p>
              <p v-if="hasParams(message.brief.params)" class="agent-brief-params">
                <span v-if="message.brief.params!.bpm">{{ message.brief.params!.bpm }} BPM</span>
                <span v-if="message.brief.params!.vocal !== 'AUTO'">{{ message.brief.params!.vocal === 'FEMALE' ? t('女声') : t('男声') }}</span>
                <span v-for="name in message.brief.params!.instruments" :key="name">{{ name }}</span>
                <span v-if="message.brief.params!.language">{{ languageLabel(message.brief.params!.language) }}</span>
                <span v-if="message.brief.params!.structure">{{ message.brief.params!.structure }}</span>
              </p>
              <div v-if="message.suggestions?.length" class="agent-brief-suggestions"><button v-for="style in message.suggestions" :key="style" type="button" :disabled="busy" @click="send(`我想要一首${style}风格的歌`)">{{ style }}</button></div>
              <div class="agent-brief-actions"><button type="button" :disabled="message.polishing" @click="polish(message)">{{ message.polishing ? t('AI 整理中…') : t('AI 润色描述') }}</button><button type="button" class="primary" @click="beginCreation(message.brief)">{{ t('用它开始创作') }} →</button><CreditCostHint v-if="credits&&credits.enabled&&(credits.rewriteCost>0||credits.rewriteFreeRemaining>0)" class="agent-brief-cost" :cost="credits.rewriteCost" :balance="credits.balance" :free-remaining="credits.rewriteFreeRemaining" :busy="message.polishing" variant="rewrite"/></div>
              <p v-if="message.polishError" class="agent-brief-error" role="status">{{ message.polishError }} <RouterLink v-if="message.polishCreditShort" class="text-button" to="/recharge">{{ t('去充值') }}</RouterLink></p>
            </div>
          </div>
        </div>
        <div v-if="messages.length === 1" class="agent-quick-card">
          <strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3Z"/></svg>{{ t('你可以这样说：') }}</strong>
          <div class="agent-quick-grid">
            <button v-for="prompt in quickPrompts" :key="prompt.text" type="button" :disabled="busy" @click="send(prompt.prompt)">
              <svg viewBox="0 0 24 24" :style="{ color: prompt.color }" aria-hidden="true"><path :d="promptPaths[prompt.icon]"/></svg><span>{{ locale === 'en' ? prompt.en : prompt.text }}</span>
            </button>
          </div>
          <button type="button" class="agent-interests-toggle" :aria-expanded="showInterests" @click="showInterests = !showInterests">{{ t('选择兴趣标签') }} {{ showInterests ? '▴' : '▾' }}</button>
          <div v-if="showInterests" class="agent-tag-list agent-tag-list--inline">
            <button v-for="tag in interestTags" :key="tag" type="button" :aria-pressed="interests.includes(tag)" @click="toggleInterest(tag)">{{ t(tag) }}</button>
          </div>
        </div>
        <p v-if="busy" class="agent-typing" role="status">{{ t('正在创作歌词…') }}</p>
      </div>

      <div class="agent-composer">
        <form class="agent-composer-box" @submit.prevent="send()">
          <svg class="agent-composer-spark" viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3ZM19 16l.7 2.3L22 19l-2.3.7L19 22l-.7-2.3L16 19l2.3-.7L19 16Z"/></svg>
          <input v-model="input" type="text" maxlength="500" :placeholder="placeholder" :aria-label="t('告诉我的创作需求')"/>
          <button type="button" class="agent-composer-clip" :aria-label="t('上传参考音乐')" @click="fileInput?.click()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L10 17.6a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8"/></svg></button>
          <button type="button" class="agent-composer-mic" :aria-label="t('语音输入')" @click="startVoice"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>
          <button type="button" class="agent-composer-polish" :aria-label="t('AI 润色描述')" @click="polishLatest"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3Z"/></svg></button>
          <button type="submit" class="agent-composer-send" :class="{ 'is-busy': busy }" :disabled="!input.trim()" :aria-busy="busy" :aria-label="busy ? t('正在整理上一条需求…') : t('发送')" :title="busy ? t('正在整理上一条需求…') : t('发送')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15m-6-6 6 6-6 6"/></svg></button>
        </form>
        <input ref="fileInput" type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac" hidden :aria-label="t('上传参考音乐')" @change="onFile"/>
        <p v-if="uploaded" class="agent-upload-file"><span :title="uploaded.name">{{ uploaded.name }}</span><small v-if="uploaded.duration">{{ fmt(uploaded.duration) }}</small><button type="button" @click="playUpload">{{ t('试听') }}</button><button type="button" :aria-label="t('移除')" @click="clearUpload">×</button></p>
        <p v-if="uploadError" class="agent-upload-error" role="alert">{{ uploadError }}</p>
        <p v-if="analysis.status === 'analyzing'" class="agent-analysis" role="status">{{ t('分析中…') }}</p>
        <template v-else-if="analysis.status === 'ready'">
          <div class="agent-analysis" role="status">
            <span v-if="analysisBpm">{{ analysisBpm }}</span>
            <span v-else>{{ t('无法判断节奏') }}</span>
            <span v-if="analysisBand">{{ analysisBand }}</span>
            <span v-if="analysisTone">{{ analysisTone }}</span>
          </div>
          <p v-if="analysis.insight" class="agent-analysis-inference">{{ t('AI 推断') }}：{{ analysis.insight.style }}<template v-if="analysis.insight.mood"> · {{ analysis.insight.mood }}</template></p>
          <p v-else-if="analysis.insightStatus === 'ready'" class="agent-section-hint">{{ t('AI 参考分析暂不可用，已按音乐特征继续。') }}</p>
        </template>
        <p v-else-if="analysis.status === 'failed'" class="agent-analysis is-failed" role="status">{{ analysis.failure }}</p>
        <p v-if="notice || voiceHint" class="agent-voice-hint" role="status" aria-live="polite">{{ notice || voiceHint }}</p>
        <div class="agent-modes"><button v-for="option in modeOptions" :key="option.id" type="button" :class="{ active: mode === option.id }" :aria-pressed="mode === option.id" @click="chooseMode(option)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3Z"/></svg>{{ t(option.label) }}</button></div>
        <p class="agent-slogan">{{ t('让音乐，点亮你的每一种情绪') }} <span aria-hidden="true">♡</span></p>
      </div>
    </section>

    <aside class="agent-preview" :aria-label="t('作品卡片')">
      <div class="agent-preview-head"><strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2Z"/></svg>{{ t('你的作品') }}</strong><i class="agent-nav-new">New</i><div><button type="button" :aria-label="t('上一首')" @click="step(-1)">‹</button><button type="button" :aria-label="t('下一首')" @click="step(1)">›</button></div></div>
      <div class="agent-preview-cover">
        <img :src="workCover" :alt="t('歌曲封面')"/>
        <span class="agent-preview-count">{{ previewIndex + 1 }}/{{ previewCount }}</span>
        <button type="button" class="agent-preview-play" :aria-label="isCurrentPlaying ? t('暂停') : t('播放')" @click="togglePreview"><svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="isCurrentPlaying" d="M9 6v12M15 6v12"/><path v-else d="m9 6 9 6-9 6V6Z"/></svg></button>
        <div v-if="busy" class="agent-work-loading"><span>{{ t('正在创作歌词…') }}</span></div>
      </div>
      <div class="agent-preview-meta"><strong>{{ workTitle }}</strong><span>{{ track.tags.join(' · ') }}</span></div>
      <div class="agent-preview-progress"><input class="agent-preview-range" type="range" min="0" :max="previewDuration" step="0.1" :value="previewTime" :disabled="!isPreviewLoaded" :style="{ background: `linear-gradient(90deg,#ff9257,#ff432d) 0/${previewProgress}% 100% no-repeat,#f2ded4` }" :aria-label="t('播放进度')" @input="seekPreview"/><div><span>{{ fmt(previewTime) }}</span><span>{{ fmt(previewDuration) }}</span></div></div>
      <div class="agent-preview-lyrics"><p v-for="(line, index) in workLyrics" :key="line" :class="{ active: index === activeLyric }">{{ line }}</p><small>{{ t('示例歌词') }}</small></div>
      <div class="agent-preview-actions">
        <button type="button" :class="{ liked: isLiked(track.id) }" :aria-pressed="isLiked(track.id)" :disabled="isPending(track.id, 'like')" @click="toggleReaction(track.id, 'like')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9Z"/></svg>{{ t('喜欢') }}</button>
        <a :href="track.audio" :download="`${track.title}.mp3`"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11m-4-4 4 4 4-4M5 19h14"/></svg>{{ t('下载') }}</a>
        <details class="agent-preview-more"><summary>{{ t('更多') }}<span aria-hidden="true">▾</span></summary><div><button type="button" @click="copyLink">{{ t('复制歌曲链接') }}</button><button type="button" @click="useAsInspiration">{{ t('用这首歌找灵感') }}</button></div></details>
      </div>
    </aside>
  </div>
</template>
