<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { discoverHref, locale, t } from '../../../shared/i18n'
import LanguageSwitcher from '../../../shared/i18n/LanguageSwitcher.vue'
import { discoverSeo } from '../../../shared/i18n/site-seo'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import BrandLogo from '../../../shared/ui/BrandLogo.vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { discoverSongHref } from '../api/discover-song'
import { readInviteStatus } from '../../beta-access/api'
import ArtistAvatar from '../components/ArtistAvatar.vue'
import DiscoverSongCard from '../components/DiscoverSongCard.vue'
import DiscoverShelf from '../components/DiscoverShelf.vue'
import { withDiscoverEditorialCover } from '../model/discover-editorial-artwork'
import { discoverSongArtwork, jixiangwuHeroArtwork, prayerHeroArtwork, type HeroArtwork } from '../model/discover-hero-artwork'
import { discoverJazzRows, discoverRockRows, prayerBeneathTheStarsRow } from '../model/discover-genre-tracks'
import {
  christmasSongId, discoverGenres, globalPopWideCoverPositions, publicDiscoverTrack, withDiscoverPreviewCover,
  type DiscoverShelfTrack, type PublishedShelfTrack,
} from '../model/discover-shelves'
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
import walkedFar from '../../../assets/discover/carousel-walked-far.webp'
import christmasSky from '../../../assets/discover/carousel-christmas-sky.webp'
import christmasCover from '../../../assets/discover/playlists/christmas.webp'

const props = defineProps<{ publicEntry?: boolean }>()
// Editorial audio without a discover publication remains in the previously invited experience.
const editorialAccess = ref(!props.publicEntry)
const checkingEditorialAccess = ref(!!props.publicEntry)

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
const heroRotationMs = 10_000
const streetlightSongId = '0f831f4b-1f3f-4d14-b6cf-b33534863622'
const replacedHeroSongId = 'acfaa9d5-ce74-477f-9db8-d3700311a26c'
const invincibleSongId = '57bb7310-c64c-4873-b239-f2a268a7dedd'
const micangshanSongId = '09aaf684-2b68-438f-aa17-8730a352bf74'
const mascotSongId = '1485ddee-9a1f-435b-bfc8-403cccb8c243'
const excludedRankedHeroIds = new Set([
  replacedHeroSongId, streetlightSongId, christmasSongId, invincibleSongId, micangshanSongId, mascotSongId,
])
const velvetHoursSongId = '0fef4b09-77a8-489f-be7a-f61490a9d848'
const christmasArtistId = '308036ef-8bef-486f-88cc-8266e5797768'
const christmasPlaylistSongIds = [
  christmasSongId,
  '752fa2d9-ec96-47c3-87a5-a1d49d0135af', // Snow on the Floor
  '1c5c325c-6a45-4607-a8e4-5baadabc2032', // Navidad
  '1cf20b2d-76cd-46de-9666-77a7303588a8', // Nochebuena en la Mesa
]
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
const firstPopularPending = ref(true)
const supplementalHeroSong = ref<DiscoverSong>()
const mascotRelease = ref<DiscoverSong>()
const velvetHours = ref<PublishedShelfTrack>()
const editorialLoading = ref(true)
const waitingForEntry = computed(() => checkingEditorialAccess.value && editorialLoading.value)
const selectedHeroTrackId = ref('')
const unavailableHeroImages = ref(new Set<string>())
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
let inactive = false
let editorialRequest = 0
let shelvesRequest = 0
let heroTimer: ReturnType<typeof setInterval> | undefined
let motionQuery: MediaQueryList | undefined

const count = computed(() => new Intl.NumberFormat(locale.value))
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
function withTracks(items: DiscoverSong[]): PublishedShelfTrack[] {
  return items.map(item => ({ ...item, track: publicDiscoverTrack(item) }))
}
const rows = computed(() => withTracks(songs.value))
const latestRows = computed(() => withTracks(latestSongs.value))
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
  (latestRecommendations.value.length ? latestRecommendations.value : featuredSongs.value).slice(0, 7),
))
const dailySongs = computed(() => shelves.value?.today ?? [])
const cumulativeHotSongs = computed(() => dailySongs.value.length || editorialLoading.value
  ? [] : popularSongs.value.filter(item => (item.plays ?? 0) > 0).slice(0, 10))
const rankedSongs = computed(() => cumulativeHotSongs.value.length ? cumulativeHotSongs.value : dailySongs.value)
const rankingRows = computed(() => withTracks(rankedSongs.value))
const vocalRows = computed(() => [...(editorialAccess.value ? vocalPerformanceRows : []), ...withTracks(shelves.value?.vocal ?? [])])
const upcomingVocal = { title: '唯一的航线', cover: discoverSongArtwork[0].compact }
const ranking = computed(() => rankingRows.value.slice(0, 5))
const genreShelves = computed(() => discoverGenres.map(section => {
  const useEditorialCover = section.id === 'electronic' || section.id === 'rock' || section.id === 'jazz-soul'
  const publicRows = withTracks(shelves.value?.genres[section.id] ?? [])
  const publishedIds = new Set(publicRows.map(row => row.song.id))
  const examples = (editorialAccess.value ? previewTracks.value : [])
    .filter(track => section.samples.some(genre => track.filters.includes(genre))
      && (!track.publication || !publishedIds.has(track.publication.song.id)))
    .map((preview): DiscoverShelfTrack => {
      const track = withDiscoverPreviewCover(preview)
      return {
        track: useEditorialCover && !track.publication ? withDiscoverEditorialCover(track) : track,
        stageName: track.publication?.stageName ?? '',
        durationSeconds: track.durationSeconds,
        song: track.publication?.song,
        artistId: track.publication?.artistId,
        wideCoverPosition: section.id === 'global-pop' ? globalPopWideCoverPositions[track.id] : undefined,
      }
    })
  const jazzRelease = section.id === 'jazz-soul'
    ? publicRows.find(row => row.song.id === velvetHoursSongId) ?? velvetHours.value
    : undefined
  const editorial = editorialAccess.value ? (section.id === 'jazz-soul' ? discoverJazzRows : section.id === 'rock' ? discoverRockRows : []) : []
  const curated = jazzRelease ? [jazzRelease, ...editorial] : editorial
  const tracks = [...publicRows.filter(row => row.song.id !== jazzRelease?.song.id), ...examples]
  const square = [...curated, ...tracks.slice(0, 7)]
  const wide = section.hasWideRow ? [...tracks.slice(7), ...tracks.slice(0, 7)].slice(0, 4) : []
  return { ...section, square, wide }
}))
const walkedFarRow = vocalPerformanceRows.find(row => row.track.id === 'vocal-zou-le-na-me-yuan')!
const heroSlides = computed(() => {
  const streetlight = popularSongs.value.find(item => item.song.id === streetlightSongId) ?? supplementalHeroSong.value
  const mascot = popularSongs.value.find(item => item.song.id === mascotSongId) ?? mascotRelease.value
  const candidates = popularSongs.value.filter(item => !excludedRankedHeroIds.has(item.song.id))
  if (streetlight) candidates.splice(1, 0, streetlight)
  const rankedSlots = discoverSongArtwork.length - Number(Boolean(mascot)) - Number(editorialAccess.value)
  const releases = editorialAccess.value || candidates.length ? candidates : latestSongs.value.filter(item => !excludedRankedHeroIds.has(item.song.id))
  const ordered: DiscoverShelfTrack[] = withTracks(releases.slice(0, rankedSlots))
  if (mascot) {
    const track = publicTrack(mascot.artistId, mascot.song)
    ordered.splice(2, 0, { ...mascot, track: { ...track, publication: mascot } })
  }
  if (editorialAccess.value) ordered.splice(3, 0, prayerBeneathTheStarsRow)

  const slides: { row: DiscoverShelfTrack; artwork: HeroArtwork }[] = [
    ...(editorialAccess.value ? [{ row: walkedFarRow, artwork: { desktop: walkedFar, compact: walkedFar, position: '68% center' } }] : []),
    ...ordered.map((row, index) => {
      const artwork = row.track.id === mascotSongId ? jixiangwuHeroArtwork
        : row === prayerBeneathTheStarsRow ? prayerHeroArtwork
          : discoverSongArtwork[index]!
      return { row, artwork }
    }),
  ]
  const release = latestSongs.value.find(item => item.song.id === christmasSongId)
    ?? popularSongs.value.find(item => item.song.id === christmasSongId)
    ?? christmasTracks.value.find(track => track.id === christmasSongId)?.publication
  if (release) {
    const track = publicDiscoverTrack(release)
    slides.push({ row: { ...release, track }, artwork: { desktop: christmasSky, compact: christmasSky, position: '67% center' } })
  }
  return slides
})
const heroSlideCount = computed(() => heroSlides.value.length)
const heroIndex = computed(() => Math.max(0, heroSlides.value.findIndex(slide => slide.row.track.id === selectedHeroTrackId.value)))
const hero = computed(() => heroSlides.value[heroIndex.value]?.row)
const heroRows = computed(() => heroSlides.value.map(slide => slide.row))
function displayedHeroArtwork(artwork: HeroArtwork): HeroArtwork {
  const { desktop, fallback } = artwork
  if (!fallback || !unavailableHeroImages.value.has(desktop)) return artwork
  return { desktop: fallback, compact: fallback, position: artwork.position }
}
function useHeroImageFallback(artwork: HeroArtwork) {
  if (artwork.fallback) unavailableHeroImages.value.add(artwork.desktop)
}
const heroBackground = computed(() => hero.value && displayedHeroArtwork(heroSlides.value[heroIndex.value]!.artwork))
function heroIndexAt(step: number) {
  return (heroIndex.value + step + heroSlideCount.value) % heroSlideCount.value
}
const heroBefore = computed(() => displayedHeroArtwork(heroSlides.value[heroIndexAt(-1)]!.artwork))
const heroAfter = computed(() => displayedHeroArtwork(heroSlides.value[heroIndexAt(1)]!.artwork))
const heroTitleLength = computed(() => Array.from(hero.value?.track.title ?? '').length)
const visibleArtists = computed(() => {
  const found = artists.value ?? Array.from(new Map(latestSongs.value.map(song => [song.artistId, {
    artistId: song.artistId, stageName: song.stageName, avatarKey: null,
  }])).values())
  return showAllArtists.value ? found : found.slice(0, 3)
})
const playlists = computed<PreviewPlaylist[]>(() => editorialAccess.value ? [
  ...previewPlaylists.value.map(playlist => ({
    ...playlist, tracks: playlist.tracks.map(withDiscoverPreviewCover),
  })),
  {
    id: 'christmas', title: t('圣诞'), subtitle: 'CHRISTMAS', cover: christmasCover,
    tracks: christmasTracks.value,
  },
] : [])
const visiblePlaylists = computed(() => {
  const keyword = query.value.toLocaleLowerCase()
  return playlists.value.filter(playlist => [playlist.title, playlist.subtitle]
    .some(label => label.toLocaleLowerCase().includes(keyword)))
})
const playlistDetails = computed(() => playlists.value.find(playlist => playlist.id === selectedPlaylist.value))

async function loadChristmasPlaylist() {
  try {
    const artist = await getPublicArtist(christmasArtistId)
    if (disposed) return
    christmasTracks.value = christmasPlaylistSongIds.flatMap(id => {
      const song = artist.songs.find(item => item.id === id)
      if (!song) return []
      return [{
        ...publicDiscoverTrack({ artistId: christmasArtistId, stageName: artist.stageName, song }), authoredTitle: true,
        recipient: '', subtitle: artist.stageName, category: t('圣诞'), filters: ['christmas'],
        tags: [t('圣诞')], style: 'Christmas', occasion: '',
        durationSeconds: 0, description: '', quote: '',
      }]
    })
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
      firstPopularPending.value = false
      popularSongs.value = page.songs
      supplementalHeroSong.value = undefined
      if (!page.songs.some(item => item.song.id === streetlightSongId)) {
        const result = await listDiscoverSongs('latest', 0, '', 'Streetlight')
        if (active()) supplementalHeroSong.value = result.songs.find(item => item.song.id === streetlightSongId)
      }
    }).catch(() => { if (active()) firstPopularPending.value = false }),
    listDiscoverSongs('latest', 0, '', '吉祥物').then(page => {
      if (active()) mascotRelease.value = page.songs.find(item => item.song.id === mascotSongId)
    }).catch(() => { /* The ranked feed can still supply the release. */ }),
    listDiscoverSongs('latest', 0, '', 'Velvet Hours').then(page => {
      if (!active()) return
      const release = page.songs.find(item => item.song.id === velvetHoursSongId)
      velvetHours.value = release ? { ...release, track: publicDiscoverTrack(release) } : undefined
    }).catch(() => { /* The genre rows are still available if this lookup fails. */ }),
    loadShelves(),
  ])
  if (active()) editorialLoading.value = false
}

function changeHero(direction: number) {
  selectedHeroTrackId.value = heroSlides.value[heroIndexAt(direction)]!.row.track.id
}
function restartHeroRotation() {
  clearInterval(heroTimer)
  heroTimer = undefined
  if (disposed || inactive || heroSlideCount.value < 2 || heroPaused.value || heroHovered.value || heroFocused.value
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

async function loadInvitedEditorial() {
  if (props.publicEntry) {
    try {
      const status = await readInviteStatus()
      if (disposed || !status.admitted) return
      editorialAccess.value = true
    } catch {
      return
    } finally {
      checkingEditorialAccess.value = false
    }
  }
  await loadChristmasPlaylist()
}

onMounted(() => { initializeHeroRotation(); void loadInvitedEditorial(); void loadEditorial() })
onActivated(() => { inactive = false; restartHeroRotation() })
onDeactivated(() => { inactive = true; heroHovered.value = false; heroFocused.value = false; restartHeroRotation() })
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
        <RouterLink class="discover-brand" :to="discoverHref === '/en/discover' ? '/en/' : '/'" :aria-label="t('Cheerful Music 青风音乐首页')"><BrandLogo /></RouterLink>
        <nav class="discover-nav" :aria-label="t('主导航')">
          <RouterLink :to="discoverHref === '/en/discover' ? '/en/' : '/'">{{ t('首页') }}</RouterLink>
          <RouterLink :to="discoverHref" aria-current="page">{{ t('广场') }}</RouterLink>
          <a href="#discover-artists">{{ t('歌手') }}</a>
          <a v-if="editorialAccess" href="#discover-playlists">{{ t('歌单') }}</a>
        </nav>
        <form class="discover-search" role="search" @submit.prevent="submitSearch">
          <button type="submit" class="discover-search-submit" :aria-label="t('搜索')"><NavIcon name="search" /></button>
          <input v-model="search" type="search" maxlength="100" :placeholder="t('搜索歌曲、歌手或歌单')"
            :aria-label="t('搜索歌曲、歌手或歌单')" @search="!search && query && submitSearch">
        </form>
        <div class="discover-header-actions">
          <LanguageSwitcher />
          <RouterLink class="discover-create" to="/create">{{ t('开始创作') }}</RouterLink>
          <RouterLink class="discover-login" :to="signedIn ? '/profile' : '/account'">{{ t(signedIn ? '我的资料' : '登录/注册') }}</RouterLink>
        </div>
      </div>
    </header>

    <div class="discover-width discover-body">
      <h1 class="discover-page-heading">{{ discoverSeo[locale].heading }}</h1>
      <section v-if="!waitingForEntry && hero && heroBackground" class="discover-hero" :aria-label="t('广场推荐')" :aria-roledescription="t('轮播')"
        @mouseenter="heroHovered = true" @mouseleave="heroHovered = false" @focusin="heroFocused = true" @focusout="heroFocusOut">
        <div v-if="heroSlideCount > 1" class="discover-hero-preview discover-hero-preview-before">
          <picture>
            <source media="(min-width: 521px)" :srcset="heroBefore.compact">
            <img :style="{ objectPosition: heroBefore.position }" alt="" loading="lazy" decoding="async" fetchpriority="low"
              @error="useHeroImageFallback(heroBefore)">
          </picture>
        </div>
        <div class="discover-hero-main" :class="{ 'is-single': heroSlideCount === 1 && !firstPopularPending, 'has-portrait': hero.track.id === mascotSongId }">
          <picture>
            <source media="(max-width: 760px)" :srcset="heroBackground.compact">
            <img class="discover-hero-background" :src="heroBackground.desktop"
              :style="{ objectPosition: heroBackground.position }" alt="" width="1536" height="512" fetchpriority="high"
              @error="useHeroImageFallback(heroBackground)">
          </picture>
          <div class="discover-hero-content">
            <h2 :class="{ 'is-long-title': heroTitleLength > 10, 'is-very-long-title': heroTitleLength > 32 }" :title="hero.track.title"><RouterLink v-if="hero.song && hero.publishedAt" class="discover-song-title-link" :to="discoverSongHref(hero.song)">{{ hero.track.title }}</RouterLink><template v-else>{{ hero.track.title }}</template></h2>
            <RouterLink v-if="hero.artistId && hero.stageName" class="discover-hero-artist" :to="{ name: 'artist-home', params: { id: hero.artistId } }">{{ hero.stageName }}</RouterLink>
            <button type="button" class="discover-hero-listen" :aria-label="t(playing(hero.track) ? '暂停 {title}' : '试听 {title}', { title: hero.track.title })" @click="play(hero, heroRows)"><PlaybackIcon :playing="playing(hero.track)" />{{ t(playing(hero.track) ? '暂停' : '立即聆听') }}</button>
          </div>
        </div>
        <div v-if="heroSlideCount > 1" class="discover-hero-preview discover-hero-preview-after">
          <picture>
            <source media="(min-width: 521px)" :srcset="heroAfter.compact">
            <img :style="{ objectPosition: heroAfter.position }" alt="" loading="lazy" decoding="async" fetchpriority="low"
              @error="useHeroImageFallback(heroAfter)">
          </picture>
        </div>
        <template v-if="heroSlideCount > 1">
          <button class="discover-hero-previous" type="button" :aria-label="t('上一张推荐')" @click="changeHero(-1)">‹</button>
          <button class="discover-hero-next" type="button" :aria-label="t('下一张推荐')" @click="changeHero(1)">›</button>
          <div class="discover-hero-controls">
            <div class="discover-hero-dots" role="group" :aria-label="t('切换推荐')">
              <button v-for="(row, index) in heroRows" :key="row.track.id" type="button"
                :aria-label="t('查看推荐：{title}', { title: row.track.title })" :aria-pressed="heroIndex === index"
                @click="selectedHeroTrackId = row.track.id"></button>
            </div>
            <button class="discover-hero-rotation" type="button" :aria-label="t(heroPaused ? '继续轮播' : '暂停轮播')" :aria-pressed="heroPaused" @click="heroPaused = !heroPaused"><PlaybackIcon :playing="!heroPaused" /></button>
          </div>
        </template>
      </section>
      <div v-else class="discover-hero discover-introduction">
        <div class="discover-intro-copy">
          <strong>{{ discoverSeo[locale].heading }}</strong>
          <p>{{ discoverSeo[locale].description }}</p>
          <RouterLink v-if="latestSongs[0]" :to="discoverSongHref(latestSongs[0].song)">{{ latestSongs[0].song.title }}</RouterLink>
        </div>
      </div>
    </div>

    <div v-if="!waitingForEntry || browseOpen" class="discover-width discover-content">
      <section class="discover-main" :aria-label="t('音乐广场')">
        <section v-if="editorialAccess" id="discover-playlists" class="discover-playlists" aria-labelledby="discover-playlists-title">
          <header class="discover-section-heading">
            <h2 id="discover-playlists-title">{{ t('推荐歌单') }}</h2>
          </header>
          <ul v-if="visiblePlaylists.length" class="discover-playlist-tiles">
            <li v-for="playlist in visiblePlaylists" :key="playlist.id"><button type="button"
              :class="{ 'discover-playlist-christmas': playlist.id === 'christmas' }"
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
            <DiscoverShelf :label="t('真人演唱')" :rows="vocalRows" :upcoming="editorialAccess ? upcomingVocal : undefined" variant="wide"
              :expanded="expanded === 'vocal'" @play="play($event, vocalRows)" />
          </section>
          <section id="discover-today" class="discover-shelf">
            <header class="discover-section-heading">
              <div class="discover-shelf-heading"><h2>{{ t('今日热歌') }}</h2><p>{{ t(cumulativeHotSongs.length ? '暂按累计播放量排序' : '按当日播放量排序') }}</p></div>
              <button v-if="rankingRows.length" type="button" :aria-expanded="expanded === 'today'"
                @click="expanded = expanded === 'today' ? undefined : 'today'">{{ t(expanded === 'today' ? '收起' : '查看全部') }} ›</button>
            </header>
            <DiscoverShelf :label="t('今日热歌')" :rows="rankingRows" variant="square" :loading="editorialLoading" :failed="shelvesFailed"
              :empty="t(shelves?.listeningDataAvailable ? '今天还没有播放记录。' : '今日播放排行暂不可用。')"
              :expanded="expanded === 'today'" show-plays @retry="loadShelves" @play="play($event, rankingRows)" />
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
              <div class="discover-ranking-row">
                <button type="button" class="discover-ranking-play" :aria-label="t(playing(row.track) ? '暂停 {title}' : '试听 {title}', { title: row.song.title })" @click="play(row, rankingRows)"></button>
                <span class="discover-rank" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
                <span v-if="index === 0" class="discover-ranking-cover"><img :src="row.track.cover" alt="" width="42" height="42" loading="lazy"></span>
                <span class="discover-ranking-info">
                  <strong><RouterLink class="discover-song-title-link" :to="discoverSongHref(row.song)">{{ row.song.title }}</RouterLink></strong>
                  <RouterLink v-if="row.artistId && row.stageName" :to="{ name: 'artist-home', params: { id: row.artistId } }">{{ row.stageName }}</RouterLink>
                  <span v-else-if="row.stageName">{{ row.stageName }}</span>
                </span>
                <span v-if="row.plays !== undefined" class="discover-ranking-count">{{ count.format(row.plays) }}</span>
              </div>
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
