<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { ApiError } from '../../../shared/http/api-client'
import { lyricPosition, plainLyricLines } from '../../../shared/player/lyric-timeline'
import { getPlaybackLyrics, syncPlaybackLyrics, type PlaybackLyrics } from '../api/detail'
const props=defineProps<{workId:string;assetId:string;lyrics?:string}>(),emit=defineEmits<{seek:[time:number]}>()
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
let automaticAttempted=false,pollUntil=0,pollFailures=0
function storageName(){return `qingfeng.lyrics.timing:${props.assetId}`}
function rememberKey(){try{sessionStorage.setItem(storageName(),pendingKey)}catch{/* 本次挂载仍复用同一个请求键。 */}}
function autoSync(){
  // 仅实际播放本曲、首次尚未请求的时间轴才自动同步。失败和未知结果不自动重发。
  if(disposed||loading.value||syncing.value||automaticAttempted||!isCurrent.value||!player.playing||data.value?.status!=='NOT_REQUESTED'||!canSync.value)return
  automaticAttempted=true;void sync()
}
async function load(){
  const turn=++sequence,work=props.workId,asset=props.assetId
  clearTimeout(pollTimer);clearTimeout(followTimer);data.value=undefined;error.value='';loading.value=true;syncing.value=false;following.value=true;pendingKey='';pollFailures=0;pollUntil=Date.now()+180000
  try{const saved=sessionStorage.getItem(storageName())||'';if(/^[a-f0-9-]{36}$/i.test(saved))pendingKey=saved}catch{/* 浏览器存储被禁用。 */}
  automaticAttempted=!!pendingKey
  try{const result=await getPlaybackLyrics(work,asset);if(turn!==sequence||disposed)return;data.value=result;await center(false)}
  catch(e){if(turn===sequence&&!disposed){pollFailures++;error.value=e instanceof Error?e.message:'同步歌词读取失败，请重试。'}}
  finally{if(turn===sequence&&!disposed){loading.value=false;autoSync();pollIfNeeded()}}
}
function pollIfNeeded(){
  clearTimeout(pollTimer)
  // 查询可重试，提交不可盲重试；网络波动最多连续重查五次，总等待不超过三分钟。
  if(disposed||syncing.value||Date.now()>=pollUntil||pollFailures>=5||!(data.value?.status==='FETCHING'||data.value?.status==='UNKNOWN'||!data.value&&error.value))return
  const turn=sequence,work=props.workId,asset=props.assetId
  pollTimer=setTimeout(async()=>{
    if(disposed||turn!==sequence)return
    if(document.visibilityState==='hidden'){pollIfNeeded();return}
    try{const value=await getPlaybackLyrics(work,asset);if(turn!==sequence||disposed)return;data.value=value;pollFailures=0;error.value='';await center(false);autoSync()}
    catch{if(turn!==sequence||disposed)return;pollFailures++;error.value='同步歌词读取失败，请重试。'}
    pollIfNeeded()
  },Math.min(15000,3000*2**pollFailures))
}
async function sync(){
  if(syncing.value||loading.value||!canSync.value)return
  const turn=sequence,work=props.workId,asset=props.assetId
  if(data.value?.status==='FAILED')pendingKey=''
  pendingKey ||= crypto.randomUUID();rememberKey();automaticAttempted=true;syncing.value=true;error.value='';clearTimeout(pollTimer)
  try{const result=await syncPlaybackLyrics(work,asset,pendingKey);if(turn!==sequence||disposed)return;data.value=result;following.value=true;await center(false)}
  catch(e){
    if(turn!==sequence||disposed)return
    error.value=e instanceof Error?e.message:'同步歌词暂时不可用。'
    if(!(e instanceof ApiError)||[0,408].includes(e.status)){if(data.value)data.value={...data.value,status:'UNKNOWN'}}
  }
  finally{if(turn===sequence&&!disposed){syncing.value=false;pollFailures=0;pollUntil=Date.now()+180000;pollIfNeeded()}}
}
async function center(smooth=true){
  const turn=sequence;await nextTick()
  if(turn!==sequence||disposed||!following.value||!timed.value||!isCurrent.value||!viewport.value)return
  const row=viewport.value.querySelector<HTMLElement>(`[data-line="${position.value.anchor}"]`)
  if(!row)return
  // 只滚动歌词容器，不改变整个页面的位置。
  const top=viewport.value.scrollTop+row.getBoundingClientRect().top-viewport.value.getBoundingClientRect().top-viewport.value.clientHeight/2+row.offsetHeight/2
  viewport.value.scrollTo({top:Math.max(0,top),behavior:smooth&&!window.matchMedia('(prefers-reduced-motion: reduce)').matches?'smooth':'instant'})
}
function manualScroll(){if(!timed.value)return;following.value=false;clearTimeout(followTimer);followTimer=setTimeout(()=>resume(),6000)}
function resume(smooth=true){clearTimeout(followTimer);following.value=true;void center(smooth)}
function jump(time:number){resume(false);emit('seek',time)}
function visible(){if(document.visibilityState==='visible'&&isCurrent.value){resume(false);if(data.value?.status==='FETCHING'||data.value?.status==='UNKNOWN'||!data.value){pollUntil=Date.now()+180000;pollFailures=0;pollIfNeeded()}autoSync()}}
watch(()=>[props.workId,props.assetId],()=>{void load()},{immediate:true})
watch(()=>[player.playing,isCurrent.value,loading.value,data.value?.status],()=>{autoSync();if(isCurrent.value&&player.playing)resume(false)})
watch(()=>[position.value.anchor,isCurrent.value,timed.value],()=>{if(following.value)void center()})
watch(()=>player.seekVersion,()=>{if(isCurrent.value)resume(false)})
onMounted(()=>document.addEventListener('visibilitychange',visible))
onBeforeUnmount(()=>{disposed=true;sequence++;clearTimeout(followTimer);clearTimeout(pollTimer);document.removeEventListener('visibilitychange',visible)})
</script>
<template><section class="synced-lyrics" :aria-label="t('歌曲歌词')" :aria-busy="loading||syncing"><div class="synced-lyrics-heading"><h3>{{ t('歌词') }}</h3><span v-if="timed" class="lyric-sync-badge">{{ t('歌词已同步') }}</span><span v-else-if="syncing||data?.status==='FETCHING'" class="lyric-sync-badge">{{ t('正在同步歌词…') }}</span></div>
  <p v-if="timed" class="lyric-phase">{{ t(phase) }}</p><p v-else class="lyric-phase">{{ loading?t('正在载入歌词…'):t('歌词全文') }}</p>
  <div ref="viewport" class="lyric-viewport" tabindex="0" :aria-label="t('滚动查看歌词')" @wheel.passive="manualScroll" @touchstart.passive="manualScroll" @pointerdown="manualScroll" @keydown.page-down="manualScroll" @keydown.page-up="manualScroll" @keydown.down="manualScroll" @keydown.up="manualScroll">
    <ol v-if="timed" class="lyric-lines"><li v-for="(line,index) in rows" :key="index" :data-line="index" :class="{active:active===index,past:isCurrent&&index<position.anchor}"><button :aria-current="active===index?'true':undefined" :aria-label="t('从这句开始播放：{line}',{line:line.text})" @click="jump(line.start)">{{ line.text }}</button></li></ol>
    <div v-else-if="plain.length" class="plain-lyric-lines"><p v-for="(line,index) in plain" :key="index">{{ line }}</p></div><p v-else class="lyric-empty">{{ loading?t('正在载入歌词…'):t('这首歌暂时没有可展示的歌词。') }}</p>
  </div>
  <div class="lyric-controls"><button v-if="timed&&!following&&isCurrent" class="lyric-follow" @click="resume()">{{ t('回到正在唱的歌词') }}</button><span v-else-if="timed" class="lyric-help">{{ t('点一句歌词，从这里开始听') }}</span><template v-else><button v-if="canSync&&(error||data?.status==='FAILED'||automaticAttempted&&!syncing)" class="lyric-fetch" :disabled="syncing||loading" @click="sync">{{ t('重试同步歌词') }}</button><button v-else-if="data?.status==='UNKNOWN'||error" class="lyric-fetch" :disabled="syncing||loading" @click="load">{{ t('刷新歌词') }}</button></template></div>
  <p v-if="error" class="lyric-feedback" role="status">{{ t(error) }}</p><p v-else-if="data?.status==='UNKNOWN'" class="lyric-feedback">{{ t('同步结果仍待确认，暂不重复请求。') }}</p><p v-else-if="data?.status==='FAILED'" class="lyric-feedback">{{ t('同步服务暂未返回时间轴，仍可查看歌词全文。') }}</p><p v-else-if="data?.status==='EMPTY'" class="lyric-feedback">{{ t('这首音频未返回可用的歌词时间轴。') }}</p><p v-else-if="canSync&&!timed" class="lyric-feedback">{{ t('播放歌曲，歌词会随歌声滚动。') }}</p>
</section></template>
