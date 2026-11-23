import type { PlayableTrack } from '../../../shared/player/store'
import type { DiscoverSong, PublishedSong } from '../api/artist'
import type { PreviewTrack } from '../../home/model/tracks'
import christmasSkyCover from '../../../assets/discover/christmas-sky-cover.webp'
import afterMidnight from '../../../assets/discover/song-covers/after-midnight.webp'
import ifYouWannaFeelMe from '../../../assets/discover/song-covers/if-you-wanna-feel-me.webp'
import whereTheWindGoes from '../../../assets/discover/song-covers/where-the-wind-goes.webp'
import cantStopThisFeeling from '../../../assets/discover/song-covers/cant-stop-this-feeling.webp'
import midnightCall from '../../../assets/discover/song-covers/midnight-call.webp'
import summerOnRepeat from '../../../assets/discover/song-covers/summer-on-repeat.webp'
import crossroads from '../../../assets/discover/song-covers/lu-kou-xiang-feng.webp'
import station from '../../../assets/discover/song-covers/wan-feng-che-zhan.webp'
import canvasShoes from '../../../assets/discover/song-covers/fan-bu-xie-de-lv-xing.webp'
import signedInInk from '../../../assets/discover/song-covers/signed-in-ink.webp'
import rainUnderEaves from '../../../assets/discover/song-covers/yan-xia-ting-yu.webp'
import oldBicycle from '../../../assets/discover/song-covers/jiu-dan-che.webp'
import alleyLight from '../../../assets/discover/song-covers/jiu-xiang-deng.webp'

export const discoverGenres = [
  { id: 'electronic', label: '电子风格', samples: ['electronic'], hasWideRow: true },
  { id: 'global-pop', label: '欧美流行', samples: ['global-pop'], hasWideRow: true },
  { id: 'folk', label: '华语民谣', samples: ['folk'], hasWideRow: false },
  { id: 'rock', label: '独立摇滚', samples: ['rock', 'indie'], hasWideRow: false },
  { id: 'jazz-soul', label: '爵士与灵魂乐', samples: ['jazz', 'rnb'], hasWideRow: false },
] as const

export type DiscoverGenre = (typeof discoverGenres)[number]['id']

export interface DiscoverShelfTrack {
  track: PlayableTrack
  song?: PublishedSong
  artistId?: string
  stageName: string
  plays?: number
  durationSeconds?: number
  wideCoverPosition?: string
}

export type PublishedShelfTrack = DiscoverSong & { track: PlayableTrack }

export const christmasSongId = 'd4b1ec5a-e68f-43e6-8624-226981ecef5c'

const releaseCoverFallbacks: Partial<Record<string, string>> = {
  '0a67006e-997d-49b1-a162-a1a02f424c91': crossroads,
  '21282ee4-0645-4a38-aa08-3ba3b79be197': station,
  '6b734c5a-de1b-414b-a81f-c9a6135f2853': canvasShoes,
  '8e4eb9c1-3bcf-4410-aa1e-1d5e523e2b89': signedInInk,
  'cba0c60a-6724-4dc3-b683-f8415dc02882': rainUnderEaves,
  '5dd66758-5354-431a-ba6a-ebcd5ceaa699': oldBicycle,
  '9d1f9ba6-af67-4844-addc-5e578b579328': alleyLight,
}

const sampleCovers: Partial<Record<string, string>> = {
  'after-midnight': afterMidnight,
  'if-you-wanna-feel-me': ifYouWannaFeelMe,
  'where-the-wind-goes': whereTheWindGoes,
  'cant-stop-this-feeling': cantStopThisFeeling,
  'midnight-call': midnightCall,
  'summer-on-repeat': summerOnRepeat,
}

export const globalPopWideCoverPositions: Partial<Record<string, string>> = {
  'after-midnight': 'center 26%',
  'where-the-wind-goes': 'center 17%',
  'cant-stop-this-feeling': 'center 15%',
  'midnight-call': 'center 26%',
  'summer-on-repeat': 'center 17%',
}

export function discoverReleaseCover(song: PublishedSong, original: string) {
  if (song.id === christmasSongId) return christmasSkyCover
  return song.hasCover ? original : releaseCoverFallbacks[song.id] ?? original
}

export function withDiscoverPreviewCover(track: PreviewTrack): PreviewTrack {
  const cover = track.publication
    ? discoverReleaseCover(track.publication.song, track.cover)
    : sampleCovers[track.id] ?? track.cover
  return cover === track.cover ? track : { ...track, cover }
}
