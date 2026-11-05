import type { PlayableTrack } from '../../../shared/player/store'
import { computed } from 'vue'
import { t } from '../../../shared/i18n'
export interface PreviewTrack extends PlayableTrack { recipient: string; subtitle: string; style: string; occasion: string; description: string; quote: string; category: string; durationSeconds: number }
export const tracks: PreviewTrack[] = [
  { id: 'love', title: '把平凡唱给你', recipient: '爱人', subtitle: '写给相伴的爱人', category: '爱情', style: '爱情主题', occasion: '恋爱纪念', cover: '/media/explore-love-v1.webp', audio: '/media/sample-love-v2.mp3', durationSeconds: 180.921333, provenance: 'SAMPLE', description: '把喜欢与陪伴，留在一首歌里。', quote: '想和你，把平凡的日子慢慢过成爱。' },
  { id: 'family', title: '留一盏灯等你', recipient: '家人', subtitle: '写给牵挂你的家人', category: '家庭', style: '家庭与陪伴', occasion: '送给家人', cover: '/media/explore-family-v1.webp', audio: '/media/sample-family-v2.mp3', durationSeconds: 162.507750, provenance: 'SAMPLE', description: '那些日常里的牵挂，值得被认真唱出来。', quote: '走得再远，心里总有一盏回家的灯。' },
  { id: 'travel', title: '去有海风的地方', recipient: '自己', subtitle: '写给向往自由的自己', category: '自由旅行', style: '自由随性 · 大海旅行', occasion: '送给自己', cover: '/media/explore-travel-v1.webp', audio: '/media/sample-ocean-travel-v2.mp3', durationSeconds: 206.571200, provenance: 'SAMPLE', description: '跟着海风出发，把自由唱给自己。', quote: '下一站，去有海风的地方。' },
]

// Only these built-in demos are translated. User-created music keeps its authored title.
export function usePreviewTracks() {
  return computed(() => tracks.map(track => ({ ...track, titleKey: track.title,
    title: t(track.title), recipient: t(track.recipient), subtitle: t(track.subtitle),
    style: t(track.style), description: t(track.description), quote: t(track.quote), category: t(track.category),
  })))
}
