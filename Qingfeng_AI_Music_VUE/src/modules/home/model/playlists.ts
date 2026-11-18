import {computed} from 'vue'
import {t} from '../../../shared/i18n'
import {usePreviewTracks,type PreviewTrack} from './tracks'

export interface PreviewPlaylist {id:string;title:string;subtitle:string;cover:string;bannerPosition?:string;tracks:PreviewTrack[]}
export const playlistDefinitions=[
  {id:'mandopop',title:'华语流行',subtitle:'MANDOPOP',cover:'/media/explore-love-v1.webp',bannerPosition:'0%',trackIds:['feng-ye-xing','ming-chui-si-hai','liu-lang-shi-ren','luo-yu-de-cao-chang','mei-jian-fu-yun','wu-ren-dai-ti','wu-ren-qu','wu-ye-zhi-shi']},
  {id:'electronic',title:'电子',subtitle:'ELECTRONIC',cover:'/media/explore-electronic-v1.jpg',bannerPosition:'33.333333%',trackIds:['meng-de-chi-bang']},
  {id:'global-pop',title:'欧美流行',subtitle:'GLOBAL POP',cover:'/media/explore-travel-v1.webp',bannerPosition:'66.666667%',trackIds:['after-midnight','if-you-wanna-feel-me','where-the-wind-goes']},
  {id:'house',title:'House',subtitle:'HOUSE',cover:'/media/explore-disco-v1.jpg',bannerPosition:'100%',trackIds:[]},
]

export function filterPlaylists(playlists:readonly PreviewPlaylist[],filter:string):PreviewPlaylist[]{
  if(filter==='all')return [...playlists]
  return playlists.map(playlist=>({...playlist,tracks:playlist.tracks.filter(track=>track.filters.includes(filter))})).filter(playlist=>playlist.tracks.length>0)
}

export function usePreviewPlaylists(){
  const tracks=usePreviewTracks()
  return computed<PreviewPlaylist[]>(()=>playlistDefinitions.map(playlist=>({
    id:playlist.id,title:t(playlist.title),subtitle:playlist.subtitle,cover:playlist.cover,bannerPosition:playlist.bannerPosition,
    tracks:playlist.trackIds.flatMap(id=>{const track=tracks.value.find(item=>item.id===id);return track?[track]:[]}),
  })))
}
