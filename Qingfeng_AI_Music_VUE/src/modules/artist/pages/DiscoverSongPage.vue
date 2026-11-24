<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { platform } from '../../../platform'
import { ApiError } from '../../../shared/http/api-client'
import { discoverHref, formatDate, locale, t } from '../../../shared/i18n'
import { siteOrigin } from '../../../shared/i18n/home-seo'
import { showLoginDialog } from '../../../shared/identity/login-dialog'
import { usePlayer } from '../../../shared/player/store'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import {
  discoverSongHref, getDiscoverComments, getDiscoverSong, postDiscoverComment, removeDiscoverComment,
  setDiscoverDescription, setDiscoverLike, type DiscoverComment, type DiscoverSongDetail,
} from '../api/discover-song'
import ArtistShareButton from '../components/ArtistShareButton.vue'
import { publicDiscoverTrack } from '../model/discover-shelves'

const route = useRoute(), router = useRouter(), player = usePlayer()
const brandSymbol = '/brand/favicon-96.png'
const detail = ref<DiscoverSongDetail>()
const loading = ref(true), unavailable = ref(false), loadError = ref(false)
const commentSection = ref<HTMLElement>()
const expanded = ref(false), commentText = ref(''), descriptionText = ref(''), editingDescription = ref(false)
const pending = ref(''), loadingMore = ref(false), feedback = ref('')
const feedbackArea = ref<'hero' | 'comments' | 'description'>('hero')
let epoch = 0
let commentRequest: { id: string; text: string } | undefined

const track = computed(() => detail.value ? publicDiscoverTrack(detail.value.release) : undefined)
const isCurrent = computed(() => Boolean(track.value && player.current?.audio === track.value.audio))
const playing = computed(() => isCurrent.value && (player.playing || player.starting))
const duration = computed(() => {
  const seconds = isCurrent.value && player.duration > 0 ? player.duration : detail.value?.durationSeconds
  if (!seconds || !Number.isFinite(seconds) || seconds <= 0) return ''
  const whole = Math.floor(seconds)
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
})
const genreNames: Record<string, string> = {
  pop: '流行', folk: '民谣', guofeng: '古风', rock: '摇滚', electronic: '电子', instrumental: '纯音乐',
  'global-pop': '欧美流行', 'jazz-soul': '爵士与灵魂乐',
}
const genre = computed(() => genreNames[detail.value?.release.genre ?? ''])
const lyricLines = computed(() => detail.value?.lyrics.trim().split('\n') ?? [])
const shownLyrics = computed(() => (expanded.value ? lyricLines.value : lyricLines.value.slice(0, 3)).join('\n'))
const numberFormat = computed(() => new Intl.NumberFormat(locale.value))
const shareUrl = computed(() => detail.value ? new URL(discoverSongHref(detail.value.release.song),
  platform.kind === 'web' ? window.location.origin : siteOrigin).href : '')
const busy = computed(() => Boolean(pending.value) || loadingMore.value)
const communityAvailable = computed(() => detail.value?.communityAvailable !== false)

async function load() {
  const request = ++epoch
  const id = String(route.params.id ?? '')
  const version = Number(route.params.version)
  loading.value = !detail.value
  unavailable.value = false
  loadError.value = false
  if (!id || !Number.isSafeInteger(version) || version < 1) {
    loading.value = false
    unavailable.value = true
    return
  }
  try {
    const result = await getDiscoverSong(id, version)
    if (request !== epoch) return
    detail.value = result
    if (track.value) player.selectIfEmpty(track.value, result.durationSeconds)
    document.title = `${result.release.song.title} · Cheerful Music`
  } catch (error) {
    if (request !== epoch) return
    unavailable.value = error instanceof ApiError && error.status === 404
    loadError.value = !unavailable.value
    detail.value = undefined
  } finally {
    if (request === epoch) { loading.value = false; pending.value = ''; loadingMore.value = false }
  }
}

watch(() => [route.params.id, route.params.version], () => {
  detail.value = undefined
  expanded.value = false
  commentText.value = ''
  commentRequest = undefined
  editingDescription.value = false
  feedback.value = ''
  void load()
}, { immediate: true })

function refreshIdentity() { void load() }
onMounted(() => window.addEventListener('qingfeng:identity-changed', refreshIdentity))
onBeforeUnmount(() => { ++epoch; window.removeEventListener('qingfeng:identity-changed', refreshIdentity) })

function returnToDiscover(event: MouseEvent, navigate: (event: MouseEvent) => unknown) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return
  const previous = window.history.state?.back
  if (typeof previous === 'string' && /^(?:\/en)?\/discover(?:[?#]|$)/.test(previous)) {
    event.preventDefault()
    router.back()
  } else void navigate(event)
}

function play() { if (track.value) void player.toggle(track.value) }

function jumpToComments() {
  const block = commentSection.value
  block?.focus({ preventScroll: true })
  block?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
}

async function mutate(action: 'like' | 'comment' | 'delete' | 'description', operation: (value: DiscoverSongDetail) => Promise<void>) {
  const value = detail.value
  if (!value || busy.value || !communityAvailable.value) return
  if (!value.signedIn) { showLoginDialog(); return }
  const request = epoch
  pending.value = action
  feedback.value = ''
  feedbackArea.value = action === 'like' ? 'hero' : action === 'description' ? 'description' : 'comments'
  try { await operation(value) }
  catch (error) {
    if (request !== epoch) return
    if (error instanceof ApiError && error.status === 401) { value.signedIn = false; showLoginDialog() }
    else if (error instanceof ApiError && error.status === 404) void load()
    else feedback.value = error instanceof Error ? error.message : '操作失败，请重试。'
  } finally { if (request === epoch) pending.value = '' }
}

function toggleLike() {
  const request = epoch
  void mutate('like', async value => {
    const result = await setDiscoverLike(value.release.song, !value.likes.liked)
    if (request === epoch) value.likes = result
  })
}

function submitComment() {
  const text = commentText.value.trim()
  if (!text || text.length > 400) return
  const request = epoch
  void mutate('comment', async value => {
    if (commentRequest?.text !== text) commentRequest = { id: crypto.randomUUID(), text }
    const result = await postDiscoverComment(value.release.song, commentRequest.id, text)
    if (request !== epoch) return
    value.comments.items = [result.comment, ...value.comments.items.filter(item => item.id !== result.comment.id)]
    value.commentCount = result.count
    commentText.value = ''
    commentRequest = undefined
  })
}

function deleteComment(item: DiscoverComment) {
  if (!item.removable || busy.value || !window.confirm(t('确认删除这条评论？'))) return
  const request = epoch
  void mutate('delete', async value => {
    const result = await removeDiscoverComment(value.release.song, item.id)
    if (request !== epoch) return
    value.comments.items = value.comments.items.filter(comment => comment.id !== item.id)
    value.commentCount = result.count
  })
}

async function loadMore() {
  const value = detail.value, cursor = value?.comments.nextCursor
  if (!value || !cursor || busy.value) return
  const request = epoch
  loadingMore.value = true
  feedback.value = ''
  feedbackArea.value = 'comments'
  try {
    const page = await getDiscoverComments(value.release.song, cursor)
    if (request !== epoch) return
    const seen = new Set(value.comments.items.map(item => item.id))
    value.comments.items.push(...page.items.filter(item => !seen.has(item.id)))
    value.comments.nextCursor = page.nextCursor
  } catch (error) {
    if (request !== epoch) return
    if (error instanceof ApiError && error.status === 404) void load()
    else feedback.value = '暂时无法加载更多评论，请重试。'
  } finally { if (request === epoch) loadingMore.value = false }
}

function editDescription() {
  descriptionText.value = detail.value?.description ?? ''
  editingDescription.value = true
}

function saveDescription() {
  const request = epoch
  void mutate('description', async value => {
    const result = await setDiscoverDescription(value.release.song, descriptionText.value)
    if (request !== epoch) return
    value.description = result.text
    editingDescription.value = false
  })
}
</script>

<template>
  <div class="song-detail">
    <header class="song-detail-header">
      <RouterLink class="song-detail-brand" to="/" :aria-label="t('Cheerful Music 青风音乐首页')">
        <img :src="brandSymbol" alt="" width="40" height="40"><span>Cheerful Music <small lang="zh-CN">青风音乐</small></span>
      </RouterLink>
      <RouterLink v-slot="{ href, navigate }" custom :to="discoverHref">
        <a class="song-detail-section-link" :href="href" @click="returnToDiscover($event, navigate)">{{ t('音乐广场') }}</a>
      </RouterLink>
    </header>
    <div class="song-detail-body">
      <RouterLink v-slot="{ href, navigate }" custom :to="discoverHref">
        <a class="song-detail-back" :href="href" @click="returnToDiscover($event, navigate)"><NavIcon name="arrow" />{{ t('音乐广场') }}</a>
      </RouterLink>
      <p v-if="loading" class="song-detail-state" role="status">{{ t('正在加载歌曲…') }}</p>
      <p v-else-if="unavailable" class="song-detail-state" role="status">{{ t('这首歌已下架或不可用。') }}</p>
      <div v-else-if="loadError" class="song-detail-state">
        <p role="alert">{{ t('暂时无法加载这首歌，请重试。') }}</p>
        <button type="button" class="song-detail-action" @click="load">{{ t('重试') }}</button>
      </div>
      <article v-else-if="detail && track" aria-labelledby="discover-song-title">
        <div class="song-detail-hero">
          <img class="song-detail-cover" :src="track.cover" alt="" width="272" height="272">
          <div class="song-detail-identity">
            <h1 id="discover-song-title">{{ track.title }}</h1>
            <RouterLink class="song-detail-artist" :to="{name:'artist-home', params:{id:detail.release.artistId}}"><NavIcon name="account" />{{ detail.release.stageName }}</RouterLink>
            <dl v-if="genre || duration" class="song-detail-meta">
              <div v-if="genre"><dt>{{ t('曲风') }}</dt><dd>{{ t(genre) }}</dd></div>
              <div v-if="duration"><dt>{{ t('时长') }}</dt><dd>{{ duration }}</dd></div>
            </dl>
            <div class="song-detail-actions">
              <button type="button" class="song-detail-primary" :aria-pressed="playing" @click="play"><PlaybackIcon :playing="playing" />{{ t(playing ? '暂停' : '播放') }}</button>
              <button type="button" class="song-detail-action song-detail-like" :aria-pressed="detail.likes.liked" :disabled="busy || !communityAvailable" @click="toggleLike"><NavIcon name="heart" />{{ t('点赞') }} <span v-if="communityAvailable">{{ numberFormat.format(detail.likes.count) }}</span></button>
              <button type="button" class="song-detail-action" aria-controls="discover-song-comments" @click="jumpToComments"><NavIcon name="comment" />{{ t('评论') }} <span v-if="communityAvailable">{{ numberFormat.format(detail.commentCount) }}</span></button>
              <ArtistShareButton :key="shareUrl" song :url="shareUrl" :name="track.title" />
            </div>
          </div>
        </div>
        <p v-if="feedback && feedbackArea === 'hero'" class="song-detail-feedback" role="alert">{{ t(feedback) }}</p>

        <div class="song-detail-reading">
          <section aria-labelledby="discover-song-lyrics">
            <h2 id="discover-song-lyrics">{{ t('歌词') }}</h2>
            <p v-if="detail.lyrics" id="discover-song-words" class="song-detail-words">{{ shownLyrics }}</p>
            <p v-else class="song-detail-muted">{{ t('暂无歌词') }}</p>
            <button v-if="lyricLines.length > 3" type="button" class="song-detail-text-action" :aria-expanded="expanded" aria-controls="discover-song-words" @click="expanded = !expanded">{{ t(expanded ? '收起歌词' : '展开歌词') }} <span aria-hidden="true">{{ expanded ? '⌃' : '⌄' }}</span></button>
          </section>
          <section v-if="communityAvailable" class="song-detail-about" aria-labelledby="discover-song-about">
            <div class="song-detail-section-heading">
              <h2 id="discover-song-about">{{ t('简介') }}</h2>
              <button v-if="detail.canEditDescription && !editingDescription" type="button" class="song-detail-text-action" @click="editDescription">{{ t('编辑简介') }}</button>
            </div>
            <form v-if="editingDescription" class="song-detail-description-form" @submit.prevent="saveDescription">
              <textarea v-model="descriptionText" :aria-label="t('简介')" maxlength="500" rows="3" />
              <div><button type="button" class="song-detail-action" :disabled="busy" @click="editingDescription = false">{{ t('取消') }}</button><button type="submit" class="song-detail-primary" :disabled="busy">{{ t('保存') }}</button></div>
            </form>
            <p v-else-if="detail.description" class="song-detail-words">{{ detail.description }}</p>
            <p v-else class="song-detail-muted">{{ t('暂无歌曲简介。') }}</p>
            <p v-if="feedback && feedbackArea === 'description'" class="song-detail-feedback" role="alert">{{ t(feedback) }}</p>
          </section>
        </div>

        <section id="discover-song-comments" ref="commentSection" class="song-detail-comments" tabindex="-1" aria-labelledby="discover-song-comment-title">
          <div class="song-detail-section-heading">
            <h2 id="discover-song-comment-title">{{ t('评论') }}</h2>
            <span v-if="communityAvailable" class="song-detail-muted">{{ t('{count} 条评论', { count: numberFormat.format(detail.commentCount) }) }}</span>
          </div>
          <p v-if="!communityAvailable" class="song-detail-muted song-detail-comment-empty">{{ t('点赞和评论暂未开放。') }}</p>
          <template v-else>
            <form class="song-detail-comment-form" @submit.prevent="submitComment">
              <textarea v-model="commentText" rows="1" maxlength="400" :disabled="pending === 'comment'" :aria-label="t('写评论')" :placeholder="t('写下你的听后感…')" />
              <button type="submit" class="song-detail-primary" :disabled="busy || !commentText.trim()">{{ t('发表') }}</button>
            </form>
            <p v-if="feedback && feedbackArea === 'comments'" class="song-detail-feedback" role="alert">{{ t(feedback) }}</p>
            <p v-if="!detail.comments.items.length" class="song-detail-muted song-detail-comment-empty">{{ t('还没有评论。') }}</p>
            <ul v-else class="song-detail-comment-list">
              <li v-for="item in detail.comments.items" :key="item.id">
                <span class="song-detail-comment-avatar" aria-hidden="true">{{ Array.from(item.author || t('听众'))[0] }}</span>
                <div class="song-detail-comment-body">
                  <div class="song-detail-comment-byline"><strong>{{ item.author || t('听众') }}</strong><time :datetime="item.createdAt">{{ formatDate(item.createdAt) }}</time></div>
                  <p>{{ item.text }}</p>
                </div>
                <button v-if="item.removable" type="button" class="song-detail-text-action song-detail-delete" :disabled="busy" @click="deleteComment(item)">{{ t('删除') }}</button>
              </li>
            </ul>
            <button v-if="detail.comments.nextCursor" type="button" class="song-detail-text-action" :disabled="busy" @click="loadMore">{{ t(loadingMore ? '加载中…' : '加载更多评论') }}</button>
          </template>
        </section>
      </article>
    </div>
  </div>
</template>

<style src="../styles/discover-song.css" scoped></style>
