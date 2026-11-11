<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { locale, t } from '../../../shared/i18n'
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
import { analyzeRequest, bandWord, extractWorkTitle, interestTags, modeOptions, toneWord, withReference, type AgentBrief, type AgentMode, type AgentReference, type AgentReply, type ReferenceInsight } from '../model/assistant'

/** 创作方案：设计稿「创作方案」卡片上的一行参数。 */
interface AgentPlan { style: string; vocal: string; mood: string; tempo: string }
interface Message { id: number; role: 'assistant' | 'user'; text: string; lead?: string; plan?: AgentPlan; brief?: AgentBrief; reply?: AgentReply; tail?: string; polishing?: boolean; polishError?: string; polishCreditShort?: boolean }
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
let messageId = 0, replyTimer: number | undefined, writingTimer: number | undefined, noticeTimer: number | undefined, recognition: VoiceRecognition | undefined
const messages = ref<Message[]>([])
const chat = ref<HTMLElement>(), fileInput = ref<HTMLInputElement>()
const input = ref(''), busy = ref(false), writing = ref(false), notice = ref(''), voiceHint = ref('')
const { credits, refreshCredits } = useCredits()
const polishKey = ref('')
const mode = ref<AgentMode>('write'), interests = ref<string[]>(['时尚'])
const brief = ref<AgentBrief | undefined>()
const uploaded = ref<UploadState | undefined>(), uploadError = ref('')
const toolsOpen = ref(false)
// 分析只在本机进行；每次换文件或清除都会让 token 失效，旧结果不可能覆盖新文件。
const analysis = ref<ReferenceAnalysisState>({ status: 'idle', insightStatus: 'idle' })
let analysisToken = 0, analysisPromise: Promise<void> | undefined
const placeholder = computed(() => t('告诉我你的创作需求...'))
const lyricsExpanded = ref(false)

// 沿用既有试听数据源（含本地化标题/标签），右栏作品卡片用它驱动播放器。
const previewTracks = usePreviewTracks()
const track = computed(() => previewTracks.value[0]!)
const lyricBoards = [
  ['黄昏落在站台尽头', '最后一班车缓缓驶走', '那句未说出口的再见…'],
  ['把地图折进口袋', '让风替我轻轻哼唱', '下一站，去有海风的地方'],
  ['沿途的风景慢慢后退', '你在身旁不说话', '也是很好的回答'],
]
const isPreviewLoaded = computed(() => player.current?.id === track.value.id)
const previewDuration = computed(() => isPreviewLoaded.value && player.duration ? player.duration : track.value.durationSeconds)
const previewTime = computed(() => isPreviewLoaded.value ? player.time : 0)
const previewProgress = computed(() => Math.min(100, previewTime.value / (previewDuration.value || 1) * 100))
const isCurrentPlaying = computed(() => isPreviewLoaded.value && (player.playing || player.starting))
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

/** 当前作品：对话中出现过的最后一个创作方案；没有时右栏展示示例试听。 */
const currentBrief = computed<AgentBrief | undefined>(() => brief.value ?? [...messages.value].reverse().find(message => message.brief)?.brief)
const currentReply = computed<AgentReply | undefined>(() => [...messages.value].reverse().find(message => message.reply)?.reply)
/** 作品名：从用户原话里取出具体意象，不用创作描述的第一句，避免出现半截标题。 */
const workTitle = computed(() => {
  const reply = currentReply.value
  if (!reply) return track.value.title
  return extractWorkTitle(reply.input, reply.topic, track.value.title)
})
/** 副标题：设计稿是「原创歌曲 · 温柔女声」，人声取方案里定下的那一个。 */
const workSubtitle = computed(() => {
  const vocal = currentBrief.value?.params?.vocal
  const voice = vocal === 'FEMALE' ? t('温柔女声') : vocal === 'MALE' ? t('温暖男声') : ''
  return voice ? `${t('原创歌曲')} · ${voice}` : `${t('原创歌曲')} · ${currentBrief.value?.style || track.value.style}`
})
const workCover = computed(() => track.value.cover || track.value.cover)
/** 歌词预览：真实歌词要等生成接口，这里按主题给一段同调性的示例。 */
const workLyrics = computed(() => {
  const text = `${currentReply.value?.topic || ''}${currentReply.value?.mood || ''}`
  if (/离别|思念|怀念|不舍/.test(text)) return lyricBoards[0]!
  if (/旅行|远方|自由/.test(text)) return lyricBoards[1]!
  return lyricBoards[2]!
})
const visibleLyrics = computed(() => (lyricsExpanded.value ? workLyrics.value : workLyrics.value.slice(0, 3)))

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
/** 方案卡片上的一行参数：只写方案里真实存在的取值，缺项就不硬凑。 */
function planFrom(briefValue: AgentBrief): AgentPlan {
  const vocal = briefValue.params?.vocal === 'FEMALE' ? t('女声')
    : briefValue.params?.vocal === 'MALE' ? t('男声') : t('自动')
  const bpm = briefValue.params?.bpm ?? 0
  const tempo = bpm > 0 ? (bpm < 90 ? t('慢速') : bpm <= 120 ? t('中速') : t('快速'))
    : /慢|抒情|轻柔|温柔/.test(`${briefValue.style}${briefValue.mood}`) ? t('慢速') : t('中速')
  return { style: briefValue.style, vocal, mood: briefValue.mood, tempo }
}
function send() {
  const text = input.value.trim()
  if (!text) return
  if (busy.value) { notify(t('上一条还在整理，稍等一下再发送。')); return }
  input.value = ''; notice.value = ''; voiceHint.value = ''
  messages.value.push({ id: ++messageId, role: 'user', text })
  busy.value = true
  const reply = analyzeRequest(text, mode.value, interests.value, reference())
  replyTimer = window.setTimeout(() => {
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      text: t('我理解了，这是一首{topic}。', { topic: reply.phrase }),
      lead: t('我为你准备了创作方案：'),
      plan: planFrom(reply.brief),
      brief: reply.brief,
      reply,
      tail: t('接下来，我为你创作歌词，并生成歌曲。'),
    })
    brief.value = withReference(reply.brief, reference())
    busy.value = false
    // 方案给出后进入写词阶段：这一段时间用设计稿里的「正在创作歌词…」占位提示。
    writing.value = true
    window.clearTimeout(writingTimer)
    writingTimer = window.setTimeout(() => { writing.value = false }, 2600)
    void scrollChat()
  }, 420)
  void scrollChat()
}
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
  void player.toggle({ id: uploadTrackId, title: uploaded.value.name, cover: uploaded.value.url, audio: uploaded.value.url, provenance: 'IMPORTED' })
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
/** 分享优先走系统分享面板，不支持时退回复制链接，两种都不静默失败。 */
async function shareWork() {
  const url = new URL(track.value.audio, window.location.origin).href
  if (typeof navigator !== 'undefined' && navigator.share) {
    try { await navigator.share({ title: workTitle.value, url }); return }
    catch { /* 用户取消分享时退回复制链接。 */ }
  }
  await copyLink()
}
function toggleLyrics() { lyricsExpanded.value = !lyricsExpanded.value }
onMounted(() => { void loadMyReactions(); void refreshCredits().catch(() => undefined) })
// 登录状态变化后重新读取点赞关系，避免刷新后喜欢状态丢失。
watch(signedIn, value => { if (value) void loadMyReactions() })
onBeforeUnmount(() => {
  window.clearTimeout(replyTimer)
  window.clearTimeout(writingTimer)
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
      <div ref="chat" class="agent-chat" role="log" :aria-label="t('创作对话记录')">
        <div class="agent-hero">
          <span class="agent-hero-icon" aria-hidden="true">
            <svg viewBox="0 0 48 48"><path d="M25 9l12 2.6v16.2a6 6 0 1 1-3.4-5.4V18.3l-8 1.7v11.4a6 6 0 1 1-3.4-5.4V12z"/></svg>
          </span>
          <h1>{{ t('今天，想创作一首什么歌？') }}</h1>
          <p>{{ t('告诉我你的灵感，剩下的交给我。') }}</p>
        </div>

        <div v-for="message in messages" :key="message.id" class="agent-message" :class="message.role">
          <div v-if="message.role === 'assistant'" class="agent-bot-avatar" aria-hidden="true">
            <svg viewBox="0 0 48 48"><rect width="48" height="48" rx="14" fill="#ff4c3b"/><path d="M25 9l12 2.6v16.2a6 6 0 1 1-3.4-5.4V18.3l-8 1.7v11.4a6 6 0 1 1-3.4-5.4V12z" fill="#fff"/></svg>
          </div>
          <div class="agent-message-body">
            <div class="agent-bubble">
              <p>{{ message.text }}</p>
              <p v-if="message.lead">{{ message.lead }}</p>
            </div>
            <div v-if="message.plan" class="agent-plan">
              <strong class="agent-plan-head"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2Z"/></svg>{{ t('创作方案') }}</strong>
              <p class="agent-plan-params">{{ t('曲风：{style} · 人声：{vocal} · 情绪：{mood} · 节奏：{tempo}', { style: message.plan.style, vocal: message.plan.vocal, mood: message.plan.mood, tempo: message.plan.tempo }) }}</p>
              <button v-if="message.tail && message.brief" type="button" class="agent-plan-tail" @click="beginCreation(message.brief)">{{ message.tail }}</button>
              <p v-if="message.polishing" class="agent-plan-status" role="status">{{ t('AI 整理中…') }}</p>
              <p v-if="message.polishError" class="agent-plan-error" role="status">{{ message.polishError }} <RouterLink v-if="message.polishCreditShort" class="text-button" to="/recharge">{{ t('去充值') }}</RouterLink></p>
            </div>
            <p v-if="message.tail && !message.plan" class="agent-bubble-tail">{{ message.tail }}</p>
          </div>
          <div v-if="message.role === 'user'" class="agent-user-avatar" aria-hidden="true">
            <svg viewBox="0 0 48 48"><circle cx="24" cy="17" r="8.4" fill="#f0b49c"/><path d="M7 44c1.9-9.6 8.6-14.4 17-14.4S39.1 34.4 41 44z" fill="#f0b49c"/></svg>
          </div>
        </div>
        <p v-if="busy || writing" class="agent-typing" role="status">
          <span class="agent-typing-spinner" aria-hidden="true"/>
          {{ t('正在创作歌词...') }}
        </p>
      </div>

      <div class="agent-composer">
        <form class="agent-composer-box" @submit.prevent="send()">
          <input v-model="input" type="text" maxlength="500" :placeholder="placeholder" :aria-label="t('告诉我的创作需求')"/>
          <div class="agent-composer-row">
            <div class="agent-composer-tools">
              <button type="button" class="agent-composer-plus" :aria-expanded="toolsOpen" :aria-label="t('创作偏好')" @click="toolsOpen = !toolsOpen"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg></button>
              <button type="button" class="agent-composer-clip" :aria-label="t('上传参考音乐')" @click="fileInput?.click()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L10 17.6a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8"/></svg></button>
              <button type="button" class="agent-composer-mic" :aria-label="t('语音输入')" @click="startVoice"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>
            </div>
          </div>
          <button type="submit" class="agent-composer-send" :class="{ 'is-busy': busy }" :disabled="!input.trim()" :aria-busy="busy" :aria-label="busy ? t('正在整理上一条需求…') : t('发送')" :title="busy ? t('正在整理上一条需求…') : t('发送')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6"/></svg></button>
        </form>
        <div v-if="toolsOpen" class="agent-composer-menu">
          <strong>{{ t('创作偏好') }}</strong>
          <div class="agent-menu-modes">
            <button v-for="option in modeOptions" :key="option.id" type="button" :class="{ active: mode === option.id }" :aria-pressed="mode === option.id" @click="chooseMode(option)">{{ t(option.label) }}</button>
          </div>
          <strong>{{ t('选择兴趣标签') }}</strong>
          <div class="agent-tag-list">
            <button v-for="tag in interestTags" :key="tag" type="button" :aria-pressed="interests.includes(tag)" @click="toggleInterest(tag)">{{ t(tag) }}</button>
          </div>
          <button type="button" class="agent-menu-polish" :disabled="busy" @click="polishLatest">{{ t('AI 润色描述') }}</button>
          <CreditCostHint v-if="credits&&credits.enabled&&(credits.rewriteCost>0||credits.rewriteFreeRemaining>0)" :cost="credits.rewriteCost" :balance="credits.balance" :free-remaining="credits.rewriteFreeRemaining" variant="rewrite"/>
        </div>
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
        <p class="agent-hint"><span aria-hidden="true">✦</span> {{ t('AI 可以理解灵感、写歌词、编曲并生成歌曲') }}</p>
      </div>
    </section>

    <aside class="agent-preview" :aria-label="t('作品卡片')">
      <div class="agent-preview-cover"><img :src="workCover" :alt="t('歌曲封面')"/></div>
      <h2 class="agent-preview-title">{{ workTitle }}</h2>
      <p class="agent-preview-sub">{{ workSubtitle }}</p>
      <div class="agent-preview-playrow">
        <button type="button" class="agent-preview-play" :aria-label="isCurrentPlaying ? t('暂停') : t('播放')" @click="togglePreview"><svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="isCurrentPlaying" d="M9 6v12M15 6v12"/><path v-else d="m9 6 9 6-9 6V6Z"/></svg></button>
        <input class="agent-preview-range" type="range" min="0" :max="previewDuration" step="0.1" :value="previewTime" :disabled="!isPreviewLoaded" :style="{ background: `linear-gradient(90deg,#ff9257,#ff432d) 0/${previewProgress}% 100% no-repeat,#f2ded4` }" :aria-label="t('播放进度')" @input="seekPreview"/>
        <span class="agent-preview-time">{{ fmt(previewTime) }} / {{ fmt(previewDuration) }}</span>
      </div>
      <div class="agent-preview-actions">
        <button type="button" :class="{ liked: isLiked(track.id) }" :aria-pressed="isLiked(track.id)" :disabled="isPending(track.id, 'like')" @click="toggleReaction(track.id, 'like')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9Z"/></svg>{{ t('点赞') }}</button>
        <a :href="track.audio" :download="`${track.title}.mp3`"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11m-4-4 4 4 4-4M5 19h14"/></svg>{{ t('下载') }}</a>
        <button type="button" @click="shareWork"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 4h6v6M20 4l-8 8M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>{{ t('分享') }}</button>
      </div>
      <div class="agent-preview-lyrics-head">
        <strong>{{ t('歌词') }}</strong>
        <button type="button" :aria-expanded="lyricsExpanded" @click="toggleLyrics">{{ lyricsExpanded ? t('收起') : t('展开') }}<span aria-hidden="true">▾</span></button>
      </div>
      <div class="agent-preview-lyrics"><p v-for="line in visibleLyrics" :key="line">{{ line }}</p></div>
    </aside>
  </div>
</template>
