<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import BrandLogo from '../../../shared/ui/BrandLogo.vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import ArtistAvatar from '../components/ArtistAvatar.vue'
import DiscoverSongCard from '../components/DiscoverSongCard.vue'
import PlaylistDetails from '../../home/components/PlaylistDetails.vue'
import { usePreviewPlaylists } from '../../home/model/playlists'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import {
  listDiscoverSongs, publicAvatarUrl, publicTrack, recordDiscoverPlay, type DiscoverArtist, type DiscoverSong,
} from '../api/artist'
import sunset from '../../../assets/discover/cover-1.webp'
import moon from '../../../assets/discover/cover-2.webp'
import mountains from '../../../assets/discover/cover-3.webp'
import lavender from '../../../assets/discover/cover-4.webp'
import city from '../../../assets/discover/cover-5.webp'
import lake from '../../../assets/discover/cover-6.webp'
import coast from '../../../assets/discover/editorial-coast.webp'

const tabs = [
  { id: 'latest', label: '最新发布' },
  { id: 'featured', label: '精选' },
  { id: 'popular', label: '热门' },
] as const
const genres = [
  { id: '', label: '全部风格' }, { id: 'pop', label: '流行' }, { id: 'folk', label: '民谣' },
  { id: 'guofeng', label: '古风' }, { id: 'rock', label: '摇滚' }, { id: 'electronic', label: '电子' },
  { id: 'instrumental', label: '纯音乐' }, { id: 'other', label: '其他风格' },
]
const covers = [sunset, moon, mountains, lavender, city, lake]
const player = usePlayer()
const playlists = usePreviewPlaylists()
const tab = ref<(typeof tabs)[number]['id']>('latest')
const genre = ref('')
const search = ref('')
const query = ref('')
const songs = ref<DiscoverSong[]>([])
const featuredSongs = ref<DiscoverSong[]>([])
const popularSongs = ref<DiscoverSong[]>([])
const editorialLoading = ref(true)
const popularFailed = ref(false)
const heroIndex = ref(0)
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

const count = computed(() => new Intl.NumberFormat(locale.value))
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
function withTracks(items: DiscoverSong[]) {
  return items.map(item => {
    const track = publicTrack(item.artistId, item.song)
    if (!item.song.hasCover) {
      const seed = Array.from(item.song.id).reduce((sum, char) => (sum * 31 + char.charCodeAt(0)) >>> 0, 0)
      track.cover = covers[seed % covers.length]!
    }
    return { ...item, track }
  })
}
const rows = computed(() => withTracks(songs.value))
const featuredRows = computed(() => withTracks(featuredSongs.value).slice(0, 4))
const popularRows = computed(() => withTracks(popularSongs.value).slice(0, 3))
const heroRows = computed(() => featuredRows.value.slice(0, 3))
const hero = computed(() => heroRows.value[heroIndex.value % (heroRows.value.length || 1)])
const visibleArtists = computed(() => {
  const found = artists.value ?? Array.from(new Map(songs.value.map(song => [song.artistId, {
    artistId: song.artistId, stageName: song.stageName, avatarKey: null,
  }])).values())
  return showAllArtists.value ? found : found.slice(0, 3)
})
const visiblePlaylists = computed(() => playlists.value.flatMap(playlist => {
  const keyword = query.value.toLocaleLowerCase()
  if (!playlist.tracks.length || keyword && !playlist.title.toLocaleLowerCase().includes(keyword)) return []
  return [playlist]
}).slice(0, 3))
const playlistDetails = computed(() => playlists.value.find(playlist => playlist.id === selectedPlaylist.value))

async function loadEditorial() {
  const attempt = ++editorialRequest
  editorialLoading.value = true
  const results = await Promise.allSettled([
    listDiscoverSongs('featured', 0, '', ''),
    listDiscoverSongs('popular', 0, '', ''),
  ])
  if (disposed || attempt !== editorialRequest) return
  const [featured, popular] = results
  if (featured.status === 'fulfilled') { featuredSongs.value = featured.value.songs; heroIndex.value = 0 }
  if (popular.status === 'fulfilled') popularSongs.value = popular.value.songs
  popularFailed.value = popular.status === 'rejected'
  editorialLoading.value = false
}

function changeHero(direction: number) {
  const size = heroRows.value.length
  if (size) heroIndex.value = (heroIndex.value + direction + size) % size
}
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
function submitSearch() {
  query.value = search.value.trim()
  void load(true)
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
async function play(row: (typeof rows.value)[number], queue = rows.value) {
  const wasPlaying = playing(row.track)
  player.setQueue(queue.map(item => item.track), false)
  await player.toggle(row.track)
  if (wasPlaying || player.current?.audio !== row.track.audio || !player.playing) return
  if (player.duration > 0) durations.value[row.track.audio] = player.duration
  if (played.has(row.track.audio)) return
  played.add(row.track.audio)
  try {
    await recordDiscoverPlay(row.song)
    for (const list of [songs.value, featuredSongs.value, popularSongs.value]) {
      const song = list.find(item => item.song.id === row.song.id && item.song.version === row.song.version)
      if (song) song.plays = (song.plays ?? 0) + 1
    }
    const artist = artists.value?.find(item => item.artistId === row.artistId)
    if (artist) artist.plays++
  } catch {
    played.delete(row.track.audio)
  }
}

onMounted(() => { void load(true); void loadEditorial() })
onBeforeUnmount(() => { disposed = true; ++request; ++editorialRequest })
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
      <div class="discover-page-heading"><h1>{{ t('音乐广场') }}</h1><p>{{ t('聆听每一份创作的灵感') }}</p></div>
      <section class="discover-hero" :class="{ 'has-featured': hero }" :aria-label="t('广场推荐')">
        <img class="discover-hero-background" :src="coast" alt="" width="2172" height="724" fetchpriority="high">
        <div class="discover-hero-content">
          <span class="discover-hero-label">{{ t(hero ? '精选作品' : '音乐广场') }}</span>
          <h2>{{ hero?.song.title || t('让好音乐，被更多人听见') }}</h2>
          <RouterLink v-if="hero" class="discover-hero-artist" :to="{ name: 'artist-home', params: { id: hero.artistId } }">{{ hero.stageName }}</RouterLink>
          <p v-else>{{ t('从一段故事，到一首属于你的歌。') }}</p>
          <button v-if="hero" type="button" class="discover-hero-listen" :aria-label="t(playing(hero.track) ? '暂停 {title}' : '试听 {title}', { title: hero.song.title })" @click="play(hero, heroRows)"><PlaybackIcon :playing="playing(hero.track)" />{{ t(playing(hero.track) ? '暂停' : '立即聆听') }}</button>
          <a v-else class="discover-hero-listen" href="#discover-tracks"><PlaybackIcon :playing="false" />{{ t('立即聆听') }}</a>
        </div>
        <img v-if="hero" class="discover-hero-cover" :src="hero.track.cover" :alt="t('歌曲封面')" width="260" height="260">
        <template v-if="heroRows.length > 1">
          <button class="discover-hero-previous" type="button" :aria-label="t('上一张推荐')" @click="changeHero(-1)">‹</button>
          <button class="discover-hero-next" type="button" :aria-label="t('下一张推荐')" @click="changeHero(1)">›</button>
          <div class="discover-hero-dots" role="group" :aria-label="t('切换推荐')">
            <button v-for="(row, index) in heroRows" :key="row.song.id" type="button" :aria-label="t('查看推荐：{title}', { title: row.song.title })" :aria-pressed="heroIndex === index" @click="heroIndex = index"></button>
          </div>
        </template>
      </section>
    </div>

    <div class="discover-width discover-content">
      <section class="discover-main" :aria-label="t('音乐广场')">
        <section id="discover-playlists" class="discover-playlists" aria-labelledby="discover-playlists-title">
          <header class="discover-section-heading"><h2 id="discover-playlists-title">{{ t('推荐歌单') }}</h2><RouterLink to="/#inspiration">{{ t('查看更多') }} <span aria-hidden="true">›</span></RouterLink></header>
          <ul v-if="visiblePlaylists.length" class="discover-playlist-tiles">
            <li v-for="playlist in visiblePlaylists" :key="playlist.id"><button type="button" @click="selectedPlaylist = playlist.id">
              <img :src="playlist.cover" alt="" loading="lazy" width="400" height="160">
              <span class="discover-playlist-title"><strong>{{ playlist.title }}</strong><span>{{ t('{count} 首作品', { count: playlist.tracks.length }) }}</span></span>
              <span class="discover-playlist-icon" aria-hidden="true"><PlaybackIcon :playing="false" /></span>
            </button></li>
          </ul>
          <p v-else class="discover-side-empty">{{ t('没有找到符合条件的歌单。') }}</p>
        </section>
        <section id="discover-tracks" class="discover-track-section" :aria-label="t('筛选广场歌曲')">
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
        <section v-if="featuredRows.length && tab !== 'featured' && !query && !genre" class="discover-featured" aria-labelledby="discover-featured-title">
          <header class="discover-section-heading"><h2 id="discover-featured-title">{{ t('精选作品') }}</h2><button type="button" @click="select('featured')">{{ t('查看更多') }} <span aria-hidden="true">›</span></button></header>
          <ul class="discover-songs"><DiscoverSongCard v-for="row in featuredRows" :key="row.song.id" :song="row" :cover="row.track.cover" :playing="playing(row.track)" :duration="duration(row.track)" @play="play(row, featuredRows)" /></ul>
        </section>
      </section>

      <aside class="discover-sidebar">
        <section id="discover-artists" class="discover-side-panel" aria-labelledby="discover-artists-title">
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
        <section class="discover-side-panel" aria-labelledby="discover-popular-title">
          <header class="discover-side-heading"><h2 id="discover-popular-title">{{ t('正在流行') }}</h2><button type="button" @click="select('popular')">{{ t('查看更多') }} <span aria-hidden="true">›</span></button></header>
          <ol v-if="popularRows.length" class="discover-ranking">
            <li v-for="(row, index) in popularRows" :key="row.song.id">
              <button type="button" class="discover-ranking-row" :aria-label="t(playing(row.track) ? '暂停 {title}' : '试听 {title}', { title: row.song.title })" @click="play(row, popularRows)">
                <span class="discover-rank" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
                <span class="discover-ranking-cover"><img :src="row.track.cover" alt="" width="48" height="48" loading="lazy"><PlaybackIcon :playing="playing(row.track)" /></span>
                <span class="discover-ranking-info"><strong>{{ row.song.title }}</strong><span>{{ row.stageName }}</span></span>
                <span v-if="row.plays !== undefined" class="discover-ranking-count"><NavIcon name="play" />{{ count.format(row.plays) }}</span>
              </button>
            </li>
          </ol>
          <p v-else-if="editorialLoading" class="discover-side-empty" role="status">{{ t('正在加载歌曲…') }}</p>
          <div v-else-if="popularFailed" class="discover-side-empty"><p>{{ t('暂时无法加载热门歌曲。') }}</p><button type="button" class="text-button" @click="loadEditorial()">{{ t('重试') }}</button></div>
          <p v-else class="discover-side-empty">{{ t('还没有歌曲发布到广场。') }}</p>
        </section>
        <RouterLink class="discover-publish" to="/works">{{ t('发布我的作品') }}<NavIcon name="arrow" /></RouterLink>
      </aside>
    </div>
    <PlaylistDetails v-if="playlistDetails" :playlist="playlistDetails" @close="selectedPlaylist = ''" />
  </div>
</template>

<style src="../styles/discover.css" scoped></style>
