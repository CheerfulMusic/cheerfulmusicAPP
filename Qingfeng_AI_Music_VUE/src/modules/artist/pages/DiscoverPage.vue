<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import BrandLogo from '../../../shared/ui/BrandLogo.vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import ArtistAvatar from '../components/ArtistAvatar.vue'
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
const playlistCovers = [sunset, moon, lake]
const player = usePlayer()
const playlists = usePreviewPlaylists()
const tab = ref<(typeof tabs)[number]['id']>('latest')
const genre = ref('')
const search = ref('')
const query = ref('')
const songs = ref<DiscoverSong[]>([])
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

const count = computed(() => new Intl.NumberFormat(locale.value))
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
const rows = computed(() => songs.value.map(item => {
  const track = publicTrack(item.artistId, item.song)
  if (!item.song.hasCover) {
    const seed = Array.from(item.song.id).reduce((sum, char) => (sum * 31 + char.charCodeAt(0)) >>> 0, 0)
    track.cover = covers[seed % covers.length]!
  }
  return { ...item, track }
}))
const visibleArtists = computed(() => {
  const found = artists.value ?? Array.from(new Map(songs.value.map(song => [song.artistId, {
    artistId: song.artistId, stageName: song.stageName, avatarKey: null,
  }])).values())
  return showAllArtists.value ? found : found.slice(0, 3)
})
const visiblePlaylists = computed(() => playlists.value.flatMap((playlist, index) => {
  const keyword = query.value.toLocaleLowerCase()
  if (keyword && !playlist.title.toLocaleLowerCase().includes(keyword)) return []
  return [{ ...playlist, cover: playlistCovers[index] ?? playlist.cover }]
}))
const playlistDetails = computed(() => visiblePlaylists.value.find(playlist => playlist.id === selectedPlaylist.value))

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
async function play(row: (typeof rows.value)[number]) {
  const wasPlaying = playing(row.track)
  player.setQueue(rows.value.map(item => item.track), false)
  await player.toggle(row.track)
  if (wasPlaying || player.current?.audio !== row.track.audio || !player.playing) return
  if (player.duration > 0) durations.value[row.track.audio] = player.duration
  if (played.has(row.track.audio)) return
  played.add(row.track.audio)
  try {
    await recordDiscoverPlay(row.song)
    const song = songs.value.find(item => item.song.id === row.song.id)
    if (song) song.plays = (song.plays ?? 0) + 1
    const artist = artists.value?.find(item => item.artistId === row.artistId)
    if (artist) artist.plays++
  } catch {
    played.delete(row.track.audio)
  }
}

onMounted(() => void load(true))
onBeforeUnmount(() => { ++request })
</script>

<template>
  <div class="discover">
    <header class="discover-header">
      <div class="discover-width discover-header-inner">
        <RouterLink class="discover-brand" to="/" :aria-label="t('Cheerful Music 青风音乐首页')"><BrandLogo standalone /></RouterLink>
        <nav class="discover-nav" :aria-label="t('主导航')">
          <RouterLink to="/">{{ t('首页') }}</RouterLink>
          <RouterLink to="/discover" aria-current="page">{{ t('发现') }}</RouterLink>
          <a href="#discover-artists">{{ t('歌手') }}</a>
          <a href="#discover-playlists">{{ t('歌单') }}</a>
        </nav>
        <form class="discover-search" role="search" @submit.prevent="submitSearch">
          <button type="submit" class="discover-search-submit" :aria-label="t('搜索')"><NavIcon name="search" /></button>
          <input v-model="search" type="search" maxlength="100" :placeholder="t('搜索歌曲、歌手或歌单')"
            :aria-label="t('搜索歌曲、歌手或歌单')" @search="!search && query && submitSearch">
        </form>
        <RouterLink class="discover-login" :to="signedIn ? '/profile' : '/account'">{{ t(signedIn ? '我的资料' : '登录/注册') }}</RouterLink>
      </div>
    </header>

    <section class="discover-hero" aria-labelledby="discover-title">
      <div class="discover-width discover-hero-content">
        <h1 id="discover-title">{{ t('发现音乐') }}</h1>
        <p>{{ t('聆听每一份创作的灵感') }} <span aria-hidden="true">✦</span></p>
      </div>
    </section>

    <div class="discover-width discover-content">
      <section class="discover-main" :aria-label="t('发现音乐')">
        <div class="discover-toolbar">
          <div class="discover-tabs" role="group" :aria-label="t('筛选发现歌曲')">
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
          <li v-for="row in rows" :key="row.song.id" class="discover-song">
            <div class="discover-art">
              <img :src="row.track.cover" alt="" loading="lazy">
              <button class="discover-play" type="button" :aria-pressed="playing(row.track)"
                :aria-label="t(playing(row.track) ? '暂停 {title}' : '试听 {title}', { title: row.song.title })" @click="play(row)">
                <PlaybackIcon :playing="playing(row.track)" />
              </button>
              <span v-if="duration(row.track)" class="discover-duration">{{ duration(row.track) }}</span>
            </div>
            <div class="discover-song-details">
              <h2>{{ row.song.title }}</h2>
              <RouterLink :to="{ name: 'artist-home', params: { id: row.artistId } }">{{ row.stageName }}</RouterLink>
              <div class="discover-song-meta">
                <span v-if="row.plays !== undefined" :aria-label="t('{count} 次播放', { count: count.format(row.plays) })">
                  <NavIcon name="play" />{{ count.format(row.plays) }}
                </span>
                <span v-if="row.genre && row.genre !== 'other'" class="discover-song-genre">
                  {{ t(genres.find(item => item.id === row.genre)?.label ?? '') }}
                </span>
              </div>
            </div>
          </li>
        </ul>
        <p v-if="loading" class="discover-message" role="status">{{ t('正在加载歌曲…') }}</p>
        <div v-else-if="failed" class="discover-message" role="alert">
          <p>{{ t('暂时无法加载发现歌曲，请重试。') }}</p>
          <button type="button" class="button secondary" @click="load()">{{ t('重试') }}</button>
        </div>
        <div v-else-if="!rows.length" class="discover-message">
          <p>{{ t(query || genre ? '没有找到符合条件的歌曲。' : tab === 'featured' ? '还没有精选歌曲。' : '还没有歌曲发布到发现。') }}</p>
          <button v-if="tab === 'featured' && !query && !genre" type="button" class="button secondary" @click="select('latest')">
            {{ t('查看最新发布') }}
          </button>
        </div>
        <div v-else-if="hasMore" class="discover-more">
          <button type="button" class="button secondary" @click="load()">{{ t('加载更多') }}</button>
        </div>
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
        <section id="discover-playlists" class="discover-side-panel" aria-labelledby="discover-playlists-title">
          <header class="discover-side-heading"><h2 id="discover-playlists-title">{{ t('推荐歌单') }}</h2>
            <RouterLink to="/#inspiration">{{ t('查看更多') }} <span aria-hidden="true">›</span></RouterLink>
          </header>
          <ul v-if="visiblePlaylists.length" class="discover-side-list">
            <li v-for="playlist in visiblePlaylists" :key="playlist.id">
              <button type="button" class="discover-side-row discover-playlist" @click="selectedPlaylist = playlist.id">
                <img :src="playlist.cover" alt="" loading="lazy">
                <span><strong>{{ playlist.title }}</strong><span>{{ t('{count} 首作品', { count: playlist.tracks.length }) }}</span></span>
                <span class="discover-playlist-icon" aria-hidden="true"><PlaybackIcon :playing="false" /></span>
              </button>
            </li>
          </ul>
          <p v-else class="discover-side-empty">{{ t('没有找到符合条件的歌单。') }}</p>
        </section>
      </aside>
    </div>
    <PlaylistDetails v-if="playlistDetails" :playlist="playlistDetails" @close="selectedPlaylist = ''" />
  </div>
</template>

<style src="../styles/discover.css" scoped></style>
