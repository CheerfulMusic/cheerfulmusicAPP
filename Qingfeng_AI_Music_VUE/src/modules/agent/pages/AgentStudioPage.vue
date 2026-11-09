<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { defaultCover, locale, t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { tracks } from '../../home/model/tracks'
import { isLiked, toggleReaction } from '../../home/model/hot-songs'
import { rewriteInspiration } from '../../studio/api/works'
import { writeAgentBrief } from '../model/brief'
import { analyzeRequest, inspirationBoards, interestTags, modeOptions, quickPrompts, type AgentBrief, type AgentMode } from '../model/assistant'

interface Message { id: number; role: 'assistant' | 'user'; text: string; lines?: string[]; brief?: AgentBrief; suggestions?: string[] }
interface VoiceResult { results: ArrayLike<ArrayLike<{ transcript: string }>> }
interface VoiceRecognition { lang: string; interimResults: boolean; onresult: ((event: VoiceResult) => void) | null; onerror: (() => void) | null; onend: (() => void) | null; start: () => void }

const router = useRouter(), player = usePlayer()
const greeting = [t('你好！我是你的音乐创作助手~'), t('告诉我你的心情、兴趣，或一个小故事，'), t('我来为你生成专属的歌曲 ♫')]
let messageId = 0, replyTimer: number | undefined
const messages = ref<Message[]>([{ id: messageId, role: 'assistant', text: '', lines: greeting }])
const chat = ref<HTMLElement>(), fileInput = ref<HTMLInputElement>()
const input = ref(''), busy = ref(false), polishing = ref(false), polishError = ref(''), voiceHint = ref('')
const mode = ref<AgentMode>('write'), interests = ref<string[]>(['时尚'])
const panelOpen = ref(true), boardIndex = ref(0), previewIndex = ref(0)
const brief = ref<AgentBrief | undefined>()
const uploaded = ref<{ name: string; url: string } | undefined>()
const board = computed(() => inspirationBoards[boardIndex.value % inspirationBoards.length]!)
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
const previewTracks = [tracks[2]!, tracks[0]!, tracks[7]!]
const lyricBoards = [
  ['下一站，去有海风的地方', '把地图折进口袋', '让风替我轻轻哼唱'],
  ['想和你，把平凡的日子慢慢过成爱', '清晨的灯还亮着', '我们不说晚安'],
  ['沿途的风景慢慢后退', '你在身旁不说话', '也是很好的回答'],
]
const track = computed(() => previewTracks[previewIndex.value % previewTracks.length]!)
const lyricLines = computed(() => lyricBoards[previewIndex.value % lyricBoards.length]!)
const isPreviewLoaded = computed(() => player.current?.id === track.value.id)
const previewDuration = computed(() => isPreviewLoaded.value && player.duration ? player.duration : track.value.durationSeconds)
const previewTime = computed(() => isPreviewLoaded.value ? player.time : 0)
const previewProgress = computed(() => Math.min(100, previewTime.value / (previewDuration.value || 1) * 100))
const isCurrentPlaying = computed(() => isPreviewLoaded.value && (player.playing || player.starting))
const activeLyric = computed(() => isPreviewLoaded.value ? Math.min(2, Math.floor(previewTime.value / (previewDuration.value / 3))) : 0)
const fmt = (value: number) => `${Math.floor((value || 0) / 60).toString().padStart(2, '0')}:${Math.floor((value || 0) % 60).toString().padStart(2, '0')}`

async function scrollChat() { await nextTick(); chat.value?.scrollTo({ top: chat.value.scrollHeight, behavior: 'smooth' }) }
function nextBoard() { boardIndex.value++ }
function toggleInterest(tag: string) { interests.value = interests.value.includes(tag) ? interests.value.filter(item => item !== tag) : [...interests.value, tag] }
function chooseMode(option: typeof modeOptions[number]) {
  mode.value = option.id
  messages.value.push({ id: ++messageId, role: 'assistant', text: t(option.hint) })
  void scrollChat()
}
function send(value?: string) {
  const text = (value ?? input.value).trim()
  if (!text || busy.value) return
  input.value = ''; voiceHint.value = ''
  messages.value.push({ id: ++messageId, role: 'user', text })
  busy.value = true
  const reply = analyzeRequest(text, mode.value, interests.value)
  const line = `${t('收到！主题是')}${reply.topic}${t('，我为你选了')}${reply.style}${t('，情绪关键词是')}${reply.mood}${t('。可以直接开始创作，也可以继续补充细节 ♫')}`
  replyTimer = window.setTimeout(() => {
    messages.value.push({ id: ++messageId, role: 'assistant', text: line, brief: reply.brief, suggestions: reply.suggestions })
    brief.value = reply.brief
    busy.value = false
    void scrollChat()
  }, 420)
  void scrollChat()
}
async function polish(message: Message) {
  if (!message.brief || polishing.value) return
  const source = message.brief.story
  polishing.value = true; polishError.value = ''
  try {
    const result = await rewriteInspiration(source, locale.value === 'en' ? 'en' : 'zh-CN')
    if (result.text && message.brief?.story === source) {
      const next = { ...message.brief, story: result.text }
      message.brief = next
      if (brief.value?.story === source) brief.value = next
    }
  } catch { polishError.value = t('AI 整理暂不可用，已按原文继续。') }
  finally { polishing.value = false }
}
function polishLatest() {
  const latest = [...messages.value].reverse().find(message => message.brief)
  if (latest) void polish(latest)
  else voiceHint.value = t('先告诉我你的创作需求，再让 AI 帮忙润色。')
}
async function beginCreation() {
  const source = input.value.trim()
  const next = brief.value ?? analyzeRequest(source || '写一首关于今天的歌', mode.value, interests.value).brief
  writeAgentBrief(next)
  await router.push({ path: '/create', query: { from: 'agent' } })
}
function startVoice() {
  const constructors = window as unknown as { SpeechRecognition?: new () => VoiceRecognition; webkitSpeechRecognition?: new () => VoiceRecognition }
  const Recognition = constructors.SpeechRecognition || constructors.webkitSpeechRecognition
  if (!Recognition) { voiceHint.value = t('当前浏览器不支持语音输入，可以直接打字告诉我。'); return }
  const recognition = new Recognition()
  recognition.lang = locale.value === 'en' ? 'en-US' : 'zh-CN'
  recognition.interimResults = false
  recognition.onresult = event => { const text = event.results[0]?.[0]?.transcript; if (text) input.value = text }
  recognition.onerror = () => { voiceHint.value = t('语音识别没有成功，可以直接打字告诉我。') }
  recognition.onend = () => { voiceHint.value = '' }
  try { recognition.start() } catch { voiceHint.value = t('语音识别没有成功，可以直接打字告诉我。') }
}
function onFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  if (uploaded.value) URL.revokeObjectURL(uploaded.value.url)
  uploaded.value = { name: file.name, url: URL.createObjectURL(file) }
}
function clearUpload() {
  if (!uploaded.value) return
  if (player.current?.id === 'agent-upload') player.close()
  URL.revokeObjectURL(uploaded.value.url)
  uploaded.value = undefined
  if (fileInput.value) fileInput.value.value = ''
}
function playUpload() {
  if (!uploaded.value) return
  void player.toggle({ id: 'agent-upload', title: uploaded.value.name, cover: defaultCover(), audio: uploaded.value.url, provenance: 'IMPORTED' })
}
function step(direction: number) { previewIndex.value = (previewIndex.value + direction + previewTracks.length) % previewTracks.length }
function togglePreview() { void player.toggle(track.value) }
function seekPreview(event: Event) { if (isPreviewLoaded.value) player.seek(Number((event.target as HTMLInputElement).value)) }
async function copyLink() {
  try { await navigator.clipboard.writeText(new URL(track.value.audio, window.location.origin).href); voiceHint.value = t('已复制试听链接') }
  catch { voiceHint.value = t('复制失败，可以直接下载试听。') }
}
function useAsInspiration() { send(`我想要一首${track.value.style}风格的歌`) }
onBeforeUnmount(() => {
  window.clearTimeout(replyTimer)
  if (uploaded.value) { if (player.current?.id === 'agent-upload') player.close(); URL.revokeObjectURL(uploaded.value.url) }
})
</script>
<template>
  <div class="agent-studio">
    <div class="agent-scenery" aria-hidden="true"><span/><span/><span/></div>

    <aside class="agent-assistant-panel" :class="{ 'is-collapsed': !panelOpen }" :aria-label="t('创作助手')">
      <div class="agent-panel-head">
        <h2><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2ZM19 15l.9 2.6L22.5 18.5l-2.6.9L19 22l-.9-2.6-2.6-.9 2.6-.9L19 15Z"/></svg>{{ t('创作助手') }}</h2>
        <button class="agent-panel-toggle" :aria-expanded="panelOpen" :aria-label="panelOpen ? t('收起创作助手') : t('展开创作助手')" @click="panelOpen = !panelOpen"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 14 6-6 6 6"/></svg></button>
      </div>
      <div v-if="panelOpen" class="agent-panel-body">
        <section class="agent-inspiration">
          <div class="agent-section-head"><strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 18h6m-5 3h4M12 3a6 6 0 0 0-3.5 10.9c.6.5.9 1.1 1 1.8h5c.1-.7.4-1.3 1-1.8A6 6 0 0 0 12 3Z"/></svg>{{ t('灵感一下') }}</strong><button type="button" @click="nextBoard">{{ t('换一批') }}<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 6.3M20 5v6h-6"/></svg></button></div>
          <p class="agent-section-hint">{{ t('不知道写什么？试试这些灵感~') }}</p>
          <div class="agent-inspiration-grid">
            <button v-for="card in board" :key="card.title" type="button" class="agent-inspiration-card" @click="send(card.prompt)">
              <img :src="card.cover" :alt="card.title" loading="lazy"/>
              <span class="agent-card-play" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="m9 6 9 6-9 6V6Z"/></svg></span>
              <strong>{{ card.title }}</strong><small>{{ card.caption }}</small>
            </button>
          </div>
        </section>
        <section class="agent-interests">
          <div class="agent-section-head"><strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9Z"/></svg>{{ t('我的兴趣标签') }}</strong><span class="agent-section-manage">{{ t('管理') }} ›</span></div>
          <p class="agent-section-hint">{{ t('选择你喜欢的，获得更懂你的推荐') }}</p>
          <div class="agent-tag-list"><button v-for="tag in interestTags" :key="tag" type="button" :aria-pressed="interests.includes(tag)" @click="toggleInterest(tag)">{{ t(tag) }}</button></div>
        </section>
        <section class="agent-upload">
          <div class="agent-section-head"><strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 18V5l11-2v13M9 9l11-2M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm11-2a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"/></svg>{{ t('上传参考音乐') }}</strong></div>
          <p class="agent-section-hint">{{ t('添加你喜欢的歌曲，让 AI 更懂你') }}</p>
          <button type="button" class="agent-upload-box" @click="fileInput?.click()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m-4 4 4-4 4 4M4 17v2a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-2"/></svg>{{ t('拖拽上传 或 点击选择') }}<small>{{ t('支持 MP3、WAV、MAA 格式') }}</small></button>
          <input ref="fileInput" type="file" accept="audio/*,.mp3,.wav,.m4a" hidden :aria-label="t('上传参考音乐')" @change="onFile"/>
          <div v-if="uploaded" class="agent-upload-file"><span :title="uploaded.name">{{ uploaded.name }}</span><button type="button" @click="playUpload">{{ t('试听') }}</button><button type="button" :aria-label="t('移除')" @click="clearUpload">×</button></div>
        </section>
        <button type="button" class="agent-start-button" @click="beginCreation"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2Z"/></svg>{{ t('开启我的创作之旅') }}<span aria-hidden="true">→</span></button>
      </div>
    </aside>

    <section class="agent-conversation" :aria-label="t('Agent 创作对话')">
      <div class="agent-dots" aria-hidden="true"><i v-for="n in 5" :key="n" :class="{ active: n === 1 }"/></div>
      <div ref="chat" class="agent-chat" role="log" :aria-label="t('创作对话记录')">
        <div v-for="message in messages" :key="message.id" class="agent-message" :class="message.role">
          <div v-if="message.role === 'assistant'" class="agent-bot-avatar" aria-hidden="true">
            <svg viewBox="0 0 48 48"><path d="M24 5v5M17 3l2 4M31 3l-2 4" fill="none" stroke="#3c7bd6" stroke-width="2.6" stroke-linecap="round"/><rect x="8" y="10" width="32" height="27" rx="13" fill="#4a90e2"/><rect x="12" y="14" width="24" height="18" rx="9" fill="#eaf5ff"/><circle cx="19" cy="23" r="3.4" fill="#2b6cc4"/><circle cx="29" cy="23" r="3.4" fill="#2b6cc4"/><path d="M20 29c2.4 1.6 5.6 1.6 8 0" fill="none" stroke="#2b6cc4" stroke-width="1.8" stroke-linecap="round"/><circle cx="24" cy="6" r="2.6" fill="#ff6a4d"/></svg>
          </div>
          <div class="agent-bubble">
            <template v-if="message.lines"><p v-for="line in message.lines" :key="line">{{ line }}</p></template>
            <p v-else>{{ message.text }}</p>
            <div v-if="message.brief" class="agent-brief-card">
              <strong class="agent-brief-head"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2Z"/></svg>{{ t('已整理好创作灵感') }}</strong>
              <p class="agent-brief-story">{{ message.brief.story }}</p>
              <p class="agent-brief-meta"><span>{{ t('风格：') }}{{ message.brief.style }}</span><span>{{ t('情绪：') }}{{ message.brief.mood }}</span></p>
              <div v-if="message.suggestions?.length" class="agent-brief-suggestions"><button v-for="style in message.suggestions" :key="style" type="button" @click="send(`我想要一首${style}风格的歌`)">{{ style }}</button></div>
              <div class="agent-brief-actions"><button type="button" :disabled="polishing" @click="polish(message)">{{ polishing ? t('AI 整理中…') : t('AI 润色描述') }}</button><button type="button" class="primary" @click="beginCreation">{{ t('用它开始创作') }} →</button></div>
              <p v-if="polishError" class="agent-brief-error" role="status">{{ polishError }}</p>
            </div>
          </div>
        </div>
        <div v-if="messages.length === 1" class="agent-quick-card">
          <strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3Z"/></svg>{{ t('你可以这样说：') }}</strong>
          <div class="agent-quick-grid">
            <button v-for="prompt in quickPrompts" :key="prompt.text" type="button" @click="send(prompt.prompt)">
              <svg viewBox="0 0 24 24" :style="{ color: prompt.color }" aria-hidden="true"><path :d="promptPaths[prompt.icon]"/></svg><span>{{ locale === 'en' ? prompt.en : prompt.text }}</span>
            </button>
          </div>
        </div>
        <p v-if="busy" class="agent-typing" role="status">{{ t('正在想…') }}</p>
      </div>

      <div class="agent-composer">
        <form class="agent-composer-box" @submit.prevent="send()">
          <svg class="agent-composer-spark" viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3ZM19 16l.7 2.3L22 19l-2.3.7L19 22l-.7-2.3L16 19l2.3-.7L19 16Z"/></svg>
          <input v-model="input" type="text" maxlength="500" :placeholder="placeholder" :aria-label="t('告诉我的创作需求')"/>
          <button type="button" class="agent-composer-mic" :aria-label="t('语音输入')" @click="startVoice"><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg></button>
          <button type="button" class="agent-composer-polish" :aria-label="t('AI 润色描述')" @click="polishLatest"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3Z"/></svg></button>
          <button type="submit" class="agent-composer-send" :disabled="!input.trim() || busy" :aria-label="t('发送')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 12h15m-6-6 6 6-6 6"/></svg></button>
        </form>
        <p v-if="voiceHint" class="agent-voice-hint" role="status">{{ voiceHint }}</p>
        <div class="agent-modes"><button v-for="option in modeOptions" :key="option.id" type="button" :class="{ active: mode === option.id }" :aria-pressed="mode === option.id" @click="chooseMode(option)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 1.9 5.6L19.5 10.5 13.9 12.4 12 18l-1.9-5.6L4.5 10.5l5.6-1.9L12 3Z"/></svg>{{ t(option.label) }}</button></div>
        <p class="agent-slogan">{{ t('让音乐，点亮你的每一种情绪') }} <span aria-hidden="true">♡</span></p>
      </div>
    </section>

    <aside class="agent-preview" :aria-label="t('歌曲预览')">
      <div class="agent-preview-head"><strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2Z"/></svg>{{ t('歌曲预览') }}</strong><div><button type="button" :aria-label="t('上一首')" @click="step(-1)">‹</button><button type="button" :aria-label="t('下一首')" @click="step(1)">›</button></div></div>
      <div class="agent-preview-cover"><img :src="track.cover" :alt="t('歌曲封面')"/><span class="agent-preview-count">{{ previewIndex + 1 }}/{{ previewTracks.length }}</span><button type="button" class="agent-preview-play" :aria-label="isCurrentPlaying ? t('暂停') : t('播放')" @click="togglePreview"><svg viewBox="0 0 24 24" aria-hidden="true"><path v-if="isCurrentPlaying" d="M9 6v12M15 6v12"/><path v-else d="m9 6 9 6-9 6V6Z"/></svg></button></div>
      <div class="agent-preview-meta"><strong>{{ track.title }}</strong><span>{{ track.tags.join(' · ') }}</span></div>
      <div class="agent-preview-progress"><input class="agent-preview-range" type="range" min="0" :max="previewDuration" step="0.1" :value="previewTime" :disabled="!isPreviewLoaded" :style="{ background: `linear-gradient(90deg,#ff9257,#ff432d) 0/${previewProgress}% 100% no-repeat,#f2ded4` }" :aria-label="t('播放进度')" @input="seekPreview"/><div><span>{{ fmt(previewTime) }}</span><span>{{ fmt(previewDuration) }}</span></div></div>
      <div class="agent-preview-lyrics"><p v-for="(line, index) in lyricLines" :key="line" :class="{ active: index === activeLyric }">{{ line }}</p><small>{{ t('示例歌词') }}</small></div>
      <div class="agent-preview-actions">
        <button type="button" :class="{ liked: isLiked(track.id) }" @click="toggleReaction(track.id, 'like')"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20s-7-4.4-7-9a4 4 0 0 1 7-2.6A4 4 0 0 1 19 11c0 4.6-7 9-7 9Z"/></svg>{{ t('喜欢') }}</button>
        <a :href="track.audio" :download="`${track.title}.mp3`"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11m-4-4 4 4 4-4M5 19h14"/></svg>{{ t('下载') }}</a>
        <details class="agent-preview-more"><summary>{{ t('更多') }}<span aria-hidden="true">▾</span></summary><div><button type="button" @click="copyLink">{{ t('复制歌曲链接') }}</button><button type="button" @click="useAsInspiration">{{ t('用这首歌找灵感') }}</button></div></details>
      </div>
    </aside>
  </div>
</template>
