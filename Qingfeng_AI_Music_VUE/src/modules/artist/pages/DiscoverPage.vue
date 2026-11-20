<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import BrandLogo from '../../../shared/ui/BrandLogo.vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import ArtistAvatar from '../components/ArtistAvatar.vue'
import DiscoverSongCard from '../components/DiscoverSongCard.vue'
import DiscoverShelf from '../components/DiscoverShelf.vue'
import { discoverGenres, shelfCover, type DiscoverShelfTrack, type PublishedShelfTrack } from '../model/discover-shelves'
import { vocalPerformanceRows } from '../model/vocal-performances'
import PlaylistDetails from '../../home/components/PlaylistDetails.vue'
import { usePreviewPlaylists, type PreviewPlaylist } from '../../home/model/playlists'
import { usePreviewTracks, type PreviewTrack } from '../../home/model/tracks'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import {
  getDiscoverShelves, getPublicArtist, listDiscoverSongs, publicAvatarUrl, publicTrack, recordDiscoverPlay,
  type DiscoverArtist, type DiscoverShelves, type DiscoverSong,
} from '../api/artist'
import coast from '../../../assets/discover/editorial/coast.webp'
import cactus from '../../../assets/discover/carousel-cactus.webp'
import mistyGarden from '../../../assets/discover/carousel-misty-garden.webp'
import airportGuitar from '../../../assets/discover/carousel-airport-guitar.webp'
import seaStacks from '../../../assets/discover/carousel-sea-stacks.webp'
import countryside from '../../../assets/discover/carousel-countryside.webp'
import discoCover from '../../../assets/discover/playlists/disco.webp'
import christmasCover from '../../../assets/discover/playlists/christmas.webp'

const tabs = [
  { id: 'latest', label: '最新发布' },
  { id: 'featured', label: '精选' },
  { id: 'popular', label: '热门' },
] as const
const genres = [
  { id: '', label: '全部风格' }, { id: 'pop', label: '流行' }, { id: 'folk', label: '民谣' },
  { id: 'guofeng', label: '古风' }, { id: 'rock', label: '摇滚' }, { id: 'electronic', label: '电子' },
  { id: 'instrumental', label: '纯音乐' }, { id: 'global-pop', label: '欧美流行' },
  { id: 'jazz-soul', label: '爵士与灵魂乐' }, { id: 'other', label: '其他风格' },
]
const latestShelfSize = 32
const heroRotationMs = 30_000
const streetlightSongId = '0f831f4b-1f3f-4d14-b6cf-b33534863622'
const replacedHeroSongId = 'acfaa9d5-ce74-477f-9db8-d3700311a26c'
const christmasArtistId = '308036ef-8bef-486f-88cc-8266e5797768'
const christmasSongId = 'd4b1ec5a-e68f-43e6-8624-226981ecef5c'
const discoverPreviewPlaylistIds = new Set(['mandopop', 'electronic', 'global-pop'])
const player = usePlayer()
const previewPlaylists = usePreviewPlaylists()
const previewTracks = usePreviewTracks()
const christmasTracks = ref<PreviewTrack[]>([])
const tab = ref<(typeof tabs)[number]['id']>('latest')
const genre = ref('')
const search = ref('')
const query = ref('')
const songs = ref<DiscoverSong[]>([])
const latestSongs = ref<DiscoverSong[]>([])
const shelves = ref<DiscoverShelves>()
const browseOpen = ref(false)
const latestFailed = ref(false)
const featuredFailed = ref(false)
const shelvesFailed = ref(false)
const expanded = ref<'vocal' | 'today'>()
const featuredSongs = ref<DiscoverSong[]>([])
const popularSongs = ref<DiscoverSong[]>([])
const supplementalHeroSong = ref<DiscoverSong>()
const editorialLoading = ref(true)
const heroIndex = ref(0)
const heroPaused = ref(false)
const heroHovered = ref(false)
const heroFocused = ref(false)
const pageVisible = ref(true)
const reducedMotion = ref(false)
const artists = ref<DiscoverArtist[]>()
const hasMore = ref(false)
const loading = ref(true)
const failed = ref(false)
const showAllArtists = ref(false)
const selectedPlaylist = ref('')
const durations = ref<Record<string, number>>({})
const played = new Set<string>()
let request = 0
let offset = 0
let disposed = false
let editorialRequest = 0
let shelvesRequest = 0
let heroTimer: ReturnType<typeof setInterval> | undefined
let motionQuery: MediaQueryList | undefined

const count = computed(() => new Intl.NumberFormat(locale.value))
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
function withTracks(items: DiscoverSong[], shelf?: Parameters<typeof shelfCover>[0]): PublishedShelfTrack[] {
  return items.map((item, index) => {
    const track = publicTrack(item.artistId, item.song)
    if (shelf || !item.song.hasCover) track.cover = shelfCover(shelf ?? 'latest', index)
    return { ...item, track }
  })
}
const rows = computed(() => withTracks(songs.value))
const latestRows = computed(() => withTracks(latestSongs.value, 'latest'))
const latestRecommendations = computed(() => {
  if (editorialLoading.value || featuredFailed.value || featuredSongs.value.length) return []

  const creators = new Set<string>()
  return latestSongs.value.filter(item => {
    if (creators.has(item.artistId)) return false
    creators.add(item.artistId)
    return true
  })
})
const featuredRows = computed(() => withTracks(
  (latestRecommendations.value.length ? latestRecommendations.value : featuredSongs.value).slice(0, 7), 'featured',
))
const dailySongs = computed(() => shelves.value?.today ?? [])
const cumulativeHotSongs = computed(() => dailySongs.value.length || editorialLoading.value
  ? [] : popularSongs.value.filter(item => (item.plays ?? 0) > 0).slice(0, 10))
const todayRows = computed(() => withTracks(
  cumulativeHotSongs.value.length ? cumulativeHotSongs.value : dailySongs.value, 'today',
))
const vocalRows = computed(() => [...vocalPerformanceRows, ...withTracks(shelves.value?.vocal ?? [], 'vocal')])
const upcomingVocal = { title: '唯一的航线', cover: seaStacks }
const ranking = computed(() => todayRows.value.slice(0, 5))
const genreShelves = computed(() => discoverGenres.map(section => {
  const publicRows = withTracks(shelves.value?.genres[section.id] ?? [], section.id)
  const publishedIds = new Set(publicRows.map(row => row.song.id))
  const examples = previewTracks.value
    .filter(track => section.samples.some(genre => track.filters.includes(genre))
      && (!track.publication || !publishedIds.has(track.publication.song.id)))
    .map((track, index): DiscoverShelfTrack => ({
      track: { ...track, cover: shelfCover(section.id, publicRows.length + index) },
      stageName: track.publication?.stageName ?? '',
      durationSeconds: track.durationSeconds,
      song: track.publication?.song,
      artistId: track.publication?.artistId,
    }))
  const tracks = [...publicRows, ...examples]
  const wide = section.hasWideRow
    ? [...tracks.slice(7), ...tracks.slice(0, 7)].slice(0, 4).map((row, index) => ({
        ...row, track: { ...row.track, cover: shelfCover(`${section.id}-wide`, index) },
      }))
    : []
  return { ...section, square: tracks.slice(0, 7), wide }
}))
const heroRows = computed(() => {
  const streetlight = popularSongs.value.find(item => item.song.id === streetlightSongId) ?? supplementalHeroSong.value
  const slides = popularSongs.value.filter(item => item.song.id !== replacedHeroSongId && item.song.id !== streetlightSongId)
  if (streetlight) slides.splice(1, 0, streetlight)
  return withTracks(slides.slice(0, 5))
})
const heroSlideCount = computed(() => heroRows.value.length + 1)
const hero = computed(() => heroIndex.value === 0 ? undefined : heroRows.value[heroIndex.value - 1])
const heroBackgrounds = [
  { src: seaStacks, position: '58% center' },
  { src: cactus, position: '65% top' },
  { src: airportGuitar, position: '62% 32%' },
  { src: countryside, position: '15% center' },
  { src: mistyGarden, position: 'center 60%' },
]
const heroBackground = computed(() => hero.value && heroBackgrounds[heroIndex.value - 1])
const adjacentHero = (step: number, fallback: string) => computed<{ src: string; position?: string }>(() => {
  if (heroSlideCount.value < 2) return { src: fallback }
  const index = (heroIndex.value + step + heroSlideCount.value) % heroSlideCount.value
  return heroBackgrounds[index - 1] ?? { src: coast }
})
const heroBefore = adjacentHero(-1, mistyGarden)
const heroAfter = adjacentHero(1, seaStacks)
const heroTitleLength = computed(() => Array.from(hero.value?.song.title ?? '').length)
const visibleArtists = computed(() => {
  const found = artists.value ?? Array.from(new Map(latestSongs.value.map(song => [song.artistId, {
    artistId: song.artistId, stageName: song.stageName, avatarKey: null,
  }])).values())
  return showAllArtists.value ? found : found.slice(0, 3)
})
const playlists = computed<PreviewPlaylist[]>(() => [
  ...previewPlaylists.value.filter(playlist => discoverPreviewPlaylistIds.has(playlist.id)),
  {
    id: 'disco', title: t('Disco'), subtitle: 'DISCO', cover: discoCover,
    tracks: previewTracks.value.filter(track => track.filters.includes('disco')),
  },
  {
    id: 'christmas', title: t('圣诞'), subtitle: 'CHRISTMAS', cover: christmasCover,
    tracks: christmasTracks.value,
  },
])
const visiblePlaylists = computed(() => {
  const keyword = query.value.toLocaleLowerCase()
  return playlists.value.filter(playlist => [playlist.title, playlist.subtitle]
    .some(label => label.toLocaleLowerCase().includes(keyword)))
})
const playlistDetails = computed(() => playlists.value.find(playlist => playlist.id === selectedPlaylist.value))

async function loadChristmasPlaylist() {
  try {
    const artist = await getPublicArtist(christmasArtistId)
    const song = artist.songs.find(item => item.id === christmasSongId)
    if (disposed || !song) return
    christmasTracks.value = [{
      ...publicTrack(christmasArtistId, song), authoredTitle: true,
      recipient: '', subtitle: artist.stageName, category: t('圣诞'), filters: ['christmas'],
      tags: [t('圣诞')], style: 'Christmas', occasion: '',
      durationSeconds: 0, description: '', quote: '',
      publication: { artistId: christmasArtistId, stageName: artist.stageName, song },
    }]
  } catch {
    // Keep the playlist available if its public release cannot be loaded.
  }
}

async function loadShelves() {
  const attempt = ++shelvesRequest
  try {
    const value = await getDiscoverShelves()
    if (!disposed && attempt === shelvesRequest) {
      shelves.value = value
      shelvesFailed.value = false
    }
  } catch {
    if (!disposed && attempt === shelvesRequest) shelvesFailed.value = true
  }
}

async function loadEditorial() {
  const attempt = ++editorialRequest
  editorialLoading.value = true
  latestFailed.value = false
  featuredFailed.value = false
  const active = () => !disposed && attempt === editorialRequest

  await Promise.all([
    listDiscoverSongs('latest', 0, '', '').then(async first => {
      if (!active()) return
      latestSongs.value = first.songs.slice(0, latestShelfSize)
      if (!browseOpen.value) artists.value = first.artists
      if (!first.hasMore || first.songs.length >= latestShelfSize || !first.songs.length) return

      const next = await listDiscoverSongs('latest', first.songs.length, '', '')
      if (!active()) return
      latestSongs.value = [...new Map([...first.songs, ...next.songs].map(item => [item.song.id, item])).values()]
        .slice(0, latestShelfSize)
    }).catch(() => { if (active()) latestFailed.value = true }),
    listDiscoverSongs('featured', 0, '', '').then(page => {
      if (active()) featuredSongs.value = page.songs
    }).catch(() => { if (active()) featuredFailed.value = true }),
    listDiscoverSongs('popular', 0, '', '').then(async page => {
      if (!active()) return
      popularSongs.value = page.songs
      supplementalHeroSong.value = undefined
      heroIndex.value = 0
      if (!page.songs.some(item => item.song.id === streetlightSongId)) {
        const result = await listDiscoverSongs('latest', 0, '', 'Streetlight')
        if (active()) supplementalHeroSong.value = result.songs.find(item => item.song.id === streetlightSongId)
      }
    }).catch(() => { /* Keep the previous carousel if its refresh fails. */ }),
    loadShelves(),
  ])
  if (active()) editorialLoading.value = false
}

function changeHero(direction: number) {
  heroIndex.value = (heroIndex.value + direction + heroSlideCount.value) % heroSlideCount.value
}
function restartHeroRotation() {
  clearInterval(heroTimer)
  heroTimer = undefined
  if (disposed || heroSlideCount.value < 2 || heroPaused.value || heroHovered.value || heroFocused.value
    || !pageVisible.value || reducedMotion.value || player.playing || player.starting) return
  heroTimer = setInterval(() => changeHero(1), heroRotationMs)
}
function heroFocusOut(event: FocusEvent) {
  heroFocused.value = event.relatedTarget instanceof Node && (event.currentTarget as HTMLElement).contains(event.relatedTarget)
}
function visibilityChanged() { pageVisible.value = document.visibilityState !== 'hidden' }
function motionChanged() { reducedMotion.value = motionQuery?.matches ?? false }
watch([heroSlideCount, heroIndex, heroPaused, heroHovered, heroFocused, pageVisible, reducedMotion,
  () => player.playing, () => player.starting], restartHeroRotation)

function initializeHeroRotation() {
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  motionChanged()
  visibilityChanged()
  motionQuery.addEventListener('change', motionChanged)
  document.addEventListener('visibilitychange', visibilityChanged)
  restartHeroRotation()
}
// Warm only the next slide; the introduction remains the first, eagerly loaded image.
watch([heroIndex, heroSlideCount], () => {
  if (disposed || heroSlideCount.value < 2 || typeof Image === 'undefined') return
  const nextIndex = (heroIndex.value + 1) % heroSlideCount.value
  const nextImage = new Image()
  nextImage.src = heroBackgrounds[nextIndex - 1]?.src ?? coast
})
function genreLabel(genre?: string) {
  return genre && genre !== 'other' ? t(genres.find(item => item.id === genre)?.label ?? '') : ''
}

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
    const page = await listDiscoverSongs(tab.value, offset, genre.value, query.value)
    if (attempt !== request) return
    const seen = new Set(songs.value.map(item => item.song.id))
    songs.value.push(...page.songs.filter(item => !seen.has(item.song.id)))
    if (offset === 0) artists.value = page.artists
    offset += page.songs.length
    hasMore.value = page.hasMore
  } catch {
    if (attempt === request) failed.value = true
  } finally {
    if (attempt === request) loading.value = false
  }
}

function select(value: typeof tab.value) {
  if (tab.value === value) return
  tab.value = value
  void load(true)
}
function openBrowse(value: typeof tab.value, filter = '', keyword = '') {
  tab.value = value
  genre.value = filter
  query.value = keyword
  search.value = keyword
  browseOpen.value = true
  void load(true)
  requestAnimationFrame(() => document.getElementById('discover-tracks')?.scrollIntoView())
}
function returnToShelves() {
  ++request
  browseOpen.value = false
  query.value = ''
  search.value = ''
  requestAnimationFrame(() => document.getElementById('discover-playlists')?.scrollIntoView())
}
function showShelf(shelf: string) {
  if (browseOpen.value) {
    ++request
    browseOpen.value = false
    query.value = ''
    search.value = ''
  }
  requestAnimationFrame(() => document.getElementById(shelf)?.scrollIntoView())
}
function submitSearch() {
  openBrowse('latest', browseOpen.value ? genre.value : '', search.value.trim())
}
function clearSearch() {
  search.value = ''
  submitSearch()
}
function playing(track: PlayableTrack) {
  return player.current?.audio === track.audio && (player.playing || player.starting)
}
function duration(track: PlayableTrack) {
  const seconds = Math.floor(player.current?.audio === track.audio && player.duration > 0
    ? player.duration : durations.value[track.audio] ?? 0)
  return seconds > 0 ? `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` : ''
}
async function play(row: DiscoverShelfTrack, queue: DiscoverShelfTrack[] = rows.value) {
  const wasPlaying = playing(row.track)
  player.setQueue(queue.map(item => item.track), false)
  await player.toggle(row.track)
  if (wasPlaying || player.current?.audio !== row.track.audio || !player.playing) return
  if (player.duration > 0) durations.value[row.track.audio] = player.duration
  const release = row.song
  if (!release || played.has(row.track.audio)) return
  played.add(row.track.audio)
  try {
    await recordDiscoverPlay(release)
    for (const list of [songs.value, featuredSongs.value, popularSongs.value, latestSongs.value]) {
      const song = list.find(item => item.song.id === release.id && item.song.version === release.version)
      if (song) song.plays = (song.plays ?? 0) + 1
    }
    const artist = artists.value?.find(item => item.artistId === row.artistId)
    if (artist) artist.plays++
    if (shelves.value?.listeningDataAvailable) void loadShelves()
  } catch {
    played.delete(row.track.audio)
  }
}

onMounted(() => { initializeHeroRotation(); void loadEditorial(); void loadChristmasPlaylist() })
onBeforeUnmount(() => {
  disposed = true; ++request; ++editorialRequest; ++shelvesRequest
  clearInterval(heroTimer)
  motionQuery?.removeEventListener('change', motionChanged)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>

<template>
  <div class="discover">
    <header class="discover-header">
      <div class="discover-width discover-header-inner">
        <RouterLink class="discover-brand" to="/" :aria-label="t('Cheerful Music 青风音乐首页')"><BrandLogo standalone /></RouterLink>
        <nav class="discover-nav" :aria-label="t('主导航')">
          <RouterLink to="/">{{ t('首页') }}</RouterLink>
          <RouterLink to="/discover" aria-current="page">{{ t('广场') }}</RouterLink>
          <a href="#discover-artists">{{ t('歌手') }}</a>
          <a href="#discover-playlists">{{ t('歌单') }}</a>
        </nav>
        <form class="discover-search" role="search" @submit.prevent="submitSearch">
          <button type="submit" class="discover-search-submit" :aria-label="t('搜索')"><NavIcon name="search" /></button>
          <input v-model="search" type="search" maxlength="100" :placeholder="t('搜索歌曲、歌手或歌单')"
            :aria-label="t('搜索歌曲、歌手或歌单')" @search="!search && query && submitSearch">
        </form>
        <div class="discover-header-actions">
          <RouterLink class="discover-create" to="/create">{{ t('开始创作') }}</RouterLink>
          <RouterLink class="discover-login" :to="signedIn ? '/profile' : '/account'">{{ t(signedIn ? '我的资料' : '登录/注册') }}</RouterLink>
        </div>
      </div>
    </header>

    <div class="discover-width">
      <div class="discover-page-heading"><h1>{{ t('音乐广场') }}</h1></div>
      <section class="discover-hero" :aria-label="t('广场推荐')" :aria-roledescription="t('轮播')"
        @mouseenter="heroHovered = true" @mouseleave="heroHovered = false" @focusin="heroFocused = true" @focusout="heroFocusOut">
        <div class="discover-hero-preview discover-hero-preview-before">
          <img :src="heroBefore.src" :style="{ objectPosition: heroBefore.position }" alt="">
        </div>
        <div class="discover-hero-main">
          <img class="discover-hero-background" :src="heroBackground?.src ?? coast"
            :style="{ objectPosition: heroBackground?.position }" alt="" width="2172" height="724" fetchpriority="high">
          <div class="discover-hero-content">
            <h2 :class="{ 'is-long-title': heroTitleLength > 10, 'is-very-long-title': heroTitleLength > 32 }" :title="hero?.song.title">{{ hero?.song.title || t('风从海上来') }}</h2>
            <RouterLink v-if="hero" class="discover-hero-artist" :to="{ name: 'artist-home', params: { id: hero.artistId } }">{{ hero.stageName }}</RouterLink>
            <p v-else>{{ t('从一段故事，到一首属于你的歌。') }}</p>
            <button v-if="hero" type="button" class="discover-hero-listen" :aria-label="t(playing(hero.track) ? '暂停 {title}' : '试听 {title}', { title: hero.song.title })" @click="play(hero, heroRows)"><PlaybackIcon :playing="playing(hero.track)" />{{ t(playing(hero.track) ? '暂停' : '立即聆听') }}</button>
            <a v-else class="discover-hero-listen" href="#discover-latest" @click.prevent="showShelf('discover-latest')"><PlaybackIcon :playing="false" />{{ t('立即聆听') }}</a>
          </div>
        </div>
        <div class="discover-hero-preview discover-hero-preview-after">
          <img :src="heroAfter.src" :style="{ objectPosition: heroAfter.position }" alt="">
        </div>
        <template v-if="heroSlideCount > 1">
          <button class="discover-hero-previous" type="button" :aria-label="t('上一张推荐')" @click="changeHero(-1)">‹</button>
          <button class="discover-hero-next" type="button" :aria-label="t('下一张推荐')" @click="changeHero(1)">›</button>
          <div class="discover-hero-controls">
            <div class="discover-hero-dots" role="group" :aria-label="t('切换推荐')">
              <button type="button" :aria-label="t('查看音乐广场介绍')" :aria-pressed="heroIndex === 0" @click="heroIndex = 0"></button>
              <button v-for="(row, index) in heroRows" :key="row.song.id" type="button" :aria-label="t('查看推荐：{title}', { title: row.song.title })" :aria-pressed="heroIndex === index + 1" @click="heroIndex = index + 1"></button>
            </div>
            <button class="discover-hero-rotation" type="button" :aria-label="t(heroPaused ? '继续轮播' : '暂停轮播')" :aria-pressed="heroPaused" @click="heroPaused = !heroPaused"><PlaybackIcon :playing="!heroPaused" /></button>
          </div>
        </template>
      </section>
    </div>

    <div class="discover-width discover-content">
      <section class="discover-main" :aria-label="t('音乐广场')">
        <section id="discover-playlists" class="discover-playlists" aria-labelledby="discover-playlists-title">
          <header class="discover-section-heading">
            <h2 id="discover-playlists-title">{{ t('推荐歌单') }}</h2>
          </header>
          <ul v-if="visiblePlaylists.length" class="discover-playlist-tiles">
            <li v-for="playlist in visiblePlaylists" :key="playlist.id"><button type="button"
              :class="{ 'discover-playlist-disco': playlist.id === 'disco', 'discover-playlist-christmas': playlist.id === 'christmas' }"
              @click="selectedPlaylist = playlist.id">
              <img :src="playlist.cover" alt="" loading="lazy">
              <span class="discover-playlist-title"><strong>{{ playlist.title }}</strong><span>{{ t('{count} 首作品', { count: playlist.tracks.length }) }}</span></span>
            </button></li>
          </ul>
          <p v-else class="discover-side-empty">{{ t('没有找到符合条件的歌单。') }}</p>
        </section>
        <section v-if="browseOpen" id="discover-tracks" class="discover-track-section" :aria-label="t('筛选广场歌曲')">
          <button type="button" class="text-button discover-back" @click="returnToShelves">{{ t('返回广场') }}</button>
          <div class="discover-toolbar">
            <div class="discover-tabs" role="group" :aria-label="t('筛选广场歌曲')">
              <button v-for="item in tabs" :key="item.id" type="button" :aria-pressed="tab === item.id"
                @click="select(item.id)">{{ t(item.label) }}</button>
            </div>
            <select v-model="genre" class="discover-genre" :aria-label="t('音乐风格')" @change="load(true)">
              <option v-for="option in genres" :key="option.id" :value="option.id">{{ t(option.label) }}</option>
            </select>
          </div>
          <div v-if="query" class="discover-query">
            <span>{{ t('搜索结果：{query}', { query }) }}</span>
            <button class="text-button" type="button" @click="clearSearch">{{ t('清除搜索') }}</button>
          </div>

          <ul v-if="rows.length" class="discover-songs">
            <DiscoverSongCard v-for="row in rows" :key="row.song.id" :song="row" :cover="row.track.cover" :playing="playing(row.track)" :duration="duration(row.track)" :genre="genreLabel(row.genre)" @play="play(row)" />
          </ul>
          <p v-if="loading" class="discover-message" role="status">{{ t('正在加载歌曲…') }}</p>
          <div v-else-if="failed" class="discover-message" role="alert">
            <p>{{ t('暂时无法加载广场歌曲，请重试。') }}</p>
            <button type="button" class="button secondary" @click="load()">{{ t('重试') }}</button>
          </div>
          <div v-else-if="!rows.length" class="discover-message">
            <p>{{ t(query || genre ? '没有找到符合条件的歌曲。' : tab === 'featured' ? '还没有精选歌曲。' : '还没有歌曲发布到广场。') }}</p>
            <button v-if="tab === 'featured' && !query && !genre" type="button" class="button secondary" @click="select('latest')">
              {{ t('查看最新发布') }}
            </button>
          </div>
          <div v-else-if="hasMore" class="discover-more">
            <button type="button" class="button secondary" @click="load()">{{ t('加载更多') }}</button>
          </div>
        </section>
        <div v-else class="discover-shelves">
          <section id="discover-featured" class="discover-shelf">
            <header class="discover-section-heading">
              <div class="discover-shelf-heading"><h2>{{ t('青风音乐') }}</h2><p>{{ t(latestRecommendations.length ? '新声推荐' : '编辑精选') }}</p></div>
            </header>
            <DiscoverShelf :label="t('青风音乐')" :rows="featuredRows" variant="square" :loading="editorialLoading"
              :failed="featuredFailed" :empty="t('还没有精选歌曲。')" @retry="loadEditorial" @play="play($event, featuredRows)" />
          </section>
          <section id="discover-latest" class="discover-shelf">
            <header class="discover-section-heading">
              <h2>{{ t('今日新歌') }}</h2>
            </header>
            <DiscoverShelf :label="t('今日新歌')" :rows="latestRows" variant="compact" :loading="editorialLoading"
              :failed="latestFailed" @retry="loadEditorial" @play="play($event, latestRows)" />
          </section>
          <section id="discover-vocal" class="discover-shelf discover-vocal">
            <header class="discover-section-heading">
              <div class="discover-shelf-heading"><h2>{{ t('真人演唱') }}</h2><p>{{ t('用 AI 创作歌曲，再用自己的声音唱出来。') }}</p></div>
              <button v-if="vocalRows.length" type="button" :aria-expanded="expanded === 'vocal'"
                @click="expanded = expanded === 'vocal' ? undefined : 'vocal'">{{ t(expanded === 'vocal' ? '收起' : '查看全部') }} ›</button>
            </header>
            <DiscoverShelf :label="t('真人演唱')" :rows="vocalRows" :upcoming="upcomingVocal" variant="wide"
              :expanded="expanded === 'vocal'" @play="play($event, vocalRows)" />
          </section>
          <section id="discover-today" class="discover-shelf">
            <header class="discover-section-heading">
              <div class="discover-shelf-heading"><h2>{{ t('今日热歌') }}</h2><p>{{ t(cumulativeHotSongs.length ? '暂按累计播放量排序' : '按当日播放量排序') }}</p></div>
              <button v-if="todayRows.length" type="button" :aria-expanded="expanded === 'today'"
                @click="expanded = expanded === 'today' ? undefined : 'today'">{{ t(expanded === 'today' ? '收起' : '查看全部') }} ›</button>
            </header>
            <DiscoverShelf :label="t('今日热歌')" :rows="todayRows" variant="square" :loading="editorialLoading" :failed="shelvesFailed"
              :empty="t(shelves?.listeningDataAvailable ? '今天还没有播放记录。' : '今日播放排行暂不可用。')"
              :expanded="expanded === 'today'" show-plays @retry="loadShelves" @play="play($event, todayRows)" />
          </section>
          <section v-for="section in genreShelves" :id="'discover-' + section.id" :key="section.id" class="discover-shelf">
            <header class="discover-section-heading">
              <h2>{{ t(section.label) }}</h2>
            </header>
            <DiscoverShelf :label="t(section.label)" :rows="section.square" variant="square" :loading="editorialLoading"
              :failed="shelvesFailed" @retry="loadShelves" @play="play($event, section.square)" />
            <div v-if="section.hasWideRow && section.wide.length" class="discover-wide-row">
              <DiscoverShelf :label="t(section.label)" :rows="section.wide" variant="wide" @play="play($event, section.wide)" />
            </div>
          </section>
        </div>
        <section id="discover-artists" class="discover-side-panel discover-artists" aria-labelledby="discover-artists-title">
          <header class="discover-side-heading">
            <h2 id="discover-artists-title">{{ t('热门创作者') }}</h2>
            <button v-if="artists && artists.length > 3" type="button" :aria-expanded="showAllArtists"
              @click="showAllArtists = !showAllArtists">{{ t(showAllArtists ? '收起' : '查看更多') }} <span aria-hidden="true">›</span></button>
          </header>
          <ul v-if="visibleArtists.length" class="discover-side-list">
            <li v-for="artist in visibleArtists" :key="artist.artistId">
              <RouterLink class="discover-side-row" :to="{ name: 'artist-home', params: { id: artist.artistId } }">
                <ArtistAvatar :name="artist.stageName" :src="artist.avatarKey ? publicAvatarUrl(artist.artistId, artist.avatarKey) : undefined" />
                <div>
                  <strong>{{ artist.stageName }}</strong>
                  <span v-if="'songs' in artist">{{ t('{count} 首作品', { count: count.format(artist.songs) }) }} · {{ t('{count} 次播放', { count: count.format(artist.plays) }) }}</span>
                </div>
                <span class="discover-side-arrow" aria-hidden="true">›</span>
              </RouterLink>
            </li>
          </ul>
          <p v-else class="discover-side-empty">{{ t('有歌曲发布后，创作者会出现在这里。') }}</p>
        </section>
        <RouterLink class="discover-publish" to="/works">{{ t('发布我的作品') }}<NavIcon name="arrow" /></RouterLink>
      </section>

      <aside class="discover-sidebar">
        <section class="discover-side-panel" aria-labelledby="discover-genres-title">
          <header class="discover-side-heading"><h2 id="discover-genres-title">{{ t('浏览风格') }}</h2></header>
          <ul class="discover-genre-links">
            <li v-for="section in discoverGenres" :key="section.id">
              <button v-if="browseOpen" type="button" @click="openBrowse('latest', section.id)">{{ t(section.label) }} <span aria-hidden="true">›</span></button>
              <a v-else :href="'#discover-' + section.id">{{ t(section.label) }} <span aria-hidden="true">›</span></a>
            </li>
          </ul>
        </section>
        <section class="discover-side-panel" aria-labelledby="discover-popular-title">
          <header class="discover-side-heading">
            <h2 id="discover-popular-title">{{ t(cumulativeHotSongs.length ? '累计热歌榜' : '今日热歌榜') }}</h2>
            <button v-if="ranking.length" type="button" @click="showShelf('discover-today')">{{ t('查看榜单') }} ›</button>
          </header>
          <ol v-if="ranking.length" class="discover-ranking">
            <li v-for="(row, index) in ranking" :key="row.track.audio">
              <button type="button" class="discover-ranking-row" :aria-label="t(playing(row.track) ? '暂停 {title}' : '试听 {title}', { title: row.song.title })" @click="play(row, todayRows)">
                <span class="discover-rank" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
                <span v-if="index === 0" class="discover-ranking-cover"><img :src="row.track.cover" alt="" width="42" height="42" loading="lazy"></span>
                <span class="discover-ranking-info"><strong>{{ row.song.title }}</strong><span>{{ row.stageName }}</span></span>
                <span v-if="row.plays !== undefined" class="discover-ranking-count">{{ count.format(row.plays) }}</span>
              </button>
            </li>
          </ol>
          <p v-else-if="editorialLoading" class="discover-side-empty" role="status">{{ t('正在加载歌曲…') }}</p>
          <div v-else-if="shelvesFailed" class="discover-side-empty" role="alert">
            {{ t('今日播放排行暂不可用。') }}
            <button type="button" class="text-button" @click="loadShelves">{{ t('重试') }}</button>
          </div>
          <p v-else class="discover-side-empty">{{ t(shelves?.listeningDataAvailable ? '今天还没有播放记录。' : '今日播放排行暂不可用。') }}</p>
        </section>
      </aside>
    </div>
    <PlaylistDetails v-if="playlistDetails" :playlist="playlistDetails" @close="selectedPlaylist = ''" />
  </div>
</template>

<style src="../styles/discover.css" scoped></style>
