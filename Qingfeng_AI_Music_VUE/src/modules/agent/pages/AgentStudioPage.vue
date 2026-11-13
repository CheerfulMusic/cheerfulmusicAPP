<script setup lang="ts">
import '../playful.css'
import { routerKey } from 'vue-router'
import { defineAsyncComponent, computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { defaultCover, locale } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import { useCredits } from '../../../shared/credits/useCredits'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import { useAgentConversation } from '../composables/useAgentConversation'
import { messageText, type ConversationSnapshot } from '../model/conversation'
import { analyzeAudioFile, cancelAnalysis, forgetAnalysis, MAX_REFERENCE_BYTES } from '../model/audio-analysis'
import { BPM_CONFIDENCE_THRESHOLD } from '../model/audio-features'
import { requestReferenceAnalysis } from '../api/reference'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { loadWork } from '../../studio/api/works'
import { assistantResult, uploadTarget, type AgentCommand } from '../model/assistant-result'
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
const AgentNativeDialog = defineAsyncComponent(() => import('../components/AgentNativeDialog.vue'))

const conversation = useAgentConversation(result => { void handleResult(result) })
const { messages, songCard, shareUrl, liked, taskRunning, loading, sending, error, refreshError, pending, hasOlderMessages, loadingOlder, historyError } = conversation
const player = usePlayer(), { credits, refreshCredits } = useCredits()
const input = ref(''), chat = ref<HTMLElement>(), fileInput = ref<HTMLInputElement>(), notice = ref('')
const avatar = useUserAvatar()
const profileRouter = inject(routerKey, null)
function avatarImageFailed(event: Event) { const img = event.target as HTMLImageElement; if (!img.src.endsWith('/brand/music-person.svg')) img.src = '/brand/music-person.svg' }
function openProfile(event: MouseEvent) { if (!profileRouter || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0) return; event.preventDefault(); void profileRouter.push('/profile') }
const profileAvatar = useProfileAvatar()
const { sparks, burst } = useMusicClicks()
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
const chatMessages = computed(() => messages.value.filter((message, index) => { const progress = agentProgress(message); const user = messages.value.slice(0, index).reverse().find(item => item.role === 'user'); return !progress || !['planning', 'tool_started'].includes(progress.stage) || !user || !['DONE', 'FAILED'].includes(user.state) }).map(message => ({ ...message, progress: agentProgress(message), result: agentProgress(message) ? null : assistantResult(message) })))
const pendingAlreadySaved = computed(() => !!pending.value && messages.value.some(message => {
  if (message.role !== 'user') return false
  try { return JSON.parse(message.contentJson || '{}').requestKey === pending.value?.requestKey } catch { return false }
}))
const blocked = computed(() => loading.value || sending.value || !!pending.value)
const english = computed(() => locale.value === 'en')
const tr = (zh: string, en: string) => english.value ? en : zh
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
const formatTime = (seconds: number) => `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${Math.floor(seconds % 60).toString().padStart(2, '0')}`
const statusText = computed(() => {
  if (taskRunning.value) return tr('音乐生成中，完成后会自动更新', 'Generating music. The result will appear here.')
  switch (songCard.value?.status) {
    case 'SUCCEEDED': return tr('音乐已生成', 'Music ready')
    case 'FAILED': return tr('生成失败，可以在对话中重新生成', 'Generation failed. Ask to generate again.')
    case 'CANCELLED': case 'CANCELED': return tr('生成已取消', 'Generation cancelled')
    default: return songCard.value?.lyrics ? tr('歌词已保存', 'Lyrics saved') : tr('创作草稿', 'Creative draft')
  }
})
async function scrollChat() { await nextTick(); chat.value?.scrollTo?.({ top: chat.value.scrollHeight, behavior: 'smooth' }) }
function togglePlay() { if (playable.value) void player.toggle(playable.value) }
const downloadUnlocked=ref(false)
const downloadPath=computed(()=>songCard.value?.songId&&songCard.value.assetId?`app/works/${encodeURIComponent(songCard.value.songId)}/audio/${encodeURIComponent(songCard.value.assetId)}/download`:'')
watch([downloadPath,accountState,logtoState],([path],_,onCleanup)=>{let stale=false;onCleanup(()=>{stale=true});downloadUnlocked.value=false;if(path)void downloadStatus(path).then(s=>{if(!stale)downloadUnlocked.value=s.unlocked}).catch(()=>undefined)},{immediate:true})
async function download() {
  if (!audioUrl.value || !songCard.value || !downloadPath.value) return
  const url = new URL(audioUrl.value); url.searchParams.set('download', 'true')
  if(await downloadSong(downloadPath.value,url.href,`${songCard.value.title || 'song'}.mp3`)){downloadUnlocked.value=true;void refreshCredits()}
}
async function handleResult(result?: ConversationSnapshot) {
  if (!result) return
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
  const text = (extra ?? input.value).trim()
  if (!text) return
  notice.value = ''
  if (!extra) input.value = ''
  const result = await conversation.send(text, locale.value)
  await handleResult(result)
}
function runCommand(command: AgentCommand) {
  if (blocked.value) return
  if (command.kind === 'send') { void send(command.text); return }
  if (command.kind === 'login') { showLoginDialog(); return }
  if (command.kind === 'recharge' || command.kind === 'account' || command.kind === 'privacy' || command.kind === 'photo') { nativePanel.value = { kind: command.kind }; return }
  const target = uploadTarget(command)
  if (target) { nativePanel.value = target; return }
  if (command.kind === 'upload' && !command.text) fileInput.value?.click()
}
function onEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault(); void send()
}
async function retry() { await handleResult(await conversation.retry()) }
function editRejected() { const text = conversation.editRejected(); if (text !== undefined) input.value = text }
async function reset() {
  if (await conversation.reset()) { confirmingReset.value = false; input.value = ''; notice.value = '' }
}
async function copyShare() {
  if (!publicLink.value) return
  try { await navigator.clipboard.writeText(publicLink.value); notice.value = tr('分享链接已复制', 'Share link copied') }
  catch { notice.value = tr('请打开下方分享链接，或长按复制。', 'Open or copy the share link below.') }
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
  input.value = ''; notice.value = ''; confirmingReset.value = false; nativePanel.value = null
  clearReference(); void conversation.changeIdentity()
})
onMounted(() => { window.addEventListener('qingfeng:identity-changed', avatarIdentityChanged); void avatar.refresh(); void profileAvatar.refresh(); avatarGeneration.start(); void conversation.refresh(); void refreshCredits().catch(() => undefined) })
onBeforeUnmount(() => { avatarDisposed = true; window.removeEventListener('qingfeng:identity-changed', avatarIdentityChanged); avatar.clear(); profileAvatar.clear(); storyVersion++; recognition?.stop?.(); clearReference() })
</script>

<template>
  <div class="agent-studio agent-playful" @click.capture="burst">
    <Teleport to="body"><div class="agent-click-sparks" aria-hidden="true"><span v-for="spark in sparks" :key="spark.id" :style="{ left: `${spark.x}px`, top: `${spark.y}px` }"><i>♪</i><i>✦</i><i>♫</i></span></div></Teleport>
    <div class="agent-scenery" aria-hidden="true"><span/><span/><span/></div>
    <section class="agent-conversation" :aria-label="tr('音乐创作对话', 'Music conversation')">
      <header class="agent-conversation-head">
        <span class="agent-conversation-pill">♫ {{ tr('音乐创作助手', 'Music creation assistant') }}</span>
        <p class="agent-conversation-sub">{{ tr('创作、作品、积分和账号，在这里接着聊、接着办。', 'Create music, manage songs, credits and your account in one conversation.') }}</p>
        <button type="button" class="agent-reset-trigger" :disabled="loading || sending" @click="confirmingReset = !confirmingReset">{{ tr('重新开始', 'Start over') }}</button>
      </header>
      <div v-if="confirmingReset" class="agent-reset-confirm" role="alert">
        <p>{{ tr('这会清空聊天及当前作品关联，已保存的作品仍在「我的音乐」。已提交的音乐任务不会被取消；如需停止，请先查看当前任务。', 'This clears the chat and current song selection. Saved songs remain in My Music. Submitted music jobs keep running; review the current task first if you want to stop it.') }}</p>
        <button type="button" :disabled="loading || sending" @click="reset">{{ tr('确认清空聊天', 'Clear chat') }}</button>
        <button type="button" @click="confirmingReset = false">{{ tr('保留对话', 'Keep chat') }}</button>
      </div>
      <div ref="chat" class="agent-chat" role="log" aria-live="polite">
        <button v-if="hasOlderMessages" class="agent-history-more" type="button" :disabled="loadingOlder || loading" @click="loadOlder">{{ loadingOlder ? tr('正在加载…', 'Loading…') : tr('查看更早的对话', 'Load earlier messages') }}</button>
        <p v-if="historyError" class="agent-upload-error" role="status">{{ historyError }}</p>
        <div v-if="!messages.length" class="agent-hero">
          <div class="agent-hero-friend" aria-hidden="true"><img :src="'/brand/music-person.svg'" alt=""/><span>♪</span><span>✦</span></div>
          <h1>{{ tr('把你的故事，变成一首歌。', 'Turn your story into a song.') }}</h1>
          <p class="agent-hero-tagline">{{ tr('每个人，都可以是歌手。', 'Everyone can be a singer.') }}</p>
          <p>{{ tr('一个故事、一种心情，甚至一张照片。', 'A story, a feeling, or even a photo.') }}</p>
          <p>{{ tr('告诉我你的灵感，剩下的交给 AI。', 'Share your inspiration. Let AI take it from here.') }}</p>
        </div>
        <p v-if="loading" class="agent-typing" role="status">{{ tr('正在恢复对话…', 'Loading conversation…') }}</p>
        <div v-if="!messages.length && !loading && !error" class="agent-message assistant">
          <div class="agent-bubble"><p>{{ tr('你好！告诉我你想做什么：聊灵感、写歌、找作品、选版本、换封面、分享，或查积分和订单。需要选择时，我会把选项放在对话里。', 'Hi! Ask for ideas, write a song, find your music, compare versions, change artwork, share, or check credits and orders. I will show any choices right here.') }}</p></div>
        </div>
        <div v-for="message in chatMessages" :key="message.id" class="agent-message" :class="message.role">
          <div v-if="message.role === 'assistant'" class="agent-bot-avatar" aria-hidden="true"><img :src="'/brand/logo-symbol.svg'" alt=""/></div>
          <div class="agent-message-content"><AgentProgressCard v-if="message.progress" :progress="message.progress"/><div v-else-if="messageText(message)" class="agent-bubble" :class="{ 'agent-message-failed': message.state === 'FAILED' }"><AgentMessageText :text="messageText(message)"/></div><AgentResultCard v-if="message.result" :result="message.result" :disabled="blocked" :current="message.id === lastAssistantId" @command="runCommand"/></div>
          <a v-if="message.role === 'user'" href="/profile" class="agent-user-avatar" @click="openProfile" :aria-label="tr('我的资料，修改头像', 'My profile, change avatar')" :title="tr('我的资料 · 点击修改头像', 'My profile · Change avatar')"><img :src="avatar.avatarUrl.value || profileAvatar.imageUrl.value || '/brand/music-person.svg'" :alt="tr('我的头像', 'My avatar')" @error="avatarImageFailed"/></a>
        </div>
        <div v-if="pending && !pendingAlreadySaved" class="agent-message user agent-pending"><div class="agent-bubble"><p>{{ pending.text }}</p><small>{{ sending ? tr('发送中…', 'Sending…') : tr('结果待确认', 'Awaiting confirmation') }}</small></div></div>
        <p v-if="sending" class="agent-typing" role="status"><span class="agent-typing-spinner" aria-hidden="true"/>{{ tr('正在处理你的请求…', 'Working on your request…') }}</p>
        <div v-if="!messages.length && !loading" class="agent-quick-card"><strong>{{ tr('试着说', 'Try saying') }}</strong><div class="agent-quick-grid"><button v-for="prompt in prompts" :key="prompt" type="button" :disabled="blocked" @click="send(prompt)"><span>{{ prompt }}</span></button></div></div>
      </div>
      <div class="agent-composer">
        <nav class="agent-capabilities" :aria-label="tr('对话功能', 'Conversation tools')"><button type="button" :disabled="blocked" @click="send('查看我的作品')">{{ tr('我的作品', 'My songs') }}</button><button type="button" :disabled="blocked" @click="send('查看当前作品版本')">{{ tr('作品版本', 'Versions') }}</button><button type="button" :disabled="blocked" @click="send('查看我的积分')">{{ tr('积分', 'Credits') }}</button><button type="button" :disabled="blocked" @click="send('我要充值')">{{ tr('充值', 'Recharge') }}</button><button type="button" :disabled="blocked" @click="send('查看所有功能')">{{ tr('所有功能', 'All tools') }}</button><button type="button" @click="avatarDialog = true">{{ tr('设置头像', 'Avatar') }}</button></nav>
        <p v-if="error" class="agent-upload-error" role="alert">{{ error }} <button type="button" :disabled="sending || loading" @click="pending ? retry() : conversation.refresh()">{{ tr('重试', 'Retry') }}</button></p>
        <button v-if="pending?.rejected && !sending" type="button" @click="editRejected">{{ tr('修改未发送的内容', 'Edit rejected message') }}</button>
        <p v-else-if="pending && !sending" class="agent-section-hint">{{ tr('上次请求的结果尚未确认。重试会核对同一次请求。', 'The previous request is unconfirmed. Retry checks the same request.') }} <button type="button" @click="retry">{{ tr('重试原请求', 'Retry request') }}</button></p>
        <p v-if="refreshError" class="agent-upload-error" role="status">{{ refreshError }}</p>
        <form class="agent-composer-box" @submit.prevent="send()">
          <textarea v-model="input" rows="2" maxlength="4000" :placeholder="tr('告诉我你想做什么，比如「找出上次的歌，换个封面」…', 'Tell me what to do, e.g. “find my last song and change its artwork”…')" :aria-label="tr('创作消息', 'Message')" @keydown.enter.exact="onEnter"/>
          <div class="agent-composer-row"><div class="agent-composer-tools">
            <button type="button" class="agent-composer-plus" :disabled="blocked" :aria-label="tr('所有功能', 'All tools')" @click="send('查看所有功能')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg></button>
            <button type="button" class="agent-composer-clip" :aria-label="tr('参考音乐', 'Reference audio')" @click="fileInput?.click()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L10 17.6a1.7 1.7 0 0 1-2.4-2.4l7.8-7.8"/></svg></button>
            <button type="button" class="agent-composer-mic" :aria-label="tr('语音输入', 'Voice input')" @click="startVoice"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>
          </div></div>
          <button type="submit" class="agent-composer-send" :class="{ 'is-busy': sending }" :disabled="!input.trim() || blocked" :aria-busy="sending" :aria-label="tr('发送', 'Send')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6"/></svg></button>
        </form>
        <input ref="fileInput" type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac" hidden @change="attach"/>
        <p v-if="reference" class="agent-upload-file"><span>{{ reference.name }}</span><button type="button" @click="playReference">{{ tr('试听', 'Listen') }}</button><button v-if="reference.summary && !analyzing" type="button" @click="useReference">{{ tr('将特征加入输入', 'Use audio features') }}</button><button type="button" :aria-label="tr('移除参考', 'Remove reference')" @click="clearReference">×</button></p>
        <p v-if="reference" class="agent-section-hint">{{ analyzing ? tr('正在分析参考音乐…', 'Analysing audio…') : reference.summary || tr('音频仅供本机试听。', 'Audio stays on this device.') }}</p>
        <p v-if="referenceError" class="agent-upload-error" role="status">{{ referenceError }}</p>
        <p v-if="notice" class="agent-voice-hint" role="status">{{ notice }}</p>
        <p v-if="avatarGeneration.message.value" class="agent-section-hint" role="status">{{ avatarGeneration.message.value }}</p>
        <p class="agent-section-hint">{{ tr('免费对话额度用完后，确认消耗 1 积分继续一轮；创作工具另计费。精确替换首句、末句不收对话积分。', 'After the free allowance, confirm 1 credit per turn; creative tools are billed separately. Exact first/last line replacements are free.') }}</p>
        <p v-if="credits" class="agent-section-hint">{{ tr('当前', 'Balance') }} {{ credits.balance }} {{ tr('积分 · 生成歌词', 'credits · Lyrics') }} {{ credits.lyricsCost }} · {{ tr('生成音乐', 'Music') }} {{ credits.musicCost }}</p>
      </div>
    </section>
    <Teleport to="body"><UserAvatarDialog v-if="avatarDialog" :busy="avatar.busy.value" :error="avatar.error.value" :disabled="blocked" :generation-available="avatarGeneration.available.value" @close="avatarDialog = false" @upload="uploadAvatar" @generate="generateAvatar"/></Teleport>
    <aside class="agent-preview" :aria-label="tr('当前作品', 'Current song')">
      <template v-if="songCard?.songId">
        <div class="agent-album">
          <div class="agent-record" :class="{ 'is-spinning': isPlaying }" aria-hidden="true"><span/></div>
          <div class="agent-album-art"><span class="agent-album-badge">{{ taskRunning ? tr('创作中', 'Creating') : tr('我的作品', 'My song') }}</span><img :src="safeLink(songCard.coverUrl) || defaultCover()" :alt="tr('歌曲封面', 'Song artwork')"/>
            <button v-if="playable" type="button" class="agent-cover-play" :aria-label="isPlaying ? tr('暂停这首歌', 'Pause this song') : tr('播放这首歌', 'Play this song')" @click="togglePlay">{{ isPlaying ? 'Ⅱ' : '▶' }}</button>
            <button type="button" class="agent-album-like" :disabled="blocked" :aria-pressed="liked" :aria-label="liked ? tr('取消喜欢', 'Unlike') : tr('喜欢', 'Like')" @click="send(liked ? '取消点赞当前作品' : '给当前作品点赞')">{{ liked ? '♥' : '♡' }}</button>
          </div>
        </div>
        <div class="agent-preview-meta"><strong>{{ songCard.title || tr('未命名作品', 'Untitled song') }}</strong><span v-if="songCard.style">{{ songCard.style }}</span></div>
        <p class="agent-song-status" role="status">{{ statusText }}</p>
        <div class="agent-player">
          <input class="agent-preview-range" type="range" min="0" :max="duration || 1" :value="playTime" :disabled="!currentPlaying || !duration" :aria-label="tr('播放进度', 'Playback position')" @input="player.seek(Number(($event.target as HTMLInputElement).value))"/>
          <div class="agent-player-times"><span>{{ formatTime(playTime) }}</span><span>{{ formatTime(duration) }}</span></div>
          <div class="agent-player-controls">
            <button type="button" :disabled="!currentPlaying" :aria-pressed="currentPlaying && player.repeatOne" :aria-label="tr('单曲循环', 'Repeat one')" @click="player.toggleRepeat()">⇄</button>
            <button type="button" :disabled="!currentPlaying || !player.hasQueue" :aria-label="tr('上一首', 'Previous track')" @click="player.next(-1)">◀▏</button>
            <button type="button" class="agent-preview-play" :disabled="!playable" :aria-label="isPlaying ? tr('暂停', 'Pause') : tr('播放', 'Play')" @click="togglePlay"><svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="isPlaying" d="M9 6v12M15 6v12"/><path v-else d="m9 6 9 6-9 6V6Z"/></svg></button>
            <button type="button" :disabled="!currentPlaying || !player.hasQueue" :aria-label="tr('下一首', 'Next track')" @click="player.next()">▕▶</button>
            <button type="button" :disabled="!player.tracks.length" :aria-expanded="showQueue" :aria-label="tr('播放列表', 'Playback queue')" @click="showQueue = !showQueue">☷</button>
          </div>
        </div>
        <div v-if="showQueue && player.tracks.length" class="agent-play-queue" :aria-label="tr('播放列表', 'Playback queue')"><button v-for="track in player.tracks" :key="track.id" type="button" :aria-current="player.current?.id === track.id ? 'true' : undefined" @click="player.toggle(track)">{{ track.title }}</button></div>
        <p v-if="player.current && !currentPlaying" class="agent-player-other">{{ tr('播放器正在播放其他作品', 'The player is on another song') }}</p>
        <p v-if="currentPlaying && player.error" class="agent-upload-error" role="alert">{{ player.error }}</p>
        <div class="agent-preview-tabs" role="tablist" :aria-label="tr('作品详情', 'Song details')">
          <button v-for="tab in (['lyrics', 'story', 'more'] as const)" :key="tab" type="button" role="tab" :aria-selected="previewTab === tab" @click="previewTab = tab">{{ tab === 'lyrics' ? tr('歌词', 'Lyrics') : tab === 'story' ? tr('作品故事', 'Story') : tr('更多', 'More') }}</button>
        </div>
        <div v-if="previewTab === 'lyrics'" class="agent-tab-content agent-real-lyrics" role="tabpanel"><strong v-if="songCard.lyrics" class="agent-lyrics-title">♫ {{ songCard.title }}</strong><p>{{ songCard.lyrics || tr('还没有歌词，可以在对话中让我写。', 'No lyrics yet. Ask me to write them.') }}</p></div>
        <div v-else-if="previewTab === 'story'" class="agent-tab-content agent-story-empty" role="tabpanel"><p v-if="storyLoading">{{ tr('正在加载作品故事…', 'Loading story…') }}</p><p v-else-if="storyError">{{ tr('作品故事加载失败', 'Unable to load story') }} <button type="button" @click="fetchStory">{{ tr('重试', 'Retry') }}</button></p><p v-else>{{ story || tr('暂无作品故事', 'No story yet') }}</p></div>
        <div v-else class="agent-tab-content agent-song-actions" role="tabpanel"><button v-if="!taskRunning" type="button" class="agent-start-button" :disabled="blocked || !songCard.lyrics" @click="send('用当前歌词生成音乐')">{{ audioUrl ? tr('重新生成音乐', 'Generate another version') : tr('生成音乐', 'Generate music') }}</button><button v-else type="button" :disabled="blocked" @click="send('停止生成')">{{ tr('停止生成', 'Cancel generation') }}</button><button type="button" :disabled="blocked" @click="input = tr('把副歌改得更温柔，保留其他部分', 'Make the chorus gentler and keep the rest')">{{ tr('修改歌词', 'Revise lyrics') }}</button></div>
        <div v-if="publicLink" class="agent-share-link"><a :href="publicLink" target="_blank" rel="noopener noreferrer">{{ tr('打开分享页面', 'Open share page') }}</a><button type="button" @click="copyShare">{{ tr('复制链接', 'Copy link') }}</button></div>
        <div class="agent-preview-actions agent-bottom-actions"><button type="button" :disabled="blocked || !playable" @click="send('下载当前作品')">↓ {{ downloadUnlocked ? tr('免费下载', 'Free download') : tr('下载（首次10积分）', 'Download (10 credits first time)') }}</button><button type="button" :disabled="blocked || !playable" @click="send('分享当前作品')">♧ {{ tr('分享', 'Share') }}</button><button type="button" :disabled="blocked" @click="input = tr('参考当前作品，帮我创作一首同风格的新歌', 'Create a new song inspired by the style of the current song')">✦ {{ tr('改编同款', 'Create a version') }}</button></div>
      </template>
      <div v-else class="agent-empty-song"><span aria-hidden="true">♫</span><strong>{{ tr('从一句灵感开始', 'Start with an idea') }}</strong><p>{{ tr('写好歌词后，作品会出现在这里。音乐完成后，就能试听、下载和分享。', 'Your song will appear here when its lyrics are ready. Once the music is generated, you can listen, download and share.') }}</p></div>
    </aside>
    <AgentNativeDialog v-if="nativePanel" :key="`${nativePanel.kind}:${nativePanel.workId || ''}`" :kind="nativePanel.kind" :work-id="nativePanel.workId" @close="nativePanel = null" @refresh="conversation.refresh()" @send="send"/>
  </div>
</template>

<style scoped>
.agent-conversation-head{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px;padding:0 4px 12px;color:#806a62}.agent-conversation-pill{font-size:12px;font-weight:700}.agent-conversation-sub{flex:1;margin:0;font-size:11px;color:#9b8278}.agent-preview-head{font-size:12px;color:#9b8278;margin-bottom:12px}.agent-preview-meta{display:grid;gap:5px;margin-top:14px}.agent-preview-meta strong{font-size:17px;color:#3d302c}.agent-preview-meta span{font-size:11px;color:#a0877d}.agent-quick-card{padding:14px;color:#9b8278;font-size:12px}.agent-quick-grid{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.agent-quick-grid button{border:1px solid #efd9cc;border-radius:12px;padding:8px 12px;background:#fffaf7;color:#956d55;text-align:left}.agent-start-button{border:0;border-radius:14px;padding:12px;background:linear-gradient(120deg,#ff9257,#ff4d2d);color:#fff;font-weight:600}

.agent-message-content{max-width:min(calc(100% - 49px),720px);min-width:0}.agent-message-content .agent-bubble{max-width:100%}.agent-capabilities{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}.agent-capabilities button{background:#fff8f3;border:1px solid #f0d9cb;color:#956d55;border-radius:18px;padding:6px 12px;font-size:12px;cursor:pointer}.agent-capabilities button:focus-visible{outline:3px solid #ffa279;outline-offset:2px}
.agent-composer-box textarea { grid-area: input; width: 100%; flex: 1; min-width: 0; resize: vertical; max-height: 180px; border: 0; outline: 0; background: transparent; padding: 8px; color: #4a3b39; font: inherit; line-height: 1.6; }
.agent-bubble p, .agent-real-lyrics p { white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-pending small { color: #fff; font-size: 11px; opacity: .8; }
.agent-message-failed { border-color: #d77363; }
.agent-preview { max-height: 100%; overflow-y: auto; }
.agent-song-status { margin: 12px 0; color: #a06550; font-size: 12px; }
.agent-song-actions { display: grid; gap: 10px; margin: 16px 0; }
.agent-song-actions > button:not(.agent-start-button), .agent-share-link button { border: 1px solid #f1d7c9; border-radius: 14px; padding: 8px 12px; background: #fff9f5; color: #845f50; }
.agent-real-lyrics { border-top: 1px solid #f4ded3; padding-top: 16px; }
.agent-real-lyrics summary { cursor: pointer; font-weight: 700; }
.agent-real-lyrics p { font-size: 13px; line-height: 1.9; color: #725f57; }
.agent-empty-song { display: grid; gap: 20px; text-align: center; padding: 40px 12px; color: #8b7065; }
.agent-empty-song > span { font-size: 52px; color: #ff714a; }
.agent-empty-song p { margin: 0; font-size: 13px; line-height: 1.9; }
.agent-share-link { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-top: 14px; font-size: 12px; }
.agent-reset-trigger { align-self: flex-end; border: 0; background: transparent; color: #9b8278; font-size: 12px; }
.agent-reset-confirm { margin-block: 10px; padding: 12px; border: 1px solid #f1d7c9; border-radius: 12px; background: #fff9f5; font-size: 12px; line-height: 1.7; color: #845f50; }
.agent-reset-confirm button { margin-right: 12px; }

.agent-preview { display:flex; flex-direction:column; height:100%; box-sizing:border-box; padding:24px; border-radius:24px; background:#fff9f6; box-shadow:0 12px 34px #ee9b8040; }
.agent-album { position:relative; padding:4px 20% 0 0; margin:0 0 4px; }
.agent-record { position:absolute; width:72%; aspect-ratio:1; right:0; top:7%; border-radius:50%; background:repeating-radial-gradient(circle,#171719 0 3px,#353537 4px 5px,#111 6px 9px); box-shadow:0 8px 24px #6b3d3a44; }
.agent-album-art { position:relative; width:100%; aspect-ratio:1; border-radius:20px 0 0 20px; overflow:hidden; box-shadow:0 12px 24px #dc958245; }
.agent-album-art img { display:block; width:100%; height:100%; object-fit:cover; }
.agent-album-like { position:absolute; right:8px; top:8px; border:0; border-radius:18px; padding:2px 9px; background:#533e4980; color:#fff; font-size:22px; line-height:1; cursor:pointer; }
.agent-album-like[aria-pressed="true"] { color:#ff4c37; }
.agent-preview-meta strong { font-size:22px; }.agent-preview-meta span { font-size:13px; color:#6e625f; }
.agent-song-status { margin:10px 0 16px; color:#8e7971; }
.agent-player { display:grid; gap:7px; }.agent-player .agent-preview-range { width:100%; background:#e9d9d8; accent-color:#ff5134; }.agent-player-times { display:flex; justify-content:space-between; font-size:10px; color:#6a5b58; }
.agent-player-controls { display:flex; justify-content:space-between; align-items:center; margin:8px 0 14px; }.agent-player-controls > button:not(.agent-preview-play) { border:0; background:transparent; color:#423b3a; font-size:19px; cursor:pointer; }.agent-player-controls .agent-preview-play { flex:0 0 48px; width:48px; height:48px; }.agent-player-controls button[aria-pressed="true"] { color:#ff5134; }.agent-play-queue { max-height:120px; overflow:auto; display:grid; gap:4px; }.agent-play-queue button { border:0; padding:7px; background:#fff5ef; color:#6e625f; text-align:left; }.agent-play-queue button[aria-current="true"] { color:#ef4a30; font-weight:700; }.agent-player-other { font-size:11px; color:#8b7d78; }
.agent-preview-tabs { display:flex; border-bottom:1px solid #f1ded8; }.agent-preview-tabs button { flex:1; border:0; border-bottom:2px solid transparent; background:transparent; color:#8b7d78; padding:12px 4px; cursor:pointer; }.agent-preview-tabs button[aria-selected="true"] { color:#352e2c; border-color:#ff5134; font-weight:700; }
.agent-tab-content { min-height:160px; max-height:240px; overflow:auto; }.agent-real-lyrics { padding-top:14px; border:0; }.agent-real-lyrics p { line-height:2; margin:8px 0; white-space:pre-wrap; }.agent-lyrics-title { color:#fa4d32; font-size:13px; }.agent-story-empty { padding:20px 0; color:#8b7d78; font-size:13px; white-space:pre-wrap; overflow-wrap:anywhere; }.agent-song-actions { align-content:start; }
.agent-bottom-actions { margin-top:auto; padding-top:16px; }.agent-bottom-actions > button:last-child { color:#f04e32; }
@media (max-width: 800px) { .agent-preview { height:auto; }.agent-album { max-width:350px; }.agent-tab-content { min-height:100px; } }
button:disabled { opacity: .5; cursor: not-allowed; }
</style>
