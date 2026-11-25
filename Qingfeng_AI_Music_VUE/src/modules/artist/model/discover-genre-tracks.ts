import discoverMedia from './discover-media.json'
import type { DiscoverShelfTrack } from './discover-shelves'

export const discoverJazzRows: DiscoverShelfTrack[] = [
  {
    track: {
      id: 'christmas-feels-like-home',
      title: 'CHRISTMAS FEELS LIKE HOME',
      ...discoverMedia['christmas-feels-like-home'],
      provenance: 'IMPORTED',
    },
    stageName: 'newlawsmaker',
    durationSeconds: 208.4,
  },
  {
    track: {
      id: 'prayer-beneath-the-stars',
      title: 'Prayer Beneath the Stars',
      ...discoverMedia['prayer-beneath-the-stars'],
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
    ...discoverMedia['cardboard-corners'],
    provenance: 'IMPORTED',
  },
  stageName: 'Ace007',
  durationSeconds: 171.998667,
}
