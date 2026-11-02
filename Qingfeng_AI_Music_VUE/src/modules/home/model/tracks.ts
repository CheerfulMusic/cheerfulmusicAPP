import type { PlayableTrack } from '../../../shared/player/store'
import { computed } from 'vue'
import { t } from '../../../shared/i18n'
export interface PreviewTrack extends PlayableTrack { recipient: string; subtitle: string; style: string; occasion: string; description: string; quote: string }
export const tracks: PreviewTrack[] = [
  { id: 'sea', title: '下次，一起去看海', recipient: '小雨', subtitle: '写给爱人的一首歌', style: '温柔民谣', occasion: '恋爱纪念', cover: '/media/couple-editorial.png', audio: '/media/sea.wav', description: '那些没说出口的喜欢，都藏在旋律里。', quote: '想把往后的每一个日落，都留给你。' },
  { id: 'home', title: '回家的那阵风', recipient: '妈妈', subtitle: '写给妈妈的一首歌', style: '温暖钢琴', occasion: '送给家人', cover: '/media/family-editorial.png', audio: '/media/home.wav', description: '走过再远的路，最想念的还是家。', quote: '长大以后才懂，你的平凡有多珍贵。' },
  { id: 'summer', title: '夏天不散场', recipient: '老朋友', subtitle: '写给朋友的一首歌', style: '轻快器乐', occasion: '朋友毕业', cover: '/media/friends-editorial.png', audio: '/media/summer.wav', description: '各自去远方，我们的故事还很长。', quote: '这一站说再见，下一站再并肩。' },
]

// Only these built-in demos are translated. User-created music keeps its authored title.
export function usePreviewTracks() {
  return computed(() => tracks.map(track => ({ ...track, titleKey: track.title,
    title: t(track.title), recipient: t(track.recipient), subtitle: t(track.subtitle),
    style: t(track.style), description: t(track.description), quote: t(track.quote),
  })))
}
