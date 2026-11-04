<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { ApiError } from '../../../shared/http/api-client'
import { lyricPosition, plainLyricLines } from '../../../shared/player/lyric-timeline'
import { getPlaybackLyrics, syncPlaybackLyrics, type PlaybackLyrics } from '../api/detail'
import {comparableLyric,type EditHint} from '../model/section-edit'
const props=defineProps<{workId:string;assetId:string;lyrics?:string;editable?:boolean}>(),emit=defineEmits<{seek:[time:number];edit:[hint:EditHint|undefined]}>()
const player=usePlayer(),data=ref<PlaybackLyrics>(),loading=ref(false),syncing=ref(false),error=ref(''),following=ref(true),viewport=ref<HTMLElement>()
const rows=computed(()=>data.value?.lines||[])
const plain=computed(()=>plainLyricLines(data.value?.text||props.lyrics||''))
const isCurrent=computed(()=>player.current?.id===props.assetId)
const position=computed(()=>lyricPosition(rows.value,isCurrent.value?player.time:0))
const active=computed(()=>isCurrent.value?position.value.active:-1)
const timed=computed(()=>data.value?.status==='READY'&&rows.value.length>0)
const phase=computed(()=>!isCurrent.value?'播放歌曲，歌词会随歌声滚动。':player.time<(rows.value[0]?.start||0)?'前奏':player.time>=(rows.value.at(-1)?.end||Infinity)?'尾奏':active.value<0?'间奏':player.playing?'正在演唱':'已暂停')
const canSync=computed(()=>data.value?.canSync&&['NOT_REQUESTED','FAILED'].includes(data.value.status))
let sequence=0,disposed=false,followTimer:ReturnType<typeof setTimeout>|undefined,pollTimer:ReturnType<typeof setTimeout>|undefined,pendingKey=''
function storageName(){return `qingfeng.lyrics.timing:${props.assetId}`}
function rememberKey(){try{sessionStorage.setItem(storageName(),pendingKey)}catch{/* 当前页面仍复用请求键。 */}}
async function load(){
  const turn=++sequence,work=props.workId,asset=props.assetId;clearTimeout(pollTimer);data.value=undefined;error.value='';loading.value=true;following.value=true;pendingKey=''
  try{pendingKey=sessionStorage.getItem(storageName())||''}catch{/* 浏览器存储被禁用。 */}
  try{const result=await getPlaybackLyrics(work,asset);if(turn!==sequence||disposed)return;data.value=result;await center(false);pollIfNeeded()}
  catch(e){if(turn===sequence&&!disposed)error.value=e instanceof Error?e.message:'同步歌词读取失败，请重试。'}
  finally{if(turn===sequence)loading.value=false}
}
function pollIfNeeded(){
  clearTimeout(pollTimer)
  if(data.value?.status!=='FETCHING'||disposed)return
  const turn=sequence,work=props.workId,asset=props.assetId
  pollTimer=setTimeout(async()=>{if(disposed||turn!==sequence)return;try{const value=await getPlaybackLyrics(work,asset);if(turn!==sequence||disposed)return;data.value=value;pollIfNeeded()}catch{if(turn===sequence)error.value='同步歌词读取失败，请重试。'}},3000)
}
async function sync(){
  if(syncing.value||!canSync.value)return
  const turn=sequence,work=props.workId,asset=props.assetId
  if(data.value?.status==='FAILED')pendingKey=''
  pendingKey ||= crypto.randomUUID();rememberKey();syncing.value=true;error.value=''
  try{const result=await syncPlaybackLyrics(work,asset,pendingKey);if(turn!==sequence||disposed)return;data.value=result;following.value=true;await center(false);pollIfNeeded()}
  catch(e){if(turn!==sequence||disposed)return;error.value=e instanceof Error?e.message:'同步歌词暂时不可用。';if(!(e instanceof ApiError)||[0,408].includes(e.status)){if(data.value)data.value={...data.value,status:'UNKNOWN'}}}
  finally{if(turn===sequence)syncing.value=false}
}
async function center(smooth=true){
  await nextTick();if(disposed||!following.value||!timed.value||!viewport.value)return
  const row=viewport.value.querySelector<HTMLElement>(`[data-line="${isCurrent.value?position.value.anchor:0}"]`)
  if(!row)return
  const top=row.offsetTop-viewport.value.clientHeight/2+row.offsetHeight/2
  viewport.value.scrollTo({top:Math.max(0,top),behavior:smooth&&!window.matchMedia('(prefers-reduced-motion: reduce)').matches?'smooth':'auto'})
}
function manualScroll(){following.value=false;clearTimeout(followTimer);followTimer=setTimeout(()=>resume(),6000)}
function resume(){clearTimeout(followTimer);following.value=true;void center()}
function jump(time:number){resume();emit('seek',time)}
function editLine(index:number,isTimed:boolean){
  const line=isTimed?rows.value[index]:undefined,text=line?.text||plain.value[index]||''
  const previous=isTimed?rows.value.slice(0,index).map(r=>r.text):plain.value.slice(0,index)
  if(player.current?.id===props.assetId&&player.playing)void player.toggle(player.current)
  emit('edit',{text,start:line?.start,end:line?.end,occurrence:previous.filter(value=>comparableLyric(value)===comparableLyric(text)).length})
}
watch(()=>[props.workId,props.assetId],()=>{syncing.value=false;void load()},{immediate:true})
watch(()=>[position.value.anchor,isCurrent.value,rows.value.length],()=>{if(following.value)void center()})
watch(()=>player.seekVersion,()=>{if(isCurrent.value)resume()})
onBeforeUnmount(()=>{disposed=true;sequence++;clearTimeout(followTimer);clearTimeout(pollTimer)})
</script>
<template><section class="synced-lyrics" :aria-label="t('歌曲歌词')" :aria-busy="loading||syncing"><div class="synced-lyrics-heading"><h3>{{ t('歌词') }}</h3><button v-if="editable" class="lyric-edit-entry" @click="emit('edit',undefined)">{{ t('局部改词') }}</button><span v-if="timed" class="lyric-sync-badge">{{ t('歌词已同步') }}</span><span v-else-if="syncing||data?.status==='FETCHING'" class="lyric-sync-badge">{{ t('正在同步歌词…') }}</span></div>
  <p v-if="timed" class="lyric-phase">{{ t(phase) }}</p><p v-else class="lyric-phase">{{ loading?t('正在载入歌词…'):t('歌词全文') }}</p>
  <div ref="viewport" class="lyric-viewport" tabindex="0" :aria-label="t('滚动查看歌词')" @wheel.passive="manualScroll" @touchstart.passive="manualScroll" @pointerdown="manualScroll" @keydown.page-down="manualScroll" @keydown.page-up="manualScroll" @keydown.down="manualScroll" @keydown.up="manualScroll">
    <ol v-if="timed" class="lyric-lines"><li v-for="(line,index) in rows" :key="index" :data-line="index" :class="{active:active===index,past:isCurrent&&index<position.anchor}"><button :aria-current="active===index?'true':undefined" :aria-label="t('从这句开始播放：{line}',{line:line.text})" @click="jump(line.start)">{{ line.text }}</button><button v-if="editable" class="lyric-edit-line" :aria-label="t('修改这句歌词：{line}',{line:line.text})" @click="editLine(index,true)">{{ t('改词') }}</button></li></ol>
    <div v-else-if="plain.length" class="plain-lyric-lines"><p v-for="(line,index) in plain" :key="index">{{ line }}<button v-if="editable" class="lyric-edit-line" :aria-label="t('修改这句歌词：{line}',{line})" @click="editLine(index,false)">{{ t('改词') }}</button></p></div><p v-else class="lyric-empty">{{ loading?t('正在载入歌词…'):t('这首歌暂时没有可展示的歌词。') }}</p>
  </div>
  <div class="lyric-controls"><button v-if="timed&&!following&&isCurrent" class="lyric-follow" @click="resume">{{ t('回到正在唱的歌词') }}</button><span v-else-if="timed" class="lyric-help">{{ t('点一句歌词，从这里开始听') }}</span><template v-else><button v-if="canSync" class="lyric-fetch" :disabled="syncing||loading" @click="sync">{{ syncing?t('正在同步歌词…'):data?.status==='FAILED'?t('重试同步歌词'):t('同步歌词') }}</button><button v-else-if="data?.status==='UNKNOWN'||error" class="lyric-fetch" :disabled="syncing||loading" @click="load">{{ t('刷新歌词') }}</button></template></div>
  <p v-if="error" class="lyric-feedback" role="status">{{ t(error) }}</p><p v-else-if="data?.status==='UNKNOWN'" class="lyric-feedback">{{ t('同步结果仍待确认，暂不重复请求。') }}</p><p v-else-if="data?.status==='FAILED'" class="lyric-feedback">{{ t('同步服务暂未返回时间轴，仍可查看歌词全文。') }}</p><p v-else-if="data?.status==='EMPTY'" class="lyric-feedback">{{ t('这首音频未返回可用的歌词时间轴。') }}</p><p v-else-if="canSync&&!timed" class="lyric-feedback">{{ t('同步后可跟随歌声滚动，会使用一次歌词同步额度。') }}</p>
</section></template>
