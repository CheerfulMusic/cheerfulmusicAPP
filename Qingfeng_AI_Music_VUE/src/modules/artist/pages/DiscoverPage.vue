<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { formatDate, t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { listDiscoverSongs, publicTrack, type DiscoverSong } from '../api/artist'

const player = usePlayer()
const featured = ref(false)
const songs = ref<DiscoverSong[]>([])
const hasMore = ref(false)
const loading = ref(true)
const failed = ref(false)
const rows = computed(() => songs.value.map(item => ({ ...item, track: publicTrack(item.artistId, item.song) })))
let request = 0
let offset = 0

async function load(reset = false) {
  if (reset) {
    songs.value = []
    hasMore.value = false
    offset = 0
  }
  const attempt = ++request
  loading.value = true
  failed.value = false
  try {
    const page = await listDiscoverSongs(featured.value, offset)
    if (attempt !== request) return
    const seen = new Set(songs.value.map(item => item.song.id))
    songs.value.push(...page.songs.filter(item => !seen.has(item.song.id)))
    offset += page.songs.length
    hasMore.value = page.hasMore
  } catch {
    if (attempt === request) failed.value = true
  } finally {
    if (attempt === request) loading.value = false
  }
}

function select(value: boolean) {
  if (featured.value === value) return
  featured.value = value
  void load(true)
}
function playing(track: PlayableTrack) {
  return player.current?.audio === track.audio && (player.playing || player.starting)
}
function play(track: PlayableTrack) {
  player.setQueue(rows.value.map(row => row.track), false)
  void player.toggle(track)
}

onMounted(() => void load(true))
onBeforeUnmount(() => { ++request })
</script>

<template>
  <section class="discover" aria-labelledby="discover-title">
    <header class="discover-heading">
      <h1 id="discover-title">{{ t('发现') }}</h1>
      <p>{{ t('听听其他创作者发布的歌曲。') }}</p>
    </header>
    <div class="discover-filters" role="group" :aria-label="t('筛选发现歌曲')">
      <button type="button" :aria-pressed="!featured" @click="select(false)">{{ t('最新发布') }}</button>
      <button type="button" :aria-pressed="featured" @click="select(true)">{{ t('精选') }}</button>
    </div>

    <ul v-if="rows.length" class="discover-songs">
      <li v-for="row in rows" :key="row.song.id" class="discover-song">
        <img class="discover-artwork" :src="row.track.cover" alt="" loading="lazy">
        <div class="discover-song-details">
          <h2>{{ row.song.title }}</h2>
          <RouterLink :to="{ name: 'artist-home', params: { id: row.artistId } }">{{ row.stageName }}</RouterLink>
        </div>
        <time :datetime="row.publishedAt">{{ formatDate(row.publishedAt) }}</time>
        <button class="discover-play" type="button" :aria-pressed="playing(row.track)"
          :aria-label="t(playing(row.track) ? '暂停 {title}' : '试听 {title}', { title: row.song.title })" @click="play(row.track)">
          <PlaybackIcon :playing="playing(row.track)" />
        </button>
      </li>
    </ul>
    <p v-if="loading" class="discover-message" role="status">{{ t('正在加载歌曲…') }}</p>
    <div v-else-if="failed" class="discover-message" role="alert">
      <p>{{ t('暂时无法加载发现歌曲，请重试。') }}</p>
      <button type="button" class="button secondary" @click="load()">{{ t('重试') }}</button>
    </div>
    <div v-else-if="!rows.length" class="discover-message">
      <p>{{ t(featured ? '还没有精选歌曲。' : '还没有歌曲发布到发现。') }}</p>
      <button v-if="featured" type="button" class="button secondary" @click="select(false)">{{ t('查看最新发布') }}</button>
      <RouterLink v-else to="/works" class="button secondary">{{ t('我的音乐') }}</RouterLink>
    </div>
    <div v-else-if="hasMore" class="discover-more">
      <button type="button" class="button secondary" @click="load()">{{ t('加载更多') }}</button>
    </div>
  </section>
</template>

<style scoped>
.discover { width: min(100%, 1000px); min-height: 56vh; margin-inline: auto; padding: 14px 0 48px; }
.discover-heading { padding: 24px 0 28px; }
.discover-heading h1 { margin: 0; font-family: inherit; font-size: clamp(2.2rem, 6vw, 4.4rem); font-weight: 800; line-height: 1.05; }
.discover-heading p { margin: 12px 0 0; color: var(--muted); }
.discover-filters { display: flex; gap: 26px; margin-bottom: 8px; border-bottom: 1px solid var(--line); }
.discover-filters button { padding: 9px 3px 12px; border: 0; border-bottom: 2px solid transparent; background: none; color: var(--muted); font-weight: 650; }
.discover-filters button[aria-pressed="true"] { border-color: var(--accent); color: var(--accent-text); }
.discover-songs { margin: 0; padding: 0; list-style: none; }
.discover-song { display: grid; grid-template-columns: 60px minmax(0, 1fr) auto 44px; align-items: center; gap: clamp(12px, 3vw, 28px); padding: 12px 3px; border-bottom: 1px solid var(--line); }
.discover-artwork { width: 60px; height: 60px; border-radius: 9px; object-fit: cover; background: var(--accent-soft); }
.discover-song h2 { margin: 0 0 2px; font-family: inherit; font-size: 1.02rem; font-weight: 700; overflow-wrap: anywhere; }
.discover-song a, .discover-song time { color: var(--muted); font-size: .82rem; overflow-wrap: anywhere; }
.discover-song a:hover { color: var(--accent-text); text-decoration: underline; }
.discover-play { display: grid; place-items: center; width: 44px; height: 44px; border: 0; border-radius: 50%; background: var(--accent-soft); color: var(--accent-text); }
.discover-play:hover, .discover-play[aria-pressed="true"] { background: var(--accent-text); color: white; }
.discover-play svg { width: 17px; height: 17px; }
.discover-message { padding: 28px 0; color: var(--muted); }
.discover-message p { margin: 0 0 14px; }
.discover-more { margin-top: 22px; }
@media (max-width: 560px) {
  .discover-heading { padding-top: 10px; }
  .discover-song { grid-template-columns: 48px minmax(0, 1fr) 44px; }
  .discover-artwork { width: 48px; height: 48px; }
  .discover-song time { display: none; }
}
</style>
