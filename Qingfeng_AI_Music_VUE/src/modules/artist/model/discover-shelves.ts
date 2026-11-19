import type { PlayableTrack } from '../../../shared/player/store'
import type { DiscoverSong, PublishedSong } from '../api/artist'
import cover1 from '../../../assets/discover/editorial/cover-01.webp'
import cover2 from '../../../assets/discover/editorial/cover-02.webp'
import cover3 from '../../../assets/discover/editorial/cover-03.webp'
import cover4 from '../../../assets/discover/editorial/cover-04.webp'
import cover5 from '../../../assets/discover/editorial/cover-05.webp'
import cover6 from '../../../assets/discover/editorial/cover-06.webp'
import cover7 from '../../../assets/discover/editorial/cover-07.webp'
import cover8 from '../../../assets/discover/editorial/cover-08.webp'
import cover9 from '../../../assets/discover/editorial/cover-09.webp'
import cover10 from '../../../assets/discover/editorial/cover-10.webp'
import cover11 from '../../../assets/discover/editorial/cover-11.webp'
import cover12 from '../../../assets/discover/editorial/cover-12.webp'
import cover13 from '../../../assets/discover/editorial/cover-13.webp'
import cover14 from '../../../assets/discover/editorial/cover-14.webp'
import cover15 from '../../../assets/discover/editorial/cover-15.webp'
import cover16 from '../../../assets/discover/editorial/cover-16.webp'
import cover17 from '../../../assets/discover/editorial/cover-17.webp'
import cover18 from '../../../assets/discover/editorial/cover-18.webp'
import cover19 from '../../../assets/discover/editorial/cover-19.webp'
import cover20 from '../../../assets/discover/editorial/cover-20.webp'
import cover21 from '../../../assets/discover/editorial/cover-21.webp'
import cover22 from '../../../assets/discover/editorial/cover-22.webp'
import cover23 from '../../../assets/discover/editorial/cover-23.webp'
import cover24 from '../../../assets/discover/editorial/cover-24.webp'
import cover25 from '../../../assets/discover/editorial/cover-25.webp'
import cover26 from '../../../assets/discover/editorial/cover-26.webp'
import cover27 from '../../../assets/discover/editorial/cover-27.webp'
import cover28 from '../../../assets/discover/editorial/cover-28.webp'
import cover29 from '../../../assets/discover/editorial/cover-29.webp'
import cover30 from '../../../assets/discover/editorial/cover-30.webp'
import cover31 from '../../../assets/discover/editorial/cover-31.webp'
import cover32 from '../../../assets/discover/editorial/cover-32.webp'
import cover33 from '../../../assets/discover/editorial/cover-33.webp'
import cover34 from '../../../assets/discover/editorial/cover-34.webp'
import cover35 from '../../../assets/discover/editorial/cover-35.webp'
import cover36 from '../../../assets/discover/editorial/cover-36.webp'
import vocal1 from '../../../assets/discover/editorial/vocal-1.webp'
import vocal2 from '../../../assets/discover/editorial/vocal-2.webp'
import vocal3 from '../../../assets/discover/editorial/vocal-3.webp'
import vocal4 from '../../../assets/discover/editorial/vocal-4.webp'

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
}

export type PublishedShelfTrack = DiscoverSong & { track: PlayableTrack }

const covers = [
  cover1, cover2, cover3, cover4, cover5, cover6, cover7, cover8, cover9, cover10, cover11, cover12,
  cover13, cover14, cover15, cover16, cover17, cover18, cover19, cover20, cover21, cover22, cover23, cover24,
  cover25, cover26, cover27, cover28, cover29, cover30, cover31, cover32, cover33, cover34, cover35, cover36,
]
const artwork = {
  latest: [5,15,14,4,13,3,16,8,17,31,18,23,9,12,2,32,20,34,25,2,35,10,27,28,7,30,1,33,29,11,19,26],
  featured: [7,6,9,10,11,12,17],
  today: [13,14,15,16,17,18,10],
  electronic: [19,20,21,22,23,24,6],
  'electronic-wide': [20,22,23,19],
  'global-pop': [25,26,27,28,29,30,1],
  'global-pop-wide': [29,27,30,26],
  folk: [31,32,7,3,1,34,4],
  rock: [33,8,24,28,6,14,9],
  'jazz-soul': [36,34,6,35,13,27,30],
} as const

export function shelfCover(shelf: keyof typeof artwork | 'vocal', index: number) {
  if (shelf === 'vocal') {
    const photos = [vocal1, vocal2, vocal3, vocal4]
    return photos[index % photos.length]!
  }
  const palette = artwork[shelf]
  return covers[palette[index % palette.length]! - 1]!
}

export const discoverPlaylistArt = { disco: cover29, house: cover19, folk: cover31 }
