import {computed} from 'vue'
import {t} from '../../../shared/i18n'
import {usePreviewTracks,type PreviewTrack} from './tracks'
import {useHouseRecommendations} from './published-recommendations'
import mandopopCover from '../../../assets/discover/playlists/mandopop.webp'
import electronicCover from '../../../assets/discover/playlists/electronic.webp'
import globalPopCover from '../../../assets/discover/playlists/global-pop.webp'
import houseCover from '../../../assets/discover/playlists/house.webp'

export interface PreviewPlaylist {id:string;title:string;subtitle:string;cover:string;tracks:PreviewTrack[]}
const sharedPlaylists=[
  {id:'mandopop',title:'华语流行',subtitle:'MANDOPOP',cover:mandopopCover,trackIds:['feng-ye-xing','ming-chui-si-hai','liu-lang-shi-ren','luo-yu-de-cao-chang','mei-jian-fu-yun','wu-ren-dai-ti','wu-ren-qu','wu-ye-zhi-shi','love','red-guofeng','family','travel','river-instrumental','scenery-folk']},
  {id:'electronic',title:'电子',subtitle:'ELECTRONIC',cover:electronicCover,trackIds:['meng-de-chi-bang','all-night-one-more-time','euphoric-drop','i-run-this-show','light-it-up','hometown-electronic','rain-disco','tomorrow-rock']},
  {id:'global-pop',title:'欧美流行',subtitle:'GLOBAL POP',cover:globalPopCover,trackIds:['after-midnight','if-you-wanna-feel-me','where-the-wind-goes','cant-stop-this-feeling','midnight-call','summer-on-repeat']},
]

export const playlistDefinitions=[
  ...sharedPlaylists.map(playlist=>playlist.id==='electronic'
    ? {...playlist,trackIds:[...playlist.trackIds,'body-language','into-the-sun','one-more-night']}
    : playlist),
  {id:'hip-hop-rnb',title:'Hip-Hop&R&B',subtitle:'RAP & SOUL',cover:houseCover,trackIds:['golden-hour-love','no-reply','too-close','big-motion','running-through-my-mind','westside-summer']},
]

export function filterPlaylists(playlists:readonly PreviewPlaylist[],filter:string):PreviewPlaylist[]{
  if(filter==='all')return [...playlists]
  return playlists.map(playlist=>({...playlist,tracks:playlist.tracks.filter(track=>track.filters.includes(filter))})).filter(playlist=>playlist.tracks.length>0)
}

export function usePreviewPlaylists({forHome=false}:{forHome?:boolean}={}){
  const tracks=usePreviewTracks()
  const house=forHome?useHouseRecommendations():undefined
  const definitions=forHome?playlistDefinitions:sharedPlaylists
  return computed<PreviewPlaylist[]>(()=>definitions.map(playlist=>({
    id:playlist.id,title:t(playlist.title),subtitle:playlist.subtitle,cover:playlist.cover,
    tracks:[
      ...playlist.trackIds.flatMap(id=>{const track=tracks.value.find(item=>item.id===id);return track?[track]:[]}),
      ...(playlist.id==='electronic'&&house?house.value.map(track=>({...track,filters:[...track.filters],tags:[...track.tags]})):[]),
    ],
  })))
}
