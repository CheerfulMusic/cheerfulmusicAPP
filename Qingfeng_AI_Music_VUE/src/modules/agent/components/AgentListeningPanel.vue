<script setup lang="ts">
import InspirationGalaxy from './InspirationGalaxy.vue'
import { computed, ref, watch } from 'vue'
import { usePlayer } from '../../../shared/player/store'
import { lyricsSource } from '../../../shared/player/current-lyrics'
import { locale } from '../../../shared/i18n'
import { usePreviewTracks } from '../../home/model/tracks'
import { sortByHeat, hotStat, isLiked, toggleReaction, recordPlay, hotLoginHint } from '../../home/model/hot-songs'
import SyncedLyrics from '../../works/components/SyncedLyrics.vue'
import { loadWork } from '../../studio/api/works'
import { downloadSong } from '../../works/api/download'
import { getJson } from '../../../shared/http/api-client'
import { songShareUrl } from '../../works/model/song-sharing'
import { createShare, audioUrl, type PlaybackLyrics } from '../../works/api/detail'

const emit=defineEmits<{remix:[title:string];work:[id:string]}>()
const requested=new URLSearchParams(location.search).get('listen')
const follow=ref(true), sharing=ref(false), shareLink=ref('')
const player=usePlayer(), catalog=usePreviewTracks()
const tr=(zh:string,en:string)=>locale.value==='en'?en:zh
const track=computed(()=>player.current??catalog.value.find(row=>row.id===requested)??sortByHeat(catalog.value)[0])
const sample=computed(()=>catalog.value.find(item=>item.id===track.value?.id))
const source=computed(()=>lyricsSource(track.value?.audio??''))
const owner=computed(()=>source.value?.kind==='owner'?source.value:undefined)
const playing=computed(()=>!!player.current&&(player.playing||player.starting))
const tab=ref<'lyrics'|'story'|'similar'|'queue'>('lyrics'), story=ref(''), publicLyrics=ref(''), notice=ref('')
const loadedLines=ref<PlaybackLyrics['lines']>([])
const timedLines=computed(()=>track.value?.lyricLines?.length?track.value.lyricLines:loadedLines.value)
const lyricRows=ref<HTMLElement>()
const lineIndex=computed(()=>timedLines.value.findIndex(line=>player.time>=line.start && player.time<line.end)??-1)
watch([lineIndex,follow],([index,enabled])=>{
  const row=lyricRows.value?.querySelectorAll<HTMLElement>('.timed-line')[index]
  if(enabled && row && lyricRows.value)lyricRows.value.scrollTop+=row.getBoundingClientRect().top-lyricRows.value.getBoundingClientRect().top-lyricRows.value.clientHeight/2
},{flush:'post'})
const duration=computed(()=>player.duration || sample.value?.durationSeconds || 0)
const clock=(seconds:number)=>Math.floor(seconds/60)+':'+String(Math.floor(seconds%60)).padStart(2,'0')
async function toggle(){
  if(!track.value)return
  if(!player.current)player.setQueue(sortByHeat(catalog.value))
  const selected=track.value, starting=!playing.value
  await player.toggle(selected)
  if(starting && player.playing && player.current?.id===selected.id && sample.value)recordPlay(selected.id)
}
watch([track,tab],async ([song,view],_,cleanup)=>{
  let live=true;cleanup(()=>{live=false});notice.value='';story.value='';publicLyrics.value='';loadedLines.value=[]
  try{
    if(view==='story'&&owner.value){const work=await loadWork(owner.value.work);if(live)story.value=work.story??''}
    if(view==='lyrics'&&source.value?.kind==='public'&&!song?.lyricLines?.length){
      const data=await getJson<PlaybackLyrics>(source.value.path)
      if(live){publicLyrics.value=data.text;loadedLines.value=data.lines??[]}
    }
  }catch{if(live)notice.value=tr('暂时无法读取歌曲详情','Song details are unavailable')}
},{immediate:true})
const similar=computed(()=>sortByHeat(catalog.value).filter(row=>row.id!==track.value?.id).sort((a,b)=>Number(b.category===sample.value?.category)-Number(a.category===sample.value?.category)).slice(0,5))
function playSimilar(row:typeof catalog.value[number]){
  player.setQueue(similar.value)
  void player.toggle(row)
}
watch(track,()=>{shareLink.value=''})
async function share(){
  const selected=track.value
  if(!selected||sharing.value)return
  if(owner.value && selected.provenance==='IMPORTED'){emit('work',owner.value.work);return}
  sharing.value=true
  try{
    let link=shareLink.value
    if(!link && owner.value){const result=await createShare(owner.value.work,owner.value.asset);link=songShareUrl(result.songId,location.origin)}
    else if(!link && sample.value){const url=new URL('/agent',location.origin);url.searchParams.set('listen',selected.id);link=url.href}
    if(!link||track.value?.id!==selected.id)return
    shareLink.value=link
    await navigator.clipboard.writeText(link)
    notice.value=tr('已复制分享链接','Share link copied')
  }catch{notice.value=shareLink.value?tr('可复制下方链接','Copy the link below'):tr('分享失败，请重试','Sharing failed. Try again.')}
  finally{sharing.value=false}
}
async function download(){
  if(!owner.value||!track.value)return
  const path='app/works/'+encodeURIComponent(owner.value.work)+'/audio/'+encodeURIComponent(owner.value.asset)+'/download'
  await downloadSong(path,audioUrl(owner.value.work,owner.value.asset,true,track.value.title+'.mp3'),track.value.title+'.mp3')
}
</script>
<template>
  <section v-if="track" class="listening-panel">
    <header>{{ tr('音乐播放','Music player') }}</header>
    <div class="record-summary"><div class="agent-album"><div class="agent-record" :class="{'is-spinning':playing}" aria-hidden="true"><span/></div><div class="agent-album-art"><img :src="track.cover" alt=""/></div></div>
    <div class="record-copy"><h2>{{ track.title }}</h2><p class="listen-meta">{{ sample?.genre || sample?.category || tr('我的作品','My music') }} · {{ clock(duration) }}</p>
    <div class="listen-actions">
      <button v-if="sample" :aria-label="tr('喜欢','Like')" :aria-pressed="isLiked(track.id)" @click="toggleReaction(track.id,'like')"><i aria-hidden="true">{{ isLiked(track.id)?'♥':'♡' }}</i>{{ hotStat(track.id).likes }}</button>
      <button v-if="owner || sample" :disabled="sharing" @click="share"><i aria-hidden="true">♧</i>{{ tr('分享','Share') }}</button>
      <button v-if="owner" @click="download"><i aria-hidden="true">↓</i>{{ tr('下载','Download') }}</button>
      <button @click="owner ? emit('work',owner.work) : tab='story'"><i aria-hidden="true">···</i>{{ tr('详情','Details') }}</button>
    </div>
    </div></div><p v-if="sample && hotLoginHint" role="status">{{ hotLoginHint }}</p>

    <input class="agent-preview-range" type="range" min="0" :max="duration||1" :value="player.time" :disabled="!player.duration" :aria-label="tr('播放进度','Playback position')" @input="player.seek(Number(($event.target as HTMLInputElement).value))"/>
    <div class="agent-player-times"><span>{{ clock(player.time) }}</span><span>{{ clock(duration) }}</span></div>
    <div class="agent-player-controls">
      <button :aria-pressed="player.repeatOne" :aria-label="tr('单曲循环','Repeat one')" @click="player.toggleRepeat()">⇄</button>
      <button :disabled="!player.hasQueue" :aria-label="tr('上一首','Previous')" @click="player.next(-1)">◀▏</button>
      <button class="agent-preview-play" :aria-label="playing?tr('暂停','Pause'):tr('播放','Play')" @click="toggle">{{ playing?'Ⅱ':'▶' }}</button>
      <button :disabled="!player.hasQueue" :aria-label="tr('下一首','Next')" @click="player.next()">▕▶</button>
      <button :aria-label="tr('播放列表','Queue')" @click="tab='queue'">☷</button>
    </div>
    <InspirationGalaxy :work-id="owner?.work" :asset-id="owner?.asset"/>
    <p v-if="player.error||notice" role="status">{{ player.error||notice }}</p>
    <input v-if="shareLink" readonly :value="shareLink" :aria-label="tr('分享链接','Share link')"/>
    <div class="agent-preview-tabs" role="tablist"><button v-for="view in (['lyrics','story','similar'] as const)" :key="view" role="tab" :aria-selected="tab===view" @click="tab=view">{{ view==='lyrics'?tr('歌词','Lyrics'):view==='story'?tr('作品故事','Story'):tr('相似推荐','Similar songs') }}</button></div>
    <div ref="lyricRows" class="listen-details" role="tabpanel">
      <template v-if="tab==='lyrics'"><label v-if="owner || timedLines.length" class="producer-lyric-switch">{{ tr('歌词滚动播放','Follow lyrics') }}<input v-model="follow" type="checkbox"/></label><SyncedLyrics show-times :follow-enabled="follow" v-if="owner" :key="track.id" :work-id="owner.work" :asset-id="owner.asset" :player-track-id="track.id" :auto-sync-enabled="false" @seek="player.seek($event)"/><template v-else-if="timedLines.length"><button class="timed-line" v-for="line in timedLines" :key="line.start" :aria-current="player.time>=line.start&&player.time<line.end" @click="player.seek(line.start)"><time>{{ clock(line.start) }}</time> {{ line.text }}</button></template><p v-else>{{ publicLyrics||sample?.lyrics||tr('暂无歌词','No lyrics available') }}</p></template>
      <p v-else-if="tab==='story'">{{ story||sample?.description||tr('暂无作品故事','No story available') }}</p>
      <template v-else-if="tab==='similar'"><button v-for="row in similar" :key="row.id" @click="playSimilar(row)">▶ {{ row.title }}</button></template>
      <template v-else><button v-for="row in player.tracks" :key="row.id" :aria-current="row.id===track.id" @click="player.toggle(row)">{{ row.title }}</button><p v-if="!player.tracks.length">{{ tr('播放歌曲后会显示队列','Play a song to start the queue') }}</p></template>
    </div>
    <footer class="listen-footer"><span v-if="!owner" class="preview-only">{{ tr('仅供试听','Preview only') }}</span><button v-if="owner"  @click="download">↓ {{ tr('下载 · 首次15积分','Download · 15 credits first time') }}</button><button v-if="owner || sample" :disabled="sharing" @click="share">{{ tr('分享','Share') }}</button><button @click="emit('remix',track.title)">＋ {{ tr('改编同款','Remix') }}</button></footer>
  </section>
</template>
