<script setup lang="ts">
import {computed,ref,nextTick,onMounted,watch} from 'vue'
import {t,locale} from '../../../shared/i18n'
import {previewFilters} from '../model/tracks'
import {usePreviewPlaylists,filterPlaylists} from '../model/playlists'
import {hotFailure,hotLoginHint,loadHotSongs,loadMyReactions,signedIn,sortByHeat} from '../model/hot-songs'
import PlaylistCard from './PlaylistCard.vue'
import PlaylistDetails from './PlaylistDetails.vue'
const playlists=usePreviewPlaylists(),filter=ref('all'),more=ref(false),gallery=ref<HTMLElement>(),selectedPlaylist=ref('')
const moreToggle=ref<HTMLButtonElement>()
const primaryFilters=previewFilters.slice(0,3),extraFilters=previewFilters.slice(3)
const selectedExtra=computed(()=>extraFilters.find(option=>option.id===filter.value))
// 热度顺序在打开页面时定一次：点赞或播放后不会把卡片在眼皮底下重排。
const ranking=ref<Record<string,number>>({})
function freezeRanking(){const all=playlists.value.flatMap(playlist=>playlist.tracks);ranking.value=Object.fromEntries(sortByHeat(all).map((track,index)=>[track.id,index]))}
const visible=computed(()=>filterPlaylists(playlists.value,filter.value).map(playlist=>({...playlist,tracks:[...playlist.tracks].sort((a,b)=>(ranking.value[a.id]??99)-(ranking.value[b.id]??99))})))
const details=computed(()=>playlists.value.find(playlist=>playlist.id===selectedPlaylist.value))
const resultCount=computed(()=>visible.value.reduce((total,playlist)=>total+playlist.tracks.length,0))
async function choose(id:string){filter.value=id;more.value=false;await nextTick();gallery.value?.scrollTo({left:0,behavior:'instant'});if(extraFilters.some(option=>option.id===id))moreToggle.value?.focus({preventScroll:true})}
onMounted(async()=>{await loadHotSongs();freezeRanking();void loadMyReactions()})
// 登录状态变化后重新读取「我的点赞 / 收藏」，避免登出后仍显示已点赞。
watch(()=>signedIn(),()=>{void loadMyReactions()})
</script>
<template><section id="inspiration" class="p-width explore-music playlist-explore">
  <header class="explore-heading"><h2>{{t('热歌推荐')}}</h2><p v-if="locale!=='en'" class="explore-heading-en" lang="en">Trending Songs.</p><p class="explore-description">{{t('最近被反复播放的作品，选一首开始')}}</p></header>
  <p v-if="hotLoginHint" class="hot-notice" role="status">{{t(hotLoginHint)}}</p>
  <p v-if="hotFailure" class="hot-notice error" role="status">{{t(hotFailure)}}</p>
  <div class="explore-toolbar"><nav class="genre-tabs" :aria-label="t('筛选试听作品')"><button type="button" :class="{selected:filter==='all'}" :aria-pressed="filter==='all'" @click="choose('all')">{{t('全部')}}</button><button v-for="option in primaryFilters" :key="option.id" type="button" :class="{selected:filter===option.id}" :aria-pressed="filter===option.id" @click="choose(option.id)">{{t(option.label)}}</button><button ref="moreToggle" type="button" :class="{selected:!!selectedExtra}" :aria-expanded="more" aria-controls="explore-style-filters" @click="more=!more">{{selectedExtra?t(selectedExtra.label):t(more?'收起风格':'更多风格')}} <span aria-hidden="true">{{more?'−':'+'}}</span></button></nav><p class="swipe-hint">{{t('左右滑动，探索更多作品')}} <span aria-hidden="true">↔</span></p></div>
  <div v-if="more" id="explore-style-filters" class="playlist-style-filters"><nav class="genre-tabs" :aria-label="t('更多试听风格')"><button v-for="option in extraFilters" :key="option.id" type="button" :class="{selected:filter===option.id}" :aria-pressed="filter===option.id" @click="choose(option.id)">{{t(option.label)}}</button></nav></div>
  <p class="sr-only" role="status" aria-live="polite">{{ t('找到 {count} 首歌曲',{count:resultCount}) }}</p>
  <div ref="gallery" class="explore-gallery" :class="{'is-filtered':filter!=='all'}" tabindex="0" :aria-label="t('试听歌单列表')"><PlaylistCard v-for="playlist in visible" :key="playlist.id" :playlist="playlist" @more="selectedPlaylist=playlist.id"/></div>
  <footer class="explore-footer"><h3>{{t('下一首 听见你的创作')}}</h3><RouterLink class="p-button" to="/create">{{t('开始创作')}}</RouterLink></footer>
  <PlaylistDetails v-if="details" :playlist="details" @close="selectedPlaylist=''"/>
</section></template>
