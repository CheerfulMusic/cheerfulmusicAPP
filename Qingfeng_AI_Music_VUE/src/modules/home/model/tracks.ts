import type { PlayableTrack } from '../../../shared/player/store'
import { computed } from 'vue'
import { t } from '../../../shared/i18n'
export interface PreviewTrack extends PlayableTrack { recipient: string; subtitle: string; style: string; occasion: string; description: string; quote: string; category: string; durationSeconds: number; filters: string[]; tags: string[]; authoredTitle?: boolean; genre?: string }
export const previewFilters = [
  {id:'love',label:'爱情'},{id:'family',label:'家庭'},{id:'travel',label:'自由旅行'},
  {id:'guofeng',label:'古风'},{id:'disco',label:'Disco'},{id:'rock',label:'摇滚'},
  {id:'pop',label:'流行'},{id:'folk',label:'民谣'},{id:'electronic',label:'电子'},{id:'instrumental',label:'纯音乐'},
]
export const tracks: PreviewTrack[] = [
  { id: 'love', title: '把平凡唱给你', recipient: '爱人', subtitle: '写给相伴的爱人', category: '爱情', filters:['love'], tags:['爱情','温柔陪伴'], style: '爱情主题', occasion: '恋爱纪念', cover: '/media/explore-love-v1.webp', audio: '/media/sample-love-v2.mp3', durationSeconds: 180.921333, provenance: 'SAMPLE', description: '把喜欢与陪伴，留在一首歌里。', quote: '想和你，把平凡的日子慢慢过成爱。' },
  // 桌面“走了那么远（流行）”与“家庭范例歌”字节相同，只展示一次并归入两个筛选。
  { id: 'family', title: '走了那么远', authoredTitle:true, recipient: '家人', subtitle: '写给牵挂你的家人', category: '家庭', genre:'流行', filters:['family','pop'], tags:['流行','家庭'], style: '抒情流行', occasion: '送给家人', cover: '/media/explore-family-v1.webp', audio: '/media/sample-family-v2.mp3', durationSeconds: 162.507750, provenance: 'SAMPLE', description: '那些日常里的牵挂，值得被认真唱出来。', quote: '' },
  { id: 'travel', title: '去有海风的地方', recipient: '自己', subtitle: '写给向往自由的自己', category: '自由旅行', filters:['travel'], tags:['旅行','自由随性'], style: '自由随性 · 大海旅行', occasion: '送给自己', cover: '/media/explore-travel-v1.webp', audio: '/media/sample-ocean-travel-v2.mp3', durationSeconds: 206.571200, provenance: 'SAMPLE', description: '跟着海风出发，把自由唱给自己。', quote: '下一站，去有海风的地方。' },
  { id:'red-guofeng',title:'红',authoredTitle:true,recipient:'',subtitle:'古风作品试听',category:'古风',filters:['guofeng'],tags:['古风','东方意境'],style:'国风古韵',occasion:'',cover:'/media/explore-guofeng-v1.svg',audio:'/media/sample-red-guofeng-v1.mp3',durationSeconds:200.955200,provenance:'SAMPLE',description:'在东方意境里，感受古风音乐的韵味。',quote:'' },
  { id:'rain-disco',title:'忽然下起雨',authoredTitle:true,recipient:'',subtitle:'Disco作品试听',category:'Disco',filters:['disco'],tags:['Disco','复古律动'],style:'Disco舞曲',occasion:'',cover:'/media/explore-disco-v1.jpg',audio:'/media/sample-rain-disco-v1.mp3',durationSeconds:94.759979,provenance:'SAMPLE',description:'跟着复古律动，换一种方式听见雨天。',quote:'' },
  { id:'tomorrow-rock',title:'明天再说',authoredTitle:true,recipient:'',subtitle:'摇滚作品试听',category:'摇滚',filters:['rock'],tags:['摇滚','自由态度'],style:'独立摇滚',occasion:'',cover:'/media/explore-rock-v1.jpg',audio:'/media/sample-tomorrow-rock-v1.mp3',durationSeconds:217.200000,provenance:'SAMPLE',description:'用摇滚表达态度，让情绪跟随节奏释放。',quote:'' },
  { id:'scenery-folk',title:'最美的风景',authoredTitle:true,recipient:'',subtitle:'民谣作品试听',category:'民谣',filters:['folk'],tags:['民谣','沿途风景'],style:'温暖民谣',occasion:'',cover:'/media/explore-family-v1.webp',audio:'/media/sample-scenery-folk-v1.mp3',durationSeconds:110.832000,provenance:'SAMPLE',description:'用自然朴素的民谣，收藏沿途最美的风景。',quote:'' },
  { id:'hometown-electronic',title:'hometown lover',authoredTitle:true,recipient:'',subtitle:'电子作品试听',category:'电子',filters:['electronic'],tags:['电子','城市律动'],style:'梦幻电子',occasion:'',cover:'/media/explore-electronic-v1.jpg',audio:'/media/sample-hometown-electronic-v1.mp3',durationSeconds:112.759979,provenance:'SAMPLE',description:'在电子律动里，寻找关于故乡与爱的想象。',quote:'' },
  { id:'river-instrumental',title:'When the River Breathes',authoredTitle:true,recipient:'',subtitle:'纯音乐作品试听',category:'纯音乐',filters:['instrumental'],tags:['纯音乐','静心放松'],style:'空灵氛围',occasion:'',cover:'/media/explore-instrumental-v1.jpg',audio:'/media/sample-river-instrumental-v1.mp3',durationSeconds:197.999979,provenance:'SAMPLE',description:'让旋律像河流般舒展，留一段安静聆听的时间。',quote:'' },
]

// Only these built-in demos are translated. User-created music keeps its authored title.
export function usePreviewTracks() {
  return computed(() => tracks.map(track => ({ ...track, titleKey: track.authoredTitle?undefined:track.title,
    title: track.authoredTitle?track.title:t(track.title), recipient: t(track.recipient), subtitle: t(track.subtitle),tags:track.tags.map(tag=>t(tag)),
    style: t(track.style), description: t(track.description), quote: t(track.quote), category: t(track.category),genre:t(track.genre||track.category),
  })))
}
