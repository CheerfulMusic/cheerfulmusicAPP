import christmasFeelsLikeHomeAudio from '../../../assets/discover/jazz/christmas-feels-like-home.mp3'
import christmasFeelsLikeHomeCover from '../../../assets/discover/jazz/christmas-feels-like-home.webp'
import prayerBeneathTheStarsAudio from '../../../assets/discover/jazz/prayer-beneath-the-stars.mp3'
import prayerBeneathTheStarsCover from '../../../assets/discover/jazz/prayer-beneath-the-stars.webp'
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
