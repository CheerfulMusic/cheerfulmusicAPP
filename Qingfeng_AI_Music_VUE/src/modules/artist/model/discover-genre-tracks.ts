import christmasFeelsLikeHomeAudio from '../../../assets/discover/jazz/christmas-feels-like-home.mp3'
import christmasFeelsLikeHomeCover from '../../../assets/discover/jazz/christmas-feels-like-home.webp'
import prayerBeneathTheStarsAudio from '../../../assets/discover/jazz/prayer-beneath-the-stars.mp3'
import prayerBeneathTheStarsCover from '../../../assets/discover/jazz/prayer-beneath-the-stars.webp'
import cardboardCornersAudio from '../../../assets/discover/rock/cardboard-corners.mp3'
import cardboardCornersCover from '../../../assets/discover/rock/cardboard-corners.webp'
import type { DiscoverShelfTrack } from './discover-shelves'

export const discoverJazzRows: DiscoverShelfTrack[] = [
  {
    track: {
      id: 'christmas-feels-like-home',
      title: 'CHRISTMAS FEELS LIKE HOME',
      audio: christmasFeelsLikeHomeAudio,
      cover: christmasFeelsLikeHomeCover,
      provenance: 'IMPORTED',
    },
    stageName: 'newlawsmaker',
    durationSeconds: 208.4,
  },
  {
    track: {
      id: 'prayer-beneath-the-stars',
      title: 'Prayer Beneath the Stars',
      audio: prayerBeneathTheStarsAudio,
      cover: prayerBeneathTheStarsCover,
      provenance: 'IMPORTED',
    },
    stageName: 'aug1566',
    durationSeconds: 178.22325,
  },
]

export const cardboardCornersRow: DiscoverShelfTrack = {
  track: {
    id: 'cardboard-corners',
    title: 'Cardboard Corners',
    audio: cardboardCornersAudio,
    cover: cardboardCornersCover,
    provenance: 'IMPORTED',
  },
  stageName: 'Ace007',
  durationSeconds: 171.998667,
}
