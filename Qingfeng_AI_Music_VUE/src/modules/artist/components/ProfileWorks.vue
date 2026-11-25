<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatDate, t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { coverUrl } from '../../works/api/covers'
import { audioUrl } from '../../works/api/detail'
import { listWorks, type LibraryItem } from '../../works/api/works'
import { librarySongTitle } from '../../works/model/song-title'
import { ownerTrack, type ArtistRelease } from '../api/artist'
import { discoverReleaseCover } from '../model/discover-shelves'

const props = defineProps<{ songs: ArtistRelease[]; busy: boolean; published: boolean }>()
const emit = defineEmits<{ publish: [song: ArtistRelease]; remove: [song: ArtistRelease]; withdraw: [song: ArtistRelease]; 'library-count': [count: number | null] }>()
const player = usePlayer()
const tab = ref<'library' | 'published'>('library')
const allPublishedVisible = ref(false)
const works = ref<LibraryItem[]>([])
const count = ref<number | null>(null)
const loading = ref(true)
const error = ref(false)
let request = 0

const PAGE_SIZE = 20
const PREVIEW_SIZE = 5
const PUBLISHED_PREVIEW_SIZE = 15
interface WorkRow {
  id: string
  workId: string
  title: string
  cover: string
  track?: PlayableTrack
  updatedAt?: string
  label?: string
  release?: ArtistRelease
}

const libraryRows = computed<WorkRow[]>(() => works.value.map(item => {
  const { id, updatedAt } = item.work
  const songTitle = librarySongTitle(item)
  const release = props.songs.find(song => song.workId === id)
  const originalCover = coverUrl(id, item.coverId)
  const cover = props.published && release?.onDiscover && release.assetId === item.audioId
    ? discoverReleaseCover({ id: release.id, hasCover: !!item.coverId }, originalCover) : originalCover
  const track = item.audioId
    ? { id: item.audioId, title: songTitle, cover, audio: audioUrl(id, item.audioId), provenance: item.audioOrigin ?? undefined }
    : undefined
  return { id, workId: id, title: songTitle, cover, track, updatedAt, release,
    label: publicationLabel(release) }
}))
const publishedRows = computed<WorkRow[]>(() => props.songs.map(song => {
  const track = ownerTrack(song)
  if (props.published && song.onDiscover) {
    track.cover = discoverReleaseCover({ id: song.id, hasCover: !!song.coverId }, track.cover)
  }
  return { id: song.id, workId: song.workId, title: song.title, cover: track.cover, track, release: song,
    label: publicationLabel(song) }
}))
const rows = computed(() => tab.value === 'library' ? libraryRows.value : publishedRows.value)
const visibleRows = computed(() => tab.value === 'published' && !allPublishedVisible.value
  ? rows.value.slice(0, PUBLISHED_PREVIEW_SIZE) : rows.value)

function publicationLabel(song?: ArtistRelease) {
  if (!song) return '仅自己可见'
  if (!props.published) return '主页未公开'
  return song.onDiscover ? '已发布到广场' : '已在主页展示'
}

async function load() {
  const attempt = ++request
  loading.value = true
  error.value = false
  try {
    const completed = new Map<string, LibraryItem>()
    let total: number | null = null
    for (let page = 0; completed.size < PREVIEW_SIZE; page++) {
      const items = await listWorks(page)
      if (attempt !== request) return
      for (const item of items) {
        if (item.status === 'COMPLETED') completed.set(item.work.id, item)
        if (completed.size === PREVIEW_SIZE) break
      }
      if (items.length < PAGE_SIZE) {
        if (page === 0) total = items.length
        break
      }
    }
    works.value = [...completed.values()]
    count.value = total
    emit('library-count', count.value)
  } catch {
    if (attempt !== request) return
    error.value = true
  } finally {
    if (attempt === request) loading.value = false
  }
}
function refreshIdentity() {
  works.value = []
  count.value = null
  tab.value = 'library'
  allPublishedVisible.value = false
  emit('library-count', null)
  void load()
}
function playing(track: PlayableTrack) {
  return player.current?.audio === track.audio && (player.playing || player.starting)
}
function play(track: PlayableTrack) {
  player.setQueue(rows.value.flatMap(row => row.track ? [row.track] : []), false)
  void player.toggle(track)
}
onMounted(() => {
  window.addEventListener('qingfeng:identity-changed', refreshIdentity)
  void load()
})
onBeforeUnmount(() => {
  ++request
  window.removeEventListener('qingfeng:identity-changed', refreshIdentity)
})
</script>

<template>
  <section class="profile-works" :aria-label="t('作品')">
    <header class="profile-works-header">
      <div class="profile-works-switch" role="group" :aria-label="t('筛选作品')">
        <button type="button" :aria-pressed="tab === 'library'" @click="tab = 'library'">
          <NavIcon name="music" />{{ t('我的作品') }}<span v-if="count !== null">({{ count }})</span>
        </button>
        <button type="button" :aria-pressed="tab === 'published'" @click="tab = 'published'">
          <NavIcon name="account" />{{ t('主页作品') }}<span>({{ songs.length }})</span>
        </button>
      </div>
      <div class="profile-works-controls">
        <button v-if="tab === 'published' && songs.length > PUBLISHED_PREVIEW_SIZE" type="button" class="text-button"
          aria-controls="profile-works-list" :aria-expanded="allPublishedVisible" @click="allPublishedVisible = !allPublishedVisible">
          {{ t(allPublishedVisible ? '收起' : '更多') }}
        </button>
        <RouterLink to="/works" class="profile-works-all">{{ t('全部作品') }}</RouterLink>
      </div>
    </header>

    <p v-if="tab === 'library' && loading" class="profile-works-message" role="status">{{ t('正在读取作品…') }}</p>
    <div v-else-if="tab === 'library' && error" class="profile-works-message" role="alert">
      {{ t('读取作品失败') }}
      <button type="button" class="text-button" @click="load">{{ t('重试') }}</button>
    </div>
    <div v-else-if="!rows.length" class="profile-works-message">
      <p>{{ t(tab === 'published' ? '打开一首已完成的歌曲，选择「发布到广场」。' : '暂无已完成作品') }}</p>
      <button v-if="tab === 'published'" type="button" class="text-button" @click="tab = 'library'">{{ t('我的作品') }}</button>
      <RouterLink v-else to="/create" class="text-button">{{ t('开始创作') }}</RouterLink>
    </div>
    <ul v-else id="profile-works-list" class="profile-works-list">
      <li v-for="row in visibleRows" :key="row.id" class="profile-work-row">
        <button v-if="row.track" type="button" class="profile-work-cover"
          :aria-label="t(playing(row.track) ? '暂停 {title}' : '试听 {title}', { title: row.title })" @click="play(row.track)">
          <img :src="row.cover" alt="" loading="lazy">
          <span><PlaybackIcon :playing="playing(row.track)" /></span>
        </button>
        <div v-else class="profile-work-cover"><img :src="row.cover" alt="" loading="lazy"></div>
        <RouterLink :to="{ name: 'work-detail', params: { id: row.workId } }" class="profile-work-link"
          :class="{ 'is-release': row.release }" :title="row.title">
          <span class="profile-work-copy">
            <strong>{{ row.title }}</strong>
            <time v-if="row.updatedAt" :datetime="row.updatedAt">{{ formatDate(row.updatedAt) }}</time>
          </span>
          <span class="profile-work-label"><span v-if="row.label" :title="t(row.label)">{{ t(row.label) }}</span></span>
          <NavIcon name="arrow" />
        </RouterLink>
        <div v-if="row.release" class="profile-work-actions">
          <button v-if="row.release.onDiscover" type="button" class="profile-work-remove text-button" :disabled="busy" @click="emit('withdraw', row.release)">
            {{ t('从广场撤回') }}
          </button>
          <button v-else-if="published" type="button" class="text-button" :disabled="busy" @click="emit('publish', row.release)">
            {{ t('发布到广场') }}
          </button>
          <button type="button" class="profile-work-remove text-button" :disabled="busy" @click="emit('remove', row.release)">
            {{ t('移出主页') }}
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.profile-works {
  min-width: 0;
  border: 1px solid var(--profile-line, var(--line));
  border-radius: 16px;
  background: var(--profile-card, rgba(255, 255, 255, .73));
  box-shadow: 0 2px 6px #792d4a07;
}
.profile-works-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  column-gap: 12px;
  border-bottom: 1px solid var(--profile-line, var(--line));
  padding-inline: clamp(12px, 2.5vw, 22px);
}
.profile-works-switch {
  display: flex;
  align-items: stretch;
  gap: clamp(8px, 3vw, 29px);
}
.profile-works-switch button {
  display: flex;
  align-items: center;
  gap: 5px;
  min-height: 43px;
  margin-bottom: -1px;
  padding: 0 4px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--muted);
  font-size: .87rem;
  font-weight: 650;
  white-space: nowrap;
}
.profile-works-switch button[aria-pressed="true"] {
  color: var(--accent-text);
  border-bottom-color: var(--accent);
}
.profile-works-switch svg { width: 15px; height: 15px; }
.profile-works-controls { display: flex; align-items: center; gap: 12px; }
.profile-works-all {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  margin-block: 5px;
  padding: 3px 11px;
  border-radius: 30px;
  background: var(--accent-soft);
  color: var(--accent-text);
  font-size: .77rem;
  font-weight: 650;
}
.profile-works-message {
  min-height: 64px;
  margin: 0;
  padding: 18px clamp(12px, 2.5vw, 22px);
  color: var(--muted);
}
.profile-works-message p { margin: 0 0 6px; }
.profile-works-list {
  list-style: none;
  margin: 0;
  padding: 0 clamp(12px, 2.5vw, 22px);
}
.profile-work-row {
  display: flex;
  align-items: center;
  gap: clamp(7px, 1.6vw, 15px);
  min-height: 55px;
  padding-block: 6px;
}
.profile-work-row + .profile-work-row { border-top: 1px solid var(--profile-line, var(--line)); }
.profile-work-cover {
  position: relative;
  flex: none;
  width: 64px;
  height: 42px;
  overflow: hidden;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: var(--accent-soft);
}
.profile-work-cover img { display: block; width: 100%; height: 100%; object-fit: cover; }
.profile-work-cover span {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #fff;
  background: #28152320;
}
.profile-work-cover span :deep(svg) {
  width: 24px;
  height: 24px;
  padding: 5px;
  border-radius: 50%;
  background: #291d2b91;
}
.profile-work-cover:hover span :deep(svg) { background: var(--accent-text); }
.profile-work-link {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) minmax(62px, 2.8fr) 28px;
  align-items: center;
  gap: 12px;
  min-width: 0;
  min-height: 40px;
  color: var(--ink);
  text-align: left;
}
.profile-work-link:hover { color: var(--accent-text); }
.profile-work-link > svg { width: 15px; height: 15px; justify-self: center; }
.profile-work-link.is-release { grid-template-columns: minmax(0, 1fr) auto 18px; }
.profile-work-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}
.profile-work-copy strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: .84rem;
  font-weight: 650;
}
.profile-work-copy time {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: .72rem;
}
.profile-work-label { min-width: 0; }
.profile-work-label > span {
  display: block;
  width: fit-content;
  max-width: min(100%, 12rem);
  overflow: hidden;
  padding: 3px 9px;
  border-radius: 24px;
  background: var(--accent-soft);
  color: var(--accent-text);
  font-size: .72rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.profile-work-actions { display: flex; flex-wrap: wrap; justify-content: end; gap: 2px 12px; font-size: .76rem; }
.profile-work-actions .text-button { font-size: inherit; }
@media (max-width: 580px) {
  .profile-work-cover { width: 52px; }
  .profile-work-link { grid-template-columns: minmax(0, 1fr) minmax(0, .65fr) 16px; gap: 7px; }
  .profile-work-link.is-release { grid-template-columns: minmax(0, 1fr) 16px; row-gap: 2px; }
  .profile-work-link.is-release .profile-work-label { grid-column: 1; grid-row: 2; }
  .profile-work-link.is-release > svg { grid-column: 2; grid-row: 1 / span 2; }
  .profile-work-label > span { padding-inline: 5px; }
  .profile-work-row { flex-wrap: wrap; }
  .profile-work-link { flex-basis: calc(100% - 65px); }
  .profile-work-actions { margin-inline-start: 54px; justify-content: start; }
}
</style>
