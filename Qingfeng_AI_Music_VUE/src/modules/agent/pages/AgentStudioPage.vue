<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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

const conversation = useAgentConversation()
const { messages, songCard, shareUrl, liked, taskRunning, loading, sending, error, refreshError, pending } = conversation
const player = usePlayer(), { credits, refreshCredits } = useCredits()
const input = ref(''), chat = ref<HTMLElement>(), fileInput = ref<HTMLInputElement>(), notice = ref('')
const confirmingReset = ref(false)
const blocked = computed(() => loading.value || sending.value || !!pending.value)
const english = computed(() => locale.value === 'en')
const tr = (zh: string, en: string) => english.value ? en : zh
const prompts = computed(() => [
  tr('你好，你可以帮我做什么？', 'Hi, what can you help me with?'),
  tr('帮我写一首关于旅行的歌词', 'Write lyrics about travelling'),
  tr('写一首送给妈妈的生日歌，先给我歌词', 'Write birthday lyrics for my mother'),
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
  return { id: `${card.songId}:${card.revisionId || card.revision}:${card.assetId || audioUrl.value}`, title: card.title, cover: safeLink(card.coverUrl) || defaultCover(), audio: audioUrl.value, provenance: 'GENERATED' }
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
function download() {
  if (!audioUrl.value || !songCard.value) return
  const url = new URL(audioUrl.value); url.searchParams.set('download', 'true')
  const link = document.createElement('a'); link.href = url.href; link.download = `${songCard.value.title || 'song'}.mp3`
  document.body.appendChild(link); link.click(); link.remove()
}
async function handleResult(result?: ConversationSnapshot) {
  if (!result) return
  if (result.action === 'play' && !isPlaying.value) togglePlay()
  if (result.action === 'download') download()
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
watch(() => messages.value.length, () => { void scrollChat() })
watch(() => JSON.stringify([accountState.value.authenticated, accountState.value.email, accountState.value.name, logtoState.value.authenticated, logtoState.value.email, logtoState.value.phoneMasked, logtoState.value.name]), () => {
  input.value = ''; notice.value = ''; confirmingReset.value = false
  clearReference(); void conversation.changeIdentity()
})
onMounted(() => { void conversation.refresh(); void refreshCredits().catch(() => undefined) })
onBeforeUnmount(() => { recognition?.stop?.(); clearReference() })
</script>

<template>
  <div class="agent-studio">
    <div class="agent-scenery" aria-hidden="true"><span/><span/><span/></div>
    <section class="agent-conversation" :aria-label="tr('音乐创作对话', 'Music conversation')">
      <header class="agent-conversation-head">
        <span class="agent-conversation-pill">♫ {{ tr('音乐创作助手', 'Music creation assistant') }}</span>
        <p class="agent-conversation-sub">{{ tr('聊灵感、写歌词、生成音乐，接着分享给喜欢的人。', 'Chat, write lyrics, create music and share it.') }}</p>
        <button type="button" class="agent-reset-trigger" :disabled="loading || sending" @click="confirmingReset = !confirmingReset">{{ tr('重新开始', 'Start over') }}</button>
      </header>
      <div v-if="confirmingReset" class="agent-reset-confirm" role="alert">
        <p>{{ tr('这会清空聊天及当前作品关联，已保存的作品仍在「我的音乐」。已提交的音乐任务不会被取消；如需停止，请先查看当前任务。', 'This clears the chat and current song selection. Saved songs remain in My Music. Submitted music jobs keep running; review the current task first if you want to stop it.') }}</p>
        <button type="button" :disabled="loading || sending" @click="reset">{{ tr('确认清空聊天', 'Clear chat') }}</button>
        <button type="button" @click="confirmingReset = false">{{ tr('保留对话', 'Keep chat') }}</button>
      </div>
      <div ref="chat" class="agent-chat" role="log" aria-live="polite">
        <p v-if="loading" class="agent-typing" role="status">{{ tr('正在恢复对话…', 'Loading conversation…') }}</p>
        <div v-if="!messages.length && !loading && !error" class="agent-message assistant">
          <div class="agent-bubble"><p>{{ tr('你好！我们可以先聊聊，也可以直接写歌。告诉我你的想法，我会记住这首作品，和你一起修改、生成音乐、分享。', 'Hi! We can chat or start a song. Tell me your idea and we can write, revise, generate and share it together.') }}</p></div>
        </div>
        <div v-for="message in messages" :key="message.id" class="agent-message" :class="message.role">
          <div v-if="messageText(message)" class="agent-bubble" :class="{ 'agent-message-failed': message.state === 'FAILED' }"><p>{{ messageText(message) }}</p></div>
        </div>
        <div v-if="pending" class="agent-message user agent-pending"><div class="agent-bubble"><p>{{ pending.text }}</p><small>{{ sending ? tr('发送中…', 'Sending…') : tr('结果待确认', 'Awaiting confirmation') }}</small></div></div>
        <p v-if="sending" class="agent-typing" role="status">{{ tr('正在处理你的请求…', 'Working on your request…') }}</p>
        <div v-if="!messages.length && !loading" class="agent-quick-card"><strong>{{ tr('试着说', 'Try saying') }}</strong><div class="agent-quick-grid"><button v-for="prompt in prompts" :key="prompt" type="button" :disabled="blocked" @click="send(prompt)"><span>{{ prompt }}</span></button></div></div>
      </div>
      <div class="agent-composer">
        <p v-if="error" class="agent-upload-error" role="alert">{{ error }} <button type="button" :disabled="sending || loading" @click="pending ? retry() : conversation.refresh()">{{ tr('重试', 'Retry') }}</button></p>
        <button v-if="pending?.rejected && !sending" type="button" @click="editRejected">{{ tr('修改未发送的内容', 'Edit rejected message') }}</button>
        <p v-else-if="pending && !sending" class="agent-section-hint">{{ tr('上次请求的结果尚未确认。重试会核对同一次请求。', 'The previous request is unconfirmed. Retry checks the same request.') }} <button type="button" @click="retry">{{ tr('重试原请求', 'Retry request') }}</button></p>
        <p v-if="refreshError" class="agent-upload-error" role="status">{{ refreshError }}</p>
        <form class="agent-composer-box" @submit.prevent="send()">
          <textarea v-model="input" rows="2" maxlength="4000" :placeholder="tr('聊聊你的想法，或说「把副歌改得更温柔」…', 'Share an idea, or say “make the chorus gentler”…')" :aria-label="tr('创作消息', 'Message')" @keydown.enter.exact="onEnter"/>
          <button type="button" class="agent-composer-clip" :aria-label="tr('参考音乐', 'Reference audio')" @click="fileInput?.click()">＋</button>
          <button type="button" class="agent-composer-mic" :aria-label="tr('语音输入', 'Voice input')" @click="startVoice">♩</button>
          <button type="submit" class="agent-composer-send" :disabled="!input.trim() || blocked" :aria-busy="sending" :aria-label="tr('发送', 'Send')">↑</button>
        </form>
        <input ref="fileInput" type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac" hidden @change="attach"/>
        <p v-if="reference" class="agent-upload-file"><span>{{ reference.name }}</span><button type="button" @click="playReference">{{ tr('试听', 'Listen') }}</button><button v-if="reference.summary && !analyzing" type="button" @click="useReference">{{ tr('将特征加入输入', 'Use audio features') }}</button><button type="button" :aria-label="tr('移除参考', 'Remove reference')" @click="clearReference">×</button></p>
        <p v-if="reference" class="agent-section-hint">{{ analyzing ? tr('正在分析参考音乐…', 'Analysing audio…') : reference.summary || tr('音频仅供本机试听。', 'Audio stays on this device.') }}</p>
        <p v-if="referenceError" class="agent-upload-error" role="status">{{ referenceError }}</p>
        <p v-if="notice" class="agent-voice-hint" role="status">{{ notice }}</p>
        <p v-if="credits" class="agent-section-hint">{{ tr('当前', 'Balance') }} {{ credits.balance }} {{ tr('积分 · 生成歌词', 'credits · Lyrics') }} {{ credits.lyricsCost }} · {{ tr('生成音乐', 'Music') }} {{ credits.musicCost }}</p>
      </div>
    </section>
    <aside class="agent-preview" :aria-label="tr('当前作品', 'Current song')">
      <div class="agent-preview-head"><strong>{{ tr('当前作品', 'Current song') }}</strong></div>
      <template v-if="songCard?.songId">
        <div class="agent-preview-cover"><img :src="safeLink(songCard.coverUrl) || defaultCover()" :alt="tr('歌曲封面', 'Song artwork')"/><button v-if="playable" type="button" class="agent-preview-play" :aria-label="isPlaying ? tr('暂停', 'Pause') : tr('播放', 'Play')" @click="togglePlay">{{ isPlaying ? 'Ⅱ' : '▷' }}</button></div>
        <div class="agent-preview-meta"><strong>{{ songCard.title || tr('未命名作品', 'Untitled song') }}</strong><span>{{ songCard.style }}</span></div>
        <p class="agent-song-status" role="status">{{ statusText }}</p>
        <div v-if="playable" class="agent-preview-progress"><input type="range" min="0" :max="duration || 1" :value="playTime" :disabled="!currentPlaying || !duration" :aria-label="tr('播放进度', 'Playback position')" @input="player.seek(Number(($event.target as HTMLInputElement).value))"/><div><span>{{ formatTime(playTime) }}</span><span>{{ formatTime(duration) }}</span></div></div>
        <p v-if="currentPlaying && player.error" class="agent-upload-error" role="alert">{{ player.error }}</p>
        <div class="agent-preview-actions"><button type="button" :disabled="blocked" :aria-pressed="liked" @click="send(liked ? '取消点赞当前作品' : '给当前作品点赞')">{{ liked ? tr('已喜欢', 'Liked') : tr('喜欢', 'Like') }}</button><button type="button" :disabled="blocked || !playable" @click="send('下载当前作品')">{{ tr('下载', 'Download') }}</button><button type="button" :disabled="blocked || !playable" @click="send('分享当前作品')">{{ tr('分享', 'Share') }}</button></div>
        <div v-if="publicLink" class="agent-share-link"><a :href="publicLink" target="_blank" rel="noopener noreferrer">{{ tr('打开分享页面', 'Open share page') }}</a><button type="button" @click="copyShare">{{ tr('复制链接', 'Copy link') }}</button></div>
        <div class="agent-song-actions"><button v-if="!taskRunning" type="button" class="agent-start-button" :disabled="blocked || !songCard.lyrics" @click="send('用当前歌词生成音乐')">{{ audioUrl ? tr('重新生成音乐', 'Generate another version') : tr('生成音乐', 'Generate music') }}</button><button v-else type="button" :disabled="blocked" @click="send('停止生成')">{{ tr('停止生成', 'Cancel generation') }}</button><button type="button" :disabled="blocked" @click="input = tr('把副歌改得更温柔，保留其他部分', 'Make the chorus gentler and keep the rest')">{{ tr('修改歌词', 'Revise lyrics') }}</button></div>
        <details class="agent-real-lyrics" open><summary>{{ tr('歌词', 'Lyrics') }}</summary><p>{{ songCard.lyrics || tr('还没有歌词，可以在对话中让我写。', 'No lyrics yet. Ask me to write them.') }}</p></details>
      </template>
      <div v-else class="agent-empty-song"><span aria-hidden="true">♫</span><strong>{{ tr('从一句灵感开始', 'Start with an idea') }}</strong><p>{{ tr('写好歌词后，作品会出现在这里。音乐完成后，就能试听、下载和分享。', 'Your song will appear here when its lyrics are ready. Once the music is generated, you can listen, download and share.') }}</p></div>
    </aside>
  </div>
</template>

<style scoped>
.agent-composer-box textarea { flex: 1; min-width: 0; resize: vertical; max-height: 180px; border: 0; outline: 0; background: transparent; padding: 8px; color: #4a3b39; font: inherit; line-height: 1.6; }
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
button:disabled { opacity: .5; cursor: not-allowed; }
</style>
