import {computed} from 'vue'
import {t} from '../../../shared/i18n'
import {usePreviewTracks,type PreviewTrack} from './tracks'

export interface PreviewPlaylist {id:string;title:string;subtitle:string;cover:string;bannerPosition?:string;tracks:PreviewTrack[]}
export const playlistDefinitions=[
  {id:'mandopop',title:'华语流行',subtitle:'MANDOPOP',cover:'/media/explore-love-v1.webp',bannerPosition:'0%',trackIds:['love','family','red-guofeng']},
  {id:'electronic',title:'电子',subtitle:'ELECTRONIC',cover:'/media/explore-electronic-v1.jpg',bannerPosition:'33.333333%',trackIds:['hometown-electronic','rain-disco']},
  {id:'global-pop',title:'欧美流行',subtitle:'GLOBAL POP',cover:'/media/explore-travel-v1.webp',bannerPosition:'66.666667%',trackIds:[]},
  {id:'indie-soul',title:'独立 / R&B',subtitle:'INDIE & SOUL',cover:'/media/explore-rock-v1.jpg',bannerPosition:'100%',trackIds:['tomorrow-rock','scenery-folk','travel','river-instrumental']},
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
