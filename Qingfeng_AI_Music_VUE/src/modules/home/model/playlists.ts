import {computed} from 'vue'
import {t} from '../../../shared/i18n'
import {usePreviewTracks,type PreviewTrack} from './tracks'

export interface PreviewPlaylist {id:string;title:string;subtitle:string;cover:string;tracks:PreviewTrack[]}
export const playlistDefinitions=[
  {id:'heart',title:'听见心意',subtitle:'爱情 流行 古风',cover:'/media/explore-love-v1.webp',trackIds:['love','family','red-guofeng']},
  {id:'journey',title:'自由与远方',subtitle:'旅行 民谣 纯音乐',cover:'/media/explore-travel-v1.webp',trackIds:['travel','scenery-folk','river-instrumental']},
  {id:'rhythm',title:'跟着节奏出发',subtitle:'Disco 摇滚 电子',cover:'/media/explore-disco-v1.jpg',trackIds:['rain-disco','tomorrow-rock','hometown-electronic']},
]

export function filterPlaylists(playlists:readonly PreviewPlaylist[],filter:string):PreviewPlaylist[]{
  if(filter==='all')return [...playlists]
  return playlists.map(playlist=>({...playlist,tracks:playlist.tracks.filter(track=>track.filters.includes(filter))})).filter(playlist=>playlist.tracks.length>0)
}

export function usePreviewPlaylists(){
  const tracks=usePreviewTracks()
  return computed<PreviewPlaylist[]>(()=>playlistDefinitions.map(playlist=>({
    id:playlist.id,title:t(playlist.title),subtitle:t(playlist.subtitle),cover:playlist.cover,
    tracks:playlist.trackIds.flatMap(id=>{const track=tracks.value.find(item=>item.id===id);return track?[track]:[]}),
  })))
}
