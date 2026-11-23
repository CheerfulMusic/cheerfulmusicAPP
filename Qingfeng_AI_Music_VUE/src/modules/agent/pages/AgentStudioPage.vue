<script setup lang="ts">
import '../playful.css'
import '../producer.css'
import producerMascot from '../assets/producer-mascot.webp'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import AgentPlayerControls from '../components/AgentPlayerControls.vue'
import SyncedLyrics from '../../works/components/SyncedLyrics.vue'
import InspirationGalaxy from '../components/InspirationGalaxy.vue'
import AgentListeningPanel from '../components/AgentListeningPanel.vue'
import AgentWorkShelf from '../components/AgentWorkShelf.vue'
import ChatAppearance from '../components/ChatAppearance.vue'
import AgentHotSongs from '../components/AgentHotSongs.vue'
import { useChatAppearance } from '../composables/useChatAppearance'
import AgentGenerationWait from '../components/AgentGenerationWait.vue'
import { defineAsyncComponent, computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { defaultCover, locale } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import { useCredits } from '../../../shared/credits/useCredits'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import { useAgentConversation } from '../composables/useAgentConversation'
import { creationTools, type CreationTool } from '../model/creation-tools'
import { displayTurnText, messageText, type ConversationSnapshot } from '../model/conversation'
import { analyzeAudioFile, cancelAnalysis, forgetAnalysis, MAX_REFERENCE_BYTES } from '../model/audio-analysis'
import { BPM_CONFIDENCE_THRESHOLD } from '../model/audio-features'
import { requestReferenceAnalysis } from '../api/reference'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { loadWork } from '../../studio/api/works'
import { assistantResult, uploadTarget, featureTarget, type AgentFeatureTarget, type AgentCommand } from '../model/assistant-result'
import AgentResultCard from '../components/AgentResultCard.vue'
import AgentProgressCard from '../components/AgentProgressCard.vue'
import AgentMessageText from '../components/AgentMessageText.vue'
import { agentProgress } from '../model/progress'
import UserAvatarDialog from '../components/UserAvatarDialog.vue'
import { useProfileAvatar } from '../composables/useProfileAvatar'
import { useMusicClicks } from '../composables/useMusicClicks'
import { useUserAvatar } from '../composables/useUserAvatar'
import { downloadSong, downloadStatus } from '../../works/api/download'
import { useAvatarGeneration } from '../composables/useAvatarGeneration'
const AgentFeatureDialog = defineAsyncComponent(() => import('../components/AgentFeatureDialog.vue'))
const featurePanel = ref<AgentFeatureTarget | null>(null)
const AgentNativeDialog = defineAsyncComponent(() => import('../components/AgentNativeDialog.vue'))

const conversation = useAgentConversation(result => { void handleResult(result) })
const { messages, songCard, shareUrl, liked, taskRunning, loading, sending, error, refreshError, pending, hasOlderMessages, loadingOlder, historyError } = conversation
const player = usePlayer(), { credits, refreshCredits } = useCredits()
const input = ref(''), chat = ref<HTMLElement>(), fileInput = ref<HTMLInputElement>(), notice = ref('')
const avatar = useUserAvatar()

function avatarImageFailed(event: Event) { const img = event.target as HTMLImageElement; if (!img.src.endsWith('/brand/music-person.svg')) img.src = '/brand/music-person.svg' }

const profileAvatar = useProfileAvatar()
const { sparks, trail, layerTarget } = useMusicClicks()
const { appearance, style: appearanceStyle, reset: resetAppearance } = useChatAppearance()
const avatarGeneration = useAvatarGeneration(avatar.refresh, refreshCredits)
const avatarDialog = ref(false)
async function uploadAvatar(file: File) { if (await avatar.upload(file)) avatarDialog.value = false }
function generateAvatar(description: string) {
  avatarDialog.value = false
  void send(tr(`帮我生成头像：${description}`, `Generate an avatar: ${description}`))
}
const confirmingReset = ref(false)
const previewTab = ref<'lyrics' | 'story' | 'more'>('lyrics')
const showQueue = ref(false)
const story = ref(''), storyLoading = ref(false), storyError = ref(false)
let storyVersion = 0
async function fetchStory() {
  const id = songCard.value?.songId, version = ++storyVersion
  story.value = ''; storyError.value = false; storyLoading.value = !!id
  if (!id) return
  try { const work = await loadWork(id); if (version === storyVersion) story.value = work.story?.trim() || '' }
  catch { if (version === storyVersion) storyError.value = true }
  finally { if (version === storyVersion) storyLoading.value = false }
}
const nativePanel = ref<{ kind: 'recharge' | 'account' | 'privacy' | 'photo' | 'upload_cover' | 'upload_audio'; workId?: string } | null>(null)
const lastAssistantId = computed(() => [...messages.value].reverse().find(message => message.role === 'assistant' && !agentProgress(message))?.id)
const chatMessages = computed(() => {
  const rows=messages.value.map(message=>({...message,progress:agentProgress(message)}))
  const lastStep=new Map<string,number>()
  for(const row of rows)if(row.progress)lastStep.set(row.progress.requestKey,row.seq)
  let turnState=''
  return rows.filter(row=>{
    if(row.role==='user')turnState=row.state
    if(!row.progress || !['planning','tool_started'].includes(row.progress.stage))return true
    return !['DONE','FAILED'].includes(turnState) && lastStep.get(row.progress.requestKey)===row.seq
      && !(row.progress.action==='generate_song' && taskRunning.value)
  }).map(row=>({...row,result:row.progress?null:assistantResult(row)}))
})
const pendingAlreadySaved = computed(() => !!pending.value && messages.value.some(message => {
  if (message.role !== 'user') return false
  try { return JSON.parse(message.contentJson || '{}').requestKey === pending.value?.requestKey } catch { return false }
}))
const blocked = computed(() => loading.value || sending.value || !!pending.value)
const english = computed(() => locale.value === 'en')
const tr = (zh: string, en: string) => english.value ? en : zh
type CreationView = 'menu' | CreationTool['id']
const creationView = ref<CreationView | null>('menu')
const activeTool = computed(() => creationTools.find(tool => tool.id === creationView.value))
const selectedChoice = ref<number | null>(null)
const choicePanel = ref<HTMLElement>()
const composerInput = ref<HTMLTextAreaElement>()
const composerMore = ref<HTMLDetailsElement>()
const messageDraft = computed(() => {
  const tool = activeTool.value
  const description = input.value.trim()
  if (!tool) return description
  const choice = selectedChoice.value === null ? undefined : tool.choices[selectedChoice.value]
  const brief = [choice && tr(...choice), description].filter(Boolean).join('\n')
  return brief ? `${tr(...tool.prompt)} ${brief}` : ''
})
const messageTooLong = computed(() => messageDraft.value.length > 4000)

function setCreationView(view: CreationView | null) {
  creationView.value = view
  selectedChoice.value = null
}
async function openCreationPanel(view: CreationView) {
  if (view !== creationView.value) setCreationView(view)
  await nextTick()
  choicePanel.value?.focus({ preventScroll: true })
  if (chat.value) chat.value.scrollTop = chat.value.scrollHeight
  choicePanel.value?.scrollIntoView?.({ block: 'nearest', behavior: 'instant' })
}
function closeComposerTools(restoreFocus = false) {
  if (!composerMore.value) return
  composerMore.value.open = false
  if (restoreFocus) composerMore.value.querySelector('summary')?.focus()
}
function closeCreationPanel() {
  setCreationView(null)
  composerInput.value?.focus({ preventScroll: true })
}
function setChatDraft(text: string) {
  setCreationView(null)
  input.value = text
}
function useConversationExample(prompt: string) {
  setChatDraft([prompt, input.value.trim()].filter(Boolean).join('\n'))
  composerInput.value?.focus()
}
const inspirationChips = [
  { icon: 'music', title: ['轻快流行', 'Upbeat pop'], idea: ['轻快流行的曲风', 'An upbeat pop sound'] },
  { icon: 'sun', title: ['旅行主题', 'Travel'], idea: ['把路上的风景写进歌里', 'A song about the sights along a journey'] },
  { icon: 'people', title: ['友情', 'Friendship'], idea: ['写给一路同行的朋友', 'For the friends who have been there along the way'] },
  { icon: 'heart', title: ['正能量', 'Encouragement'], idea: ['写一首给自己打气的歌', 'A song to lift my spirits'] },
  { icon: 'wave', title: ['电子舞曲', 'Dance'], idea: ['有律动感的电子舞曲', 'An electronic dance beat'] },
] as const
function addInspiration(idea: string) {
  input.value = [input.value.trimEnd(), idea].filter(Boolean).join('\n')
  if (creationView.value === 'menu') setCreationView(null)
  composerInput.value?.focus()
}
function openVideoEditor() {
  const card = songCard.value
  if (card?.songId && card.assetId) featurePanel.value = { feature: 'mv', workId: card.songId, assetId: card.assetId }
}
const prompts = computed(() => [
  tr('你好，你可以帮我做什么？', 'Hi, what can you help me with?'),
  tr('帮我写一首关于旅行的歌词', 'Write lyrics about travelling'),
  tr('查看我的作品', 'Show my songs'),
  tr('给我一些创作灵感', 'Give me songwriting ideas'),
  tr('查看我的积分和用量', 'Show my credits and usage'),
  tr('查看我的订单', 'Show my orders'),
  tr('查看推荐音乐', 'Show recommended music'),
  tr('我想用照片写歌', 'Write a song from a photo'),
])
function safeLink(value?: string | null): string {
  if (!value) return ''
  try { const url = new URL(value, window.location.origin); return ['http:', 'https:'].includes(url.protocol) ? url.href : '' } catch { return '' }
}
const audioUrl = computed(() => safeLink(songCard.value?.audioUrl))
const publicLink = computed(() => safeLink(shareUrl.value))
const playable = computed<PlayableTrack | null>(() => {
  const card = songCard.value
  if (!card?.songId || !audioUrl.value) return null
  return { id: `${card.songId}:${card.revisionId || card.revision}:${card.assetId || audioUrl.value}`, title: card.title, cover: safeLink(card.coverUrl) || defaultCover(), audio: audioUrl.value, provenance: card.audioOrigin || undefined }
})
const currentPlaying = computed(() => !!playable.value && player.current?.id === playable.value.id && player.current.audio === playable.value.audio)
const isPlaying = computed(() => currentPlaying.value && (player.playing || player.starting))
const duration = computed(() => currentPlaying.value ? player.duration : 0)
const playTime = computed(() => currentPlaying.value ? player.time : 0)
const statusText = computed(() => {
  if (taskRunning.value) return tr('音乐生成中，完成后会自动更新', 'Generating music. The result will appear here.')
  switch (songCard.value?.status) {
    case 'SUCCEEDED': return tr('音乐已生成', 'Music ready')
    case 'FAILED': return tr('生成失败，可以在对话中重新生成', 'Generation failed. Ask to generate again.')
    case 'CANCELLED': case 'CANCELED': return tr('生成已取消', 'Generation cancelled')
    default: return songCard.value?.lyrics ? tr('歌词已保存', 'Lyrics saved') : tr('创作草稿', 'Creative draft')
  }
})
async function scrollChat() {
  await nextTick()
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  chat.value?.scrollTo?.({ top: chat.value.scrollHeight, behavior: creationView.value || reducedMotion ? 'instant' : 'smooth' })
}
function togglePlay() { if (playable.value) void player.toggle(playable.value) }
const downloadUnlocked=ref(false)
const downloadPath=computed(()=>songCard.value?.songId&&songCard.value.assetId?`app/works/${encodeURIComponent(songCard.value.songId)}/audio/${encodeURIComponent(songCard.value.assetId)}/download`:'')
watch([downloadPath,accountState,logtoState],([path],_,onCleanup)=>{let stale=false;onCleanup(()=>{stale=true});downloadUnlocked.value=false;if(path)void downloadStatus(path).then(s=>{if(!stale)downloadUnlocked.value=s.unlocked}).catch(()=>undefined)},{immediate:true})
async function download() {
  if (!audioUrl.value || !songCard.value || !downloadPath.value) return
  const url = new URL(audioUrl.value); url.searchParams.set('download', 'true')
  if(await downloadSong(downloadPath.value,url.href,`${songCard.value.title || 'song'}.mp3`)){downloadUnlocked.value=true;void refreshCredits()}
}
const lyricFollow=ref(true),lyricRefresh=ref(0)
const handledPlayerResults=new Set<string>()
async function handleResult(result?: ConversationSnapshot) {
  if (!result) return
  const resultKey=`${result.messages.at(-1)?.id}:${result.action}`
  if (!handledPlayerResults.has(resultKey)) {
    handledPlayerResults.add(resultKey)
    if(handledPlayerResults.size>100)handledPlayerResults.delete(handledPlayerResults.values().next().value!)
    if(result.action==='pause_playback' && player.current && (player.playing||player.starting))void player.toggle(player.current)
    if(result.action==='next_track')player.next()
    if(result.action==='previous_track')player.next(-1)
    if(result.action==='enable_repeat'&&!player.repeatOne || result.action==='disable_repeat'&&player.repeatOne)player.toggleRepeat()
    if(result.action==='enable_lyric_follow')lyricFollow.value=true
    if(result.action==='disable_lyric_follow')lyricFollow.value=false
    if(result.action==='sync_playback_lyrics')lyricRefresh.value++
  }
  if (result.action === 'generate_avatar') avatarGeneration.start()
  if (result.action === 'play' && !isPlaying.value) togglePlay()
  if (result.action === 'download') await download()
  if (result.action === 'login') showLoginDialog()
  if (result.action === 'recharge' || result.action === 'account' || result.action === 'privacy' || result.action === 'photo') nativePanel.value = { kind: result.action }
  void refreshCredits().catch(() => undefined)
  await scrollChat()
}
async function send(extra?: string) {
  if (blocked.value) return
  if (extra === undefined && messageTooLong.value) return
  const text = (extra ?? messageDraft.value).trim()
  if (!text) return
  notice.value = ''
  const draft = input.value
  const result = await conversation.send(text, locale.value, () => {
    if (extra === undefined && input.value === draft && messageDraft.value === text) {
      input.value = ''
      setCreationView(null)
    } else if (creationView.value === 'menu') setCreationView(null)
  })
  await handleResult(result)
}
function goToLibrary(){ const shelf=document.getElementById('agent-library'); shelf?.focus(); shelf?.scrollIntoView?.({block:'nearest'}) }
function runCommand(command: AgentCommand) {
  if (blocked.value) return
  if (command.kind === 'send') { void send(command.text); return }
  if (command.kind === 'login') { showLoginDialog(); return }
  if (command.kind === 'recharge' || command.kind === 'account' || command.kind === 'privacy' || command.kind === 'photo') { nativePanel.value = { kind: command.kind }; return }
  const feature = featureTarget(command)
  if (feature) { if (feature.feature === 'avatar') avatarDialog.value = true; else if (feature.feature === 'reference') fileInput.value?.click(); else featurePanel.value = feature; return }
  const target = uploadTarget(command)
  if (target) { nativePanel.value = target; return }
  if (command.kind === 'upload' && !command.text) fileInput.value?.click()
}
function onEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault(); void send()
}
async function retry() { await handleResult(await conversation.retry()) }
function editRejected() { const text = conversation.editRejected(); if (text !== undefined) setChatDraft(text) }
async function reset() {
  if (await conversation.reset()) { confirmingReset.value = false; input.value = ''; notice.value = ''; setCreationView('menu') }
}
async function copyShare() {
  if (!publicLink.value) return
  try { await navigator.clipboard.writeText(publicLink.value); notice.value = tr('分享链接已复制', 'Share link copied') }
  catch { notice.value = tr('可选中下方链接复制。', 'Select and copy the link below.') }
}

interface Recognition { lang: string; interimResults: boolean; onresult: ((event: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null; onerror: (() => void) | null; onend: (() => void) | null; start(): void; stop?(): void }
let recognition: Recognition | undefined
function startVoice() {
  const browser = window as unknown as { SpeechRecognition?: new () => Recognition; webkitSpeechRecognition?: new () => Recognition }
  const Constructor = browser.SpeechRecognition || browser.webkitSpeechRecognition
  if (!Constructor) { notice.value = tr('当前浏览器不支持语音输入，请直接打字。', 'Voice input is unavailable in this browser.'); return }
  recognition?.stop?.()
  const voice = new Constructor(); recognition = voice; voice.lang = english.value ? 'en-US' : 'zh-CN'; voice.interimResults = false
  voice.onresult = event => { const text = event.results[0]?.[0]?.transcript?.trim(); if (text) input.value = [input.value.trim(), text].filter(Boolean).join(' ') }
  voice.onerror = () => { notice.value = tr('语音识别没有成功，请重试。', 'Voice input failed. Please try again.') }
  voice.onend = () => { if (recognition === voice) recognition = undefined }
  try { voice.start() } catch { notice.value = tr('无法开启语音输入，请检查浏览器权限。', 'Unable to start voice input. Check browser permissions.') }
}
const reference = ref<{ name: string; url: string; summary: string; key?: string } | null>(null)
const analyzing = ref(false), referenceError = ref('')
let analysisVersion = 0
function clearReference() {
  analysisVersion++; cancelAnalysis(); analyzing.value = false; referenceError.value = ''
  if (reference.value) {
    if (player.current?.id === 'agent-reference') player.close()
    URL.revokeObjectURL(reference.value.url); forgetAnalysis(reference.value.key)
  }
  reference.value = null
  if (fileInput.value) fileInput.value.value = ''
}
async function attach(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (!file.type.startsWith('audio/') && !/\.(mp3|wav|m4a|aac|ogg|flac)$/i.test(file.name)) { referenceError.value = tr('请选择 MP3、WAV、M4A 等音频文件。', 'Choose an audio file such as MP3, WAV or M4A.'); return }
  if (file.size > MAX_REFERENCE_BYTES) { referenceError.value = tr('参考音乐不能超过 20MB。', 'Reference audio must be under 20MB.'); return }
  clearReference()
  const version = analysisVersion
  reference.value = { name: file.name, url: URL.createObjectURL(file), summary: '' }; analyzing.value = true
  try {
    const result = await analyzeAudioFile(file)
    if (version !== analysisVersion || !reference.value) return
    reference.value.key = result.key
    const features = result.features
    const measured = features.bpm > 0 && features.bpmConfidence >= BPM_CONFIDENCE_THRESHOLD ? `${Math.round(features.bpm)} BPM` : ''
    reference.value.summary = measured
    const insight = await requestReferenceAnalysis({ text: input.value, fileName: file.name, duration: features.duration, locale: english.value ? 'en' : 'zh-CN', features })
    if (version !== analysisVersion || !reference.value) return
    reference.value.summary = [measured, insight ? `AI 推断：${insight.style}，${insight.mood}。${insight.summary}` : ''].filter(Boolean).join('；')
  } catch { if (version === analysisVersion) referenceError.value = tr('暂时无法分析这段音乐，你可以直接描述想参考的风格。', 'Unable to analyse this audio. Describe the style instead.') }
  finally { if (version === analysisVersion) analyzing.value = false }
}
function useReference() {
  if (reference.value?.summary) input.value = [input.value.trim(), `创作参考特征：${reference.value.summary}`].filter(Boolean).join('\n')
}
function playReference() {
  if (reference.value) void player.toggle({ id: 'agent-reference', title: reference.value.name, audio: reference.value.url, cover: defaultCover(), provenance: 'IMPORTED' })
}
async function loadOlder() {
  const height = chat.value?.scrollHeight ?? 0, top = chat.value?.scrollTop ?? 0
  await conversation.loadOlder(); await nextTick()
  if (chat.value) chat.value.scrollTop = top + chat.value.scrollHeight - height
}
watch(() => [previewTab.value, songCard.value?.songId], ([tab]) => {
  storyVersion++; story.value = ''; storyError.value = false; storyLoading.value = false
  if (tab === 'story') void fetchStory()
})
watch(() => messages.value.at(-1)?.seq, () => { void scrollChat() })
let avatarRefreshScheduled = false, avatarDisposed = false
function avatarIdentityChanged() {
  avatarGeneration.stop(); avatar.clear(); profileAvatar.clear(); avatarDialog.value = false
  if (avatarRefreshScheduled) return
  avatarRefreshScheduled = true
  queueMicrotask(() => {
    avatarRefreshScheduled = false
    if (!avatarDisposed) { void avatar.refresh(); void profileAvatar.refresh(); avatarGeneration.start() }
  })
}
watch(() => JSON.stringify([accountState.value.authenticated, accountState.value.email, accountState.value.name, logtoState.value.authenticated, logtoState.value.email, logtoState.value.phoneMasked, logtoState.value.name]), () => {
  avatarIdentityChanged()
  storyVersion++; story.value = ''; storyError.value = false; storyLoading.value = false; previewTab.value = 'lyrics'; showQueue.value = false
  setCreationView('menu'); input.value = ''; notice.value = ''; confirmingReset.value = false; nativePanel.value = null; featurePanel.value = null
  clearReference(); void conversation.changeIdentity()
})
onMounted(() => { window.addEventListener('qingfeng:identity-changed', avatarIdentityChanged); void avatar.refresh(); void profileAvatar.refresh(); avatarGeneration.start(); void conversation.refresh(); void refreshCredits().catch(() => undefined) })
onBeforeUnmount(() => { avatarDisposed = true; window.removeEventListener('qingfeng:identity-changed', avatarIdentityChanged); avatar.clear(); profileAvatar.clear(); storyVersion++; recognition?.stop?.(); clearReference() })
</script>

<template>
  <div class="agent-studio agent-playful agent-producer" :style="appearanceStyle">
    <Teleport :to="layerTarget"><div class="agent-click-sparks" aria-hidden="true"><i v-for="dot in trail" :key="dot.id" class="pointer-ribbon" :style="{left:dot.x+'px',top:dot.y+'px',width:dot.length+'px',transform:'rotate('+dot.angle+'rad)'}"/> <span v-for="spark in sparks" :key="spark.id" :style="{ left: `${spark.x}px`, top: `${spark.y}px` }"><i/><b/></span></div></Teleport>
    <nav class="producer-tools" :aria-label="tr('创作助手','Creation assistant')">
      <h2><button type="button" class="creation-menu-trigger" aria-haspopup="dialog" :aria-expanded="creationView !== null" @click="openCreationPanel('menu')"><span aria-hidden="true">✦</span> {{ tr('创作助手','Creation assistant') }}</button></h2>
      <button v-for="tool in creationTools" :key="tool.id" type="button" :disabled="blocked"
              aria-haspopup="dialog" :aria-expanded="creationView === tool.id" @click="openCreationPanel(tool.id)">
        <i aria-hidden="true"><NavIcon :name="tool.icon"/></i><span><strong>{{ tr(...tool.title) }}</strong><small>{{ tr(...tool.detail) }}</small></span>
      </button>
      <button class="producer-library" type="button" @click="goToLibrary"><i aria-hidden="true"><NavIcon name="library"/></i><span><strong>{{ tr('我的作品','My songs') }}</strong><small>{{ tr('管理创作与成品','Manage your music') }}</small></span></button>
    </nav>
    <section class="agent-conversation" :aria-label="tr('音乐创作对话', 'Music conversation')">
      <header class="agent-conversation-head">
        <button type="button" class="agent-conversation-pill creation-menu-trigger" aria-haspopup="dialog" :aria-expanded="creationView !== null" @click="openCreationPanel('menu')"><NavIcon name="create"/><span>{{ tr('有灵感？写词、编曲、制作 MV 都可以问我', 'Have an idea? Ask me about lyrics, music or videos') }}</span><span aria-hidden="true">⌄</span></button>
        <ChatAppearance v-model="appearance" @reset="resetAppearance"/><button type="button" class="agent-reset-trigger" :disabled="loading || sending" @click="confirmingReset = !confirmingReset">{{ tr('清空对话', 'Clear chat') }}</button>
      </header>
      <div v-if="confirmingReset" class="agent-reset-confirm" role="alert">
        <p>{{ tr('这会清空聊天及当前作品关联，已保存的作品仍在「我的音乐」。已提交的音乐任务不会被取消；如需停止，请先查看当前任务。', 'This clears the chat and current song selection. Saved songs remain in My Music. Submitted music jobs keep running; review the current task first if you want to stop it.') }}</p>
        <button type="button" :disabled="loading || sending" @click="reset">{{ tr('确认清空聊天', 'Clear chat') }}</button>
        <button type="button" @click="confirmingReset = false">{{ tr('保留对话', 'Keep chat') }}</button>
      </div>
      <div class="producer-welcome">
        <img class="producer-mascot" :src="producerMascot" alt="" width="212" height="190"/>
        <div><h1>{{ tr('你的 AI 专属音乐制作人', 'Your personal AI music producer') }}</h1><p>{{ tr('写歌词 · 创作旋律 · 制作歌曲 · 生成 MV', 'Lyrics · Melodies · Songs · Music videos') }} <span aria-hidden="true">♡</span></p></div>
      </div>
      <div ref="chat" class="agent-chat" role="log" aria-live="polite">
        <button v-if="hasOlderMessages" class="agent-history-more" type="button" :disabled="loadingOlder || loading" @click="loadOlder">{{ loadingOlder ? tr('正在加载…', 'Loading…') : tr('查看更早的对话', 'Load earlier messages') }}</button>
        <p v-if="historyError" class="agent-upload-error" role="status">{{ historyError }}</p>
        <p v-if="loading" class="agent-typing" role="status">{{ tr('正在恢复对话…', 'Loading conversation…') }}</p>
        <div v-if="!loading && !messages.length && !pending && !error && !refreshError" class="agent-message assistant studio-intro">
          <div class="agent-bot-avatar" aria-hidden="true"><img :src="'/brand/logo-symbol.svg'" alt=""/></div>
          <div class="agent-bubble">
            <p>{{ tr('你好！我是 Cheerful Music 的 AI 音乐伙伴。', 'Hi! I’m your AI music partner at Cheerful Music.') }} <span aria-hidden="true">♫</span></p>
            <p>{{ tr('告诉我一段故事、一种心情，或你喜欢的曲风，我们一起把它写成歌。', 'Tell me a story, a feeling or a style you love, and we can turn it into a song together.') }}</p>
          </div>
        </div>
        <div v-for="message in chatMessages" :key="message.id" class="agent-message" :class="message.role">
          <div v-if="message.role === 'assistant'" class="agent-bot-avatar" aria-hidden="true"><img :src="'/brand/logo-symbol.svg'" alt=""/></div>
          <div class="agent-message-content"><AgentProgressCard v-if="message.progress" :progress="message.progress"/><div v-else-if="messageText(message)" class="agent-bubble" :class="{ 'agent-message-failed': message.state === 'FAILED' }"><AgentMessageText :text="messageText(message)"/></div><AgentResultCard v-if="message.result" :result="message.result" :disabled="blocked" :current="message.id === lastAssistantId" @command="runCommand"/></div>
          <button v-if="message.role === 'user'" type="button" class="agent-user-avatar" @click="avatarDialog = true" :aria-label="tr('我的资料，修改头像', 'My profile, change avatar')" :title="tr('我的资料 · 点击修改头像', 'My profile · Change avatar')"><img :src="avatar.avatarUrl.value || profileAvatar.imageUrl.value || '/brand/music-person.svg'" :alt="tr('我的头像', 'My avatar')" @error="avatarImageFailed"/></button>
        </div>
        <div v-if="pending && !pendingAlreadySaved" class="agent-message user agent-pending"><div class="agent-bubble"><p>{{ displayTurnText(pending.text) }}</p><small>{{ sending ? tr('发送中…', 'Sending…') : tr('结果待确认', 'Awaiting confirmation') }}</small></div></div>
        <AgentGenerationWait v-if="taskRunning" kind="music" :cover="safeLink(songCard?.coverUrl) || defaultCover()"/><p v-else-if="sending" class="agent-typing" role="status"><span class="agent-typing-spinner" aria-hidden="true"/>{{ tr('正在处理你的请求…', 'Working on your request…') }}</p>
        <section v-if="creationView" ref="choicePanel" class="agent-choice-panel" role="dialog" tabindex="-1"
                 aria-labelledby="agent-choice-title" @keydown.esc.stop="closeCreationPanel">
          <img class="choice-avatar" :src="'/brand/logo-symbol.svg'" alt=""/>
          <header>
            <button v-if="activeTool" type="button" class="choice-back" :aria-label="tr('返回创作方向', 'Back to creative tools')" @click="openCreationPanel('menu')">‹</button>
            <h2 id="agent-choice-title">{{ activeTool ? tr(...activeTool.question) : tr('想从哪一步开始？', 'Where would you like to start?') }}</h2>
            <button type="button" class="choice-close" :aria-label="tr('关闭创作选项', 'Close creative options')" @click="closeCreationPanel">×</button>
          </header>
          <div v-if="activeTool" class="creation-choices" role="group" :aria-label="tr(...activeTool.title)">
            <button v-for="(choice, index) in activeTool.choices" :key="choice[0]" type="button"
                    :aria-pressed="selectedChoice === index" :disabled="blocked"
                    @click="selectedChoice = selectedChoice === index ? null : index">
              {{ tr(...choice) }}
            </button>
            <button v-if="activeTool.id === 'mv' && songCard?.assetId" type="button" :disabled="blocked" @click="openVideoEditor">{{ tr('打开 MV 编辑器', 'Open video editor') }} ›</button>
          </div>
          <div v-else class="creation-choices" role="group" :aria-label="tr('创作方向', 'Creative tools')">
            <button v-for="tool in creationTools" :key="tool.id" type="button" :disabled="blocked" @click="openCreationPanel(tool.id)">
              <NavIcon :name="tool.icon"/> {{ tr(...tool.title) }}
            </button>
          </div>
          <button class="choice-write" type="button" @click="composerInput?.focus()">
            {{ selectedChoice === null ? tr('自己打字描述', 'Describe your own idea') : tr('补充描述', 'Add details') }}
          </button>
          <details v-if="!activeTool && !messages.length && !loading" class="creation-examples">
            <summary>{{ tr('更多对话示例', 'More conversation starters') }}</summary>
            <div class="agent-quick-grid"><button v-for="prompt in prompts" :key="prompt" type="button" :disabled="blocked" @click="useConversationExample(prompt)">{{ prompt }}</button></div>
          </details>
        </section>
      </div>
      <div class="agent-composer">
        <nav class="inspiration-chips" :aria-label="tr('创作灵感', 'Song ideas')">
          <button v-for="chip in inspirationChips" :key="chip.icon" type="button" :disabled="blocked" @click="addInspiration(tr(chip.idea[0], chip.idea[1]))"><NavIcon :name="chip.icon"/>{{ tr(chip.title[0], chip.title[1]) }}</button>
          <button type="button" :disabled="blocked" @click="openCreationPanel('music')"><NavIcon name="sliders"/>{{ tr('其他风格','More styles') }}</button>
        </nav>
        <p v-if="error" class="agent-upload-error" role="alert">{{ error }} <button type="button" :disabled="sending || loading" @click="pending ? retry() : conversation.refresh()">{{ tr('重试', 'Retry') }}</button></p>
        <button v-if="pending?.rejected && !sending" type="button" @click="editRejected">{{ tr('修改未发送的内容', 'Edit rejected message') }}</button>
        <p v-else-if="pending && !sending" class="agent-section-hint">{{ tr('上次请求的结果尚未确认。重试会核对同一次请求。', 'The previous request is unconfirmed. Retry checks the same request.') }} <button type="button" @click="retry">{{ tr('重试原请求', 'Retry request') }}</button></p>
        <p v-if="refreshError" class="agent-upload-error" role="status">{{ refreshError }}</p>
        <form class="agent-composer-box" @submit.prevent="send()">
          <textarea ref="composerInput" v-model="input" rows="2" maxlength="4000"
                    :placeholder="selectedChoice !== null ? tr('还可以补充细节（选填）…', 'Add any details (optional)…') : tr('也可以直接打字，描述你的想法…', 'Or type your idea here…')"
                    :aria-label="tr('创作消息', 'Message')" :aria-invalid="messageTooLong" :aria-describedby="messageTooLong ? 'agent-message-limit' : undefined" @keydown.enter.exact="onEnter"/>
          <div class="agent-composer-row"><div class="agent-composer-tools">
            <button type="button" class="agent-composer-clip" :aria-label="tr('参考音乐', 'Reference audio')" @click="fileInput?.click()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L10 17.6a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8"/></svg></button>
            <button type="button" class="agent-composer-mic" :aria-label="tr('语音输入', 'Voice input')" @click="startVoice"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>
            <details ref="composerMore" class="composer-more" @keydown.esc.prevent="closeComposerTools(true)">
              <summary :aria-label="tr('更多对话工具', 'More conversation tools')" :title="tr('更多对话工具', 'More conversation tools')"><NavIcon name="more"/></summary>
              <nav class="agent-capabilities" @click="closeComposerTools()" :aria-label="tr('对话功能', 'Conversation tools')"><button type="button" :disabled="blocked" @click="send('查看我的作品')">{{ tr('我的作品', 'My songs') }}</button><button type="button" :disabled="blocked" @click="send('查看当前作品版本')">{{ tr('作品版本', 'Versions') }}</button><button type="button" :disabled="blocked" @click="send('查看我的积分')">{{ tr('积分', 'Credits') }}</button><button type="button" :disabled="blocked" @click="send('我要充值')">{{ tr('充值', 'Recharge') }}</button><button type="button" :disabled="blocked" @click="send('查看所有功能')">{{ tr('所有功能', 'All tools') }}</button><button type="button" @click="avatarDialog = true">{{ tr('设置头像', 'Avatar') }}</button></nav>
            </details>
          </div></div>
          <button type="submit" class="agent-composer-send" :class="{ 'is-busy': sending }" :disabled="!messageDraft || messageTooLong || blocked" :aria-busy="sending" :aria-label="tr('发送', 'Send')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 18-7-6 18-3.5-8.5L3 10Zm8.5 2.5L21 3"/></svg></button>
        </form>
        <p v-if="messageTooLong" id="agent-message-limit" class="agent-upload-error" role="status">{{ tr('消息超过 4000 字，请缩短描述。', 'The message exceeds 4,000 characters. Shorten your description.') }}</p>
        <input ref="fileInput" type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac" hidden @change="attach"/>
        <p v-if="reference" class="agent-upload-file"><span>{{ reference.name }}</span><button type="button" @click="playReference">{{ tr('试听', 'Listen') }}</button><button v-if="reference.summary && !analyzing" type="button" @click="useReference">{{ tr('将特征加入输入', 'Use audio features') }}</button><button type="button" :aria-label="tr('移除参考', 'Remove reference')" @click="clearReference">×</button></p>
        <p v-if="reference" class="agent-section-hint">{{ analyzing ? tr('正在分析参考音乐…', 'Analysing audio…') : reference.summary || tr('音频仅供本机试听。', 'Audio stays on this device.') }}</p>
        <p v-if="referenceError" class="agent-upload-error" role="status">{{ referenceError }}</p>
        <p v-if="notice" class="agent-voice-hint" role="status">{{ notice }}</p>
        <p v-if="avatarGeneration.message.value" class="agent-section-hint" role="status">{{ avatarGeneration.message.value }}</p>
        <details class="studio-pricing">
          <summary>{{ tr('免费额度后 1 积分可聊 5 次', 'After the free allowance: 1 credit for 5 turns') }}<span v-if="credits"> · {{ tr('余额', 'Balance') }} {{ credits.balance }} {{ tr('积分','credits') }}</span></summary>
          <p class="agent-section-hint">{{ tr('免费额度用完后，1 积分可聊 5 次；发送即按此规则计费，剩余次数跨会话保留。创作工具另计费，精确替换首句、末句免费。', 'After the free allowance, 1 credit covers 5 turns. Sending accepts this rate; unused turns carry over. Creative tools are billed separately; exact first/last line edits are free.') }}</p>
          <p v-if="credits" class="agent-section-hint">{{ tr('整首歌词重写', 'Full lyric rewrite') }} {{ credits.lyricsCost }} {{ tr('积分', 'credits') }} · {{ tr('生成音乐', 'Music') }} {{ credits.musicCost }} {{ tr('积分', 'credits') }}</p>
        </details>
      </div>
    </section>
    <Teleport to="body"><UserAvatarDialog v-if="avatarDialog" :busy="avatar.busy.value" :error="avatar.error.value" :disabled="blocked" :generation-available="avatarGeneration.available.value" @close="avatarDialog = false" @upload="uploadAvatar" @generate="generateAvatar"/></Teleport>
    <div class="producer-collection">
      <AgentHotSongs/>
      <AgentWorkShelf @open="featurePanel={feature:'work',workId:$event}" :refresh-key="`${songCard?.songId}:${songCard?.revision}:${messages.length}`"/>
    </div>
    <aside class="agent-preview" :aria-label="songCard?.songId ? tr('当前作品', 'Current song') : tr('热歌推荐', 'Song recommendations')">
      <template v-if="songCard?.songId && (!player.current || currentPlaying)"><header class="music-player-heading"><NavIcon name="headphones"/>{{ tr('音乐播放','Music player') }}<span class="player-meter" aria-hidden="true"/></header>
        <div class="record-summary"><div class="agent-album">
          <div class="agent-album-art"><span class="agent-album-badge">{{ taskRunning ? tr('创作中', 'Creating') : tr('我的作品', 'My song') }}</span><img :src="safeLink(songCard.coverUrl) || defaultCover()" :alt="tr('歌曲封面', 'Song artwork')"/>
            <button v-if="playable" type="button" class="agent-cover-play" :aria-label="isPlaying ? tr('暂停这首歌', 'Pause this song') : tr('播放这首歌', 'Play this song')" @click="togglePlay"><PlaybackIcon :playing="isPlaying"/></button>
          </div>
        </div>
        <div class="agent-preview-meta"><strong>{{ songCard.title || tr('未命名作品', 'Untitled song') }}</strong><span v-if="songCard.style">{{ songCard.style }}</span>
          <div class="listen-actions"><button type="button" class="agent-album-like" :disabled="blocked" :aria-pressed="liked" :aria-label="liked ? tr('取消喜欢', 'Unlike') : tr('喜欢', 'Like')" @click="send(liked ? '取消点赞当前作品' : '给当前作品点赞')"><NavIcon name="heart"/>{{ tr('喜欢','Like') }}</button>
            <button type="button" :disabled="blocked || !playable" @click="send('分享当前作品')"><NavIcon name="share"/>{{ tr('分享','Share') }}</button>
            <button type="button" @click="previewTab = 'more'"><NavIcon name="more"/>{{ tr('更多','More') }}</button>
          </div></div>
        </div><p class="agent-song-status" role="status">{{ statusText }}</p>

        <AgentPlayerControls :position="playTime" :duration="duration" :playing="isPlaying" :playable="!!playable"
                             :seekable="currentPlaying && !!duration" :active="currentPlaying" :queue-available="!!player.tracks.length" :queue-open="showQueue"
                             @play="togglePlay" @queue="showQueue = !showQueue"/>
        <InspirationGalaxy :work-id="songCard.songId" :asset-id="songCard.assetId"/>
        <div v-if="showQueue && player.tracks.length" class="agent-play-queue" :aria-label="tr('播放列表', 'Playback queue')"><button v-for="track in player.tracks" :key="track.id" type="button" :aria-current="player.current?.id === track.id ? 'true' : undefined" @click="player.toggle(track)">{{ track.title }}</button></div>
        <p v-if="player.current && !currentPlaying" class="agent-player-other">{{ tr('播放器正在播放其他作品', 'The player is on another song') }}</p>
        <p v-if="currentPlaying && player.error" class="agent-upload-error" role="alert">{{ player.error }}</p>
        <div class="agent-preview-tabs" role="tablist" :aria-label="tr('作品详情', 'Song details')">
          <button v-for="tab in (['lyrics', 'story', 'more'] as const)" :key="tab" type="button" role="tab" :aria-selected="previewTab === tab" @click="previewTab = tab">{{ tab === 'lyrics' ? tr('歌词', 'Lyrics') : tab === 'story' ? tr('作品故事', 'Story') : tr('更多', 'More') }}</button>
        </div>
        <label v-if="songCard.assetId && previewTab==='lyrics'" class="producer-lyric-switch"><span>♫ {{ tr('歌词滚动播放','Follow lyrics') }}</span><input v-model="lyricFollow" type="checkbox" role="switch" :aria-label="tr('歌词滚动播放','Follow lyrics')"/></label>
        <div v-if="previewTab === 'lyrics'" class="agent-tab-content agent-real-lyrics" role="tabpanel"><strong v-if="songCard.lyrics" class="agent-lyrics-title">♫ {{ songCard.title }}</strong><SyncedLyrics show-times v-if="songCard.assetId" :key="songCard.assetId" :work-id="songCard.songId" :asset-id="songCard.assetId" :player-track-id="playable?.id" :lyrics="songCard.lyrics" :auto-sync-enabled="false" :follow-enabled="lyricFollow" :refresh-key="lyricRefresh" @seek="player.seek($event)"/><p v-else>{{ songCard.lyrics || tr('还没有歌词，可以在对话中让我写。', 'No lyrics yet. Ask me to write them.') }}</p></div>
        <div v-else-if="previewTab === 'story'" class="agent-tab-content agent-story-empty" role="tabpanel"><p v-if="storyLoading">{{ tr('正在加载作品故事…', 'Loading story…') }}</p><p v-else-if="storyError">{{ tr('作品故事加载失败', 'Unable to load story') }} <button type="button" @click="fetchStory">{{ tr('重试', 'Retry') }}</button></p><p v-else>{{ story || tr('暂无作品故事', 'No story yet') }}</p></div>
        <div v-else class="agent-tab-content agent-song-actions" role="tabpanel"><button v-if="!taskRunning" type="button" class="agent-start-button" :disabled="blocked || !songCard.lyrics" @click="send('用当前歌词生成音乐')">{{ audioUrl ? tr('重新生成音乐', 'Generate another version') : tr('生成音乐', 'Generate music') }}</button><button v-else type="button" :disabled="blocked" @click="send('停止生成')">{{ tr('停止生成', 'Cancel generation') }}</button><button type="button" :disabled="blocked" @click="setChatDraft(tr('把副歌改得更温柔，保留其他部分', 'Make the chorus gentler and keep the rest'))">{{ tr('修改歌词', 'Revise lyrics') }}</button></div>
        <div v-if="publicLink" class="agent-share-link"><input readonly :value="publicLink" :aria-label="tr('分享链接','Share link')"/><button type="button" @click="copyShare">{{ tr('复制链接', 'Copy link') }}</button></div>
        <div class="agent-preview-actions agent-bottom-actions"><button type="button" :disabled="blocked || !playable" @click="download"><NavIcon name="download"/> {{ downloadUnlocked ? tr('免费下载', 'Free download') : tr('下载（首次15积分）', 'Download (15 credits first time)') }}</button><button type="button" :disabled="blocked || !playable" @click="send('分享当前作品')"><NavIcon name="share"/> {{ tr('分享', 'Share') }}</button><button type="button" :disabled="blocked" @click="setChatDraft(tr('参考当前作品，帮我创作一首同风格的新歌', 'Create a new song inspired by the style of the current song'))">＋ {{ tr('改编这首歌', 'Remix') }}</button></div>
      </template>
      <AgentListeningPanel v-else @remix="setChatDraft(tr('参考《'+$event+'》的风格，创作一首新歌','Create a new song inspired by '+$event))" @work="featurePanel={feature:'work',workId:$event}"/>
    </aside>
    <AgentFeatureDialog v-if="featurePanel" :key="JSON.stringify(featurePanel)" :target="featurePanel" @close="featurePanel = null" @refresh="conversation.refresh()" @send="send"/>
    <AgentNativeDialog v-if="nativePanel" :key="`${nativePanel.kind}:${nativePanel.workId || ''}`" :kind="nativePanel.kind" :work-id="nativePanel.workId" @close="nativePanel = null" @refresh="conversation.refresh()" @send="send"/>
  </div>
</template>
