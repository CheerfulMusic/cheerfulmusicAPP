<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usePlayer } from '../../../shared/player/store'
import { lyricsSource } from '../../../shared/player/current-lyrics'
import { locale } from '../../../shared/i18n'
import { usePreviewTracks } from '../../home/model/tracks'
import { sortByHeat, isLiked, toggleReaction, recordPlay, hotLoginHint } from '../../home/model/hot-songs'
import SyncedLyrics from '../../works/components/SyncedLyrics.vue'
import { loadWork } from '../../studio/api/works'
import { downloadSong } from '../../works/api/download'
import { getJson } from '../../../shared/http/api-client'
import { audioUrl, type PlaybackLyrics } from '../../works/api/detail'

const player=usePlayer(), catalog=usePreviewTracks()
const tr=(zh:string,en:string)=>locale.value==='en'?en:zh
const track=computed(()=>player.current??sortByHeat(catalog.value)[0])
const sample=computed(()=>catalog.value.find(item=>item.id===track.value?.id))
const source=computed(()=>lyricsSource(track.value?.audio??''))
const owner=computed(()=>source.value?.kind==='owner'?source.value:undefined)
const playing=computed(()=>!!player.current&&(player.playing||player.starting))
const tab=ref<'lyrics'|'story'|'queue'>('lyrics'), story=ref(''), publicLyrics=ref(''), notice=ref('')
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
  let live=true;cleanup(()=>{live=false});notice.value='';story.value='';publicLyrics.value=''
  try{
    if(view==='story'&&owner.value){const work=await loadWork(owner.value.work);if(live)story.value=work.story??''}
    if(view==='lyrics'&&source.value?.kind==='public'&&!song?.lyricLines?.length){
      const data=await getJson<PlaybackLyrics>(source.value.path)
      if(live)publicLyrics.value=data.text||data.lines.map(line=>line.text).join('\n')
    }
  }catch{if(live)notice.value=tr('暂时无法读取歌曲详情','Song details are unavailable')}
},{immediate:true})
async function download(){
  if(!owner.value||!track.value)return
  const path='app/works/'+encodeURIComponent(owner.value.work)+'/audio/'+encodeURIComponent(owner.value.asset)+'/download'
  await downloadSong(path,audioUrl(owner.value.work,owner.value.asset,true,track.value.title+'.mp3'),track.value.title+'.mp3')
}
</script>
<template>
  <section v-if="track" class="listening-panel">
    <header>{{ tr('音乐播放','Music player') }}</header>
    <div class="agent-album"><div class="agent-record" :class="{'is-spinning':playing}" aria-hidden="true"><span/></div><div class="agent-album-art"><img :src="track.cover" alt=""/></div></div>
    <h2>{{ track.title }}</h2><p class="listen-meta">{{ sample?.genre || sample?.category || tr('我的作品','My music') }} · {{ clock(duration) }}</p>
    <div class="listen-actions">
      <button v-if="sample" :aria-pressed="isLiked(track.id)" @click="toggleReaction(track.id,'like')">{{ isLiked(track.id)?'♥':'♡' }} {{ tr('喜欢','Like') }}</button>
      <template v-if="owner"><a :href="'/works/'+encodeURIComponent(owner.work)">{{ tr('作品详情与分享','Details & sharing') }}</a><button @click="download">↓ {{ tr('下载','Download') }}</button></template>
    </div>
    <p v-if="sample && hotLoginHint" role="status">{{ hotLoginHint }}</p>
    <input class="agent-preview-range" type="range" min="0" :max="duration||1" :value="player.time" :disabled="!player.duration" :aria-label="tr('播放进度','Playback position')" @input="player.seek(Number(($event.target as HTMLInputElement).value))"/>
    <div class="agent-player-times"><span>{{ clock(player.time) }}</span><span>{{ clock(duration) }}</span></div>
    <div class="agent-player-controls">
      <button :aria-pressed="player.repeatOne" :aria-label="tr('单曲循环','Repeat one')" @click="player.toggleRepeat()">⇄</button>
      <button :disabled="!player.hasQueue" :aria-label="tr('上一首','Previous')" @click="player.next(-1)">◀▏</button>
      <button class="agent-preview-play" :aria-label="playing?tr('暂停','Pause'):tr('播放','Play')" @click="toggle">{{ playing?'Ⅱ':'▶' }}</button>
      <button :disabled="!player.hasQueue" :aria-label="tr('下一首','Next')" @click="player.next()">▕▶</button>
      <button :aria-label="tr('播放列表','Queue')" @click="tab='queue'">☷</button>
    </div>
    <p v-if="player.error||notice" role="status">{{ player.error||notice }}</p>
    <div class="agent-preview-tabs" role="tablist"><button v-for="view in (['lyrics','story','queue'] as const)" :key="view" role="tab" :aria-selected="tab===view" @click="tab=view">{{ view==='lyrics'?tr('歌词','Lyrics'):view==='story'?tr('作品故事','Story'):tr('播放列表','Queue') }}</button></div>
    <div class="listen-details" role="tabpanel">
      <template v-if="tab==='lyrics'"><SyncedLyrics v-if="owner" :key="track.id" :work-id="owner.work" :asset-id="owner.asset" :player-track-id="track.id" :auto-sync-enabled="false" @seek="player.seek($event)"/><template v-else-if="track.lyricLines?.length"><button v-for="line in track.lyricLines" :key="line.start" :aria-current="player.time>=line.start&&player.time<line.end" @click="player.seek(line.start)">{{ line.text }}</button></template><p v-else>{{ publicLyrics||sample?.lyrics||tr('暂无歌词','No lyrics available') }}</p></template>
      <p v-else-if="tab==='story'">{{ story||sample?.description||tr('暂无作品故事','No story available') }}</p>
      <template v-else><button v-for="row in player.tracks" :key="row.id" :aria-current="row.id===track.id" @click="player.toggle(row)">{{ row.title }}</button><p v-if="!player.tracks.length">{{ tr('播放歌曲后会显示队列','Play a song to start the queue') }}</p></template>
    </div>
  </section>
</template>
