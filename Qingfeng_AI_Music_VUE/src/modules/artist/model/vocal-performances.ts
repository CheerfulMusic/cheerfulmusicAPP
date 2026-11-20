import type { DiscoverShelfTrack } from './discover-shelves'

const media = '/media/recommendations'
// These recordings have their own versions and do not reuse the AI sample IDs or lyric timelines.
const performances = [
  { id: 'hu-ran-xia-qi-yu', title: '忽然下起雨', audioHash: 'b6f3f97e3182', coverHash: '8f41f4f0460a', durationSeconds: 157.14 },
  { id: 'liu-lang-shi-ren', title: '流浪诗人', audioHash: '58376af9823c', coverHash: 'e187026fea88', durationSeconds: 203.81 },
  { id: 'ming-tian-zai-shuo', title: '明天再说', audioHash: '5fb7dd7a316d', coverHash: 'abb350e340c7', durationSeconds: 193.22 },
  { id: 'zou-le-na-me-yuan', title: '走了那么远', audioHash: '5ecdb2d00d7a', coverHash: '4e668639a1a1', durationSeconds: 171.69 },
]

export const vocalPerformanceRows: DiscoverShelfTrack[] = performances.map(({ id, title, audioHash, coverHash, durationSeconds }) => {
  const trackId = `vocal-${id}`
  return {
    track: {
      id: trackId,
      title,
      audio: `${media}/${trackId}-${audioHash}-preview-128.mp3`,
      cover: `${media}/${trackId}-${coverHash}-cover.jpg`,
      provenance: 'IMPORTED',
    },
    stageName: '',
    durationSeconds,
  }
})
