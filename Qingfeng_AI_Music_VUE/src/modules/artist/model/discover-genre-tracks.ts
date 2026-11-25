import discoverMedia from './discover-media.json'
import type { DiscoverShelfTrack } from './discover-shelves'

export const prayerBeneathTheStarsRow: DiscoverShelfTrack = {
  track: {
    id: 'prayer-beneath-the-stars',
    title: 'Prayer Beneath the Stars',
    ...discoverMedia['prayer-beneath-the-stars'],
    provenance: 'IMPORTED',
  },
  stageName: 'aug1566',
  durationSeconds: 178.22325,
}

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
  prayerBeneathTheStarsRow,
  {
    track: {
      id: 'zui-hua-jazz',
      title: '醉花 Jazz',
      ...discoverMedia['zui-hua-jazz'],
      provenance: 'IMPORTED',
    },
    stageName: 'aug1566',
    durationSeconds: 237.56,
  },
]

export const discoverRockRows: DiscoverShelfTrack[] = [
  {
    track: {
      id: 'cardboard-corners',
      title: 'Cardboard Corners',
      ...discoverMedia['cardboard-corners'],
      provenance: 'IMPORTED',
    },
    stageName: 'Ace007',
    durationSeconds: 171.998667,
  },
  {
    track: {
      id: 'nothing-left-to-lose',
      title: 'NOTHING LEFT TO LOSE',
      ...discoverMedia['nothing-left-to-lose'],
      provenance: 'IMPORTED',
    },
    stageName: 'newlawsmaker',
    durationSeconds: 134.76,
  },
  {
    track: {
      id: 'your-side-of-the-tape',
      title: 'Your Side of the Tape',
      ...discoverMedia['your-side-of-the-tape'],
      provenance: 'IMPORTED',
    },
    stageName: 'newlawsmaker',
    durationSeconds: 145.2,
  },
  {
    track: {
      id: 'bicycle-weather',
      title: 'Bicycle Weather',
      ...discoverMedia['bicycle-weather'],
      provenance: 'IMPORTED',
    },
    stageName: 'Ace007',
    durationSeconds: 197.758667,
  },
  {
    track: {
      id: 'southbank-under-rain',
      title: 'Southbank Under Rain',
      ...discoverMedia['southbank-under-rain'],
      provenance: 'IMPORTED',
    },
    stageName: 'Ace007',
    durationSeconds: 277.198667,
  },
  {
    track: {
      id: 'town-hall-steps',
      title: 'Town Hall Steps',
      ...discoverMedia['town-hall-steps'],
      provenance: 'IMPORTED',
    },
    stageName: 'Ace007',
    durationSeconds: 217.998667,
  },
]
