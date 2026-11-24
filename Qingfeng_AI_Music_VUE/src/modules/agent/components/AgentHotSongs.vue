<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { locale } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import { listDiscoverSongs } from '../../artist/api/artist'
import { publicDiscoverTrack } from '../../artist/model/discover-shelves'
import { usePreviewTracks } from '../../home/model/tracks'
import { loadHotSongs, sortByHeat, hotFailure, hotLoading, recordPlay } from '../../home/model/hot-songs'
import { recordPreviewPlay } from '../../home/model/published-recommendations'

const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
const player = usePlayer(), tracks = usePreviewTracks(), ranking = ref<string[]>([])
const songs = computed(() => [...tracks.value].sort((a, b) => ranking.value.indexOf(a.id) - ranking.value.indexOf(b.id)))
async function refresh() {
  await loadHotSongs(true)
  ranking.value = sortByHeat(tracks.value).map(track => track.id)
}

const releases = ref<ReturnType<typeof publicDiscoverTrack>[]>([])
const loadingReleases = ref(false), releaseError = ref(false), hasMore = ref(true)
const index = ref(0)
const cards = computed(() => songs.value[0] ? [songs.value[0], ...releases.value] : releases.value)
const featured = computed(() => cards.value[index.value])
const showingRelease = computed(() => index.value > 0 || !songs.value.length)
const firstListRank = computed(() => showingRelease.value ? 1 : 2)
const canBrowse = computed(() => !loadingReleases.value && releases.value.length > 0)
const canGoBack = computed(() => canBrowse.value && (index.value > 0 || (!hasMore.value && cards.value.length > 1)))
const canGoForward = computed(() => canBrowse.value && (hasMore.value || cards.value.length > 1))
let offset = 0, disposed = false

async function loadReleases() {
  if (loadingReleases.value || !hasMore.value) return false
  loadingReleases.value = true
  releaseError.value = false
  try {
    const unique = new Map(releases.value.map(track => [track.id, track]))
    const previousSize = unique.size
    let nextOffset = offset, more: boolean
    // Popularity can change between pages. Advance by raw rows even when a release appears twice.
    do {
      const page = await listDiscoverSongs('popular', nextOffset, '', '')
      if (disposed) return false
      for (const item of page.songs) unique.set(item.song.id, publicDiscoverTrack(item))
      nextOffset += page.songs.length
      more = page.hasMore && page.songs.length > 0
    } while (more && unique.size === previousSize)
    offset = nextOffset
    hasMore.value = more
    releases.value = [...unique.values()]
    return true
  } catch {
    if (!disposed) releaseError.value = true
    return false
  } finally {
    if (!disposed) loadingReleases.value = false
  }
}

async function move(direction: -1 | 1) {
  if (direction < 0 ? !canGoBack.value : !canGoForward.value) return
  if (direction > 0 && index.value === cards.value.length - 1 && hasMore.value) {
    if (!await loadReleases() || disposed) return
  }
  index.value = (index.value + direction + cards.value.length) % cards.value.length
}

function retryReleases() {
  if (releases.value.length && index.value === cards.value.length - 1) void move(1)
  else void loadReleases()
}

let touchOrigin: Touch | undefined
function startSwipe(event: TouchEvent) {
  touchOrigin = event.touches.length === 1 ? event.touches[0] : undefined
}
function endSwipe(event: TouchEvent) {
  const start = touchOrigin, end = event.changedTouches[0]
  touchOrigin = undefined
  if (!start || !end || event.touches.length) return
  const dx = end.clientX - start.clientX, dy = end.clientY - start.clientY
  if (Math.abs(dx) < 40 || Math.abs(dx) <= Math.abs(dy)) return
  event.preventDefault()
  void move(dx < 0 ? 1 : -1)
}

function active(track: PlayableTrack) {
  return player.current?.audio === track.audio && (player.playing || player.starting)
}
async function play(track: PlayableTrack, queue: PlayableTrack[]) {
  const wasPlaying = active(track)
  player.setQueue(queue)
  await player.toggle(track)
  if (!track.publication && !wasPlaying && player.current?.audio === track.audio && player.playing) recordPlay(track.id)
}
// Queue controls can start a public release as well as the cover's play button.
watch(() => player.playing && !player.starting ? player.current : null, track => {
  if (track?.publication) void recordPreviewPlay(track)
})
function duration(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`
}
onMounted(() => { void refresh(); void loadReleases() })
onBeforeUnmount(() => { disposed = true })
</script>

<template>
  <section class="agent-hot-songs" :aria-label="tr('热歌推荐榜','Popular songs')">
    <div class="hot-carousel" @keydown.left.exact.prevent="move(-1)" @keydown.right.exact.prevent="move(1)">
      <header>
        <h2><span aria-hidden="true">🔥</span>{{ tr('热歌推荐','Recommended songs') }}</h2>
        <div class="hot-carousel-controls" role="group" :aria-busy="loadingReleases" :aria-label="tr('切换广场歌曲','Browse Discover songs')">
          <button type="button" :disabled="!releases.length" :aria-disabled="!canGoBack" :aria-label="tr('上一首推荐','Previous recommendation')" @click="move(-1)"><span aria-hidden="true">‹</span></button>
          <button type="button" :disabled="!releases.length" :aria-disabled="!canGoForward" :aria-label="tr('下一首推荐','Next recommendation')" @click="move(1)"><span aria-hidden="true">›</span></button>
        </div>
      </header>
      <div v-if="featured" class="hot-featured" @touchstart.passive="startSwipe" @touchcancel="touchOrigin = undefined" @touchend="endSwipe">
        <img :src="featured.cover" alt=""/><div class="hot-featured-shade"/>
        <span class="hot-featured-label">{{ showingRelease ? tr('广场热门','Popular on Discover') : tr('热歌 TOP 1','TOP TRACK') }}</span>
        <div class="hot-featured-copy" aria-live="polite" aria-atomic="true">
          <h3 :title="featured.title">{{ featured.title }}</h3>
          <small v-if="showingRelease">{{ featured.publication?.stageName }}</small>
        </div>
        <button class="hot-song-play" type="button" :aria-label="(active(featured) ? tr('暂停','Pause ') : tr('试听','Play ')) + featured.title"
                :aria-pressed="active(featured)" @click="play(featured, showingRelease ? releases : songs)"><span><PlaybackIcon :playing="active(featured)"/></span></button>
      </div>
    </div>
    <ol :start="firstListRank">
      <li v-for="(track, i) in songs.slice(firstListRank - 1)" :key="track.id" :class="{'is-playing':active(track)}">
        <span class="hot-rank">{{ String(i + firstListRank).padStart(2,'0') }}</span>
        <button class="hot-song-play" type="button" :aria-label="(active(track) ? tr('暂停','Pause ') : tr('试听','Play ')) + track.title" :aria-pressed="active(track)" @click="play(track, songs)">
          <img :src="track.cover" alt="" loading="lazy"/><span><strong>{{ track.title }}</strong><small>{{ track.genre || track.category }} · {{ duration(track.durationSeconds) }}</small></span><i><PlaybackIcon :playing="active(track)"/></i>
        </button>
      </li>
    </ol>
    <p v-if="player.error && (player.current?.publication || songs.some(track => track.audio === player.current?.audio))" class="hot-chart-error" role="status">{{ player.error }}</p>
    <p v-if="releaseError" class="hot-chart-error" role="status">{{ tr('广场歌曲加载失败。','Could not load Discover songs.') }} <button type="button" @click="retryReleases">{{ tr('重试','Retry') }}</button></p>
    <p v-else-if="!hasMore && !releases.length" class="hot-chart-note" role="status">{{ tr('广场暂无公开歌曲','No published Discover songs yet') }}</p>
    <p v-if="hotFailure" class="hot-chart-error" role="status">{{ tr('热度暂未更新，先为你展示精选试听。','Showing curated songs while rankings are unavailable.') }} <button type="button" :disabled="hotLoading" @click="refresh">{{ tr('重试','Retry') }}</button></p>
    <p v-else-if="hotLoading" class="hot-chart-note" role="status">{{ tr('正在更新热度…','Updating ranking…') }}</p>
  </section>
</template>
