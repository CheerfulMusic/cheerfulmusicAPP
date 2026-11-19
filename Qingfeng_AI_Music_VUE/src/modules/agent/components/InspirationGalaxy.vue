<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { getJson } from '../../../shared/http/api-client'
import { locale } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import GalaxyScene from './GalaxyScene.vue'

const props=defineProps<{workId?:string|null;assetId?:string|null}>()
const en=computed(()=>locale.value==='en')
const player=usePlayer(), video=ref<HTMLVideoElement>()
const project=ref(''), failed=ref(false), epoch=ref(0)
const content=computed(()=>'/api/app/music-videos/'+encodeURIComponent(project.value)+'/content')
function changeIdentity(){epoch.value++}
onMounted(()=>window.addEventListener('qingfeng:identity-changed',changeIdentity))
onBeforeUnmount(()=>window.removeEventListener('qingfeng:identity-changed',changeIdentity))
watch([()=>props.workId,()=>props.assetId,epoch],([work,asset],_,cleanup)=>{
  let current=true, timer:ReturnType<typeof setTimeout>
  cleanup(()=>{current=false;clearTimeout(timer)})
  project.value='';failed.value=false
  if(!work || !asset)return
  async function refresh(){
    try{
      const rows=await getJson<{id:string;asset_id:string;state:string}[]>('app/music-videos?workId='+encodeURIComponent(String(work)))
      if(!current)return
      const ready=rows.find(row=>row.asset_id===asset && row.state==='READY')
      if(project.value!==(ready?.id??'')){project.value=ready?.id??'';failed.value=false}
    }catch{ /* Keep the last playable result while the status endpoint is unavailable. */ }
    if(current)timer=setTimeout(refresh,20000)
  }
  void refresh()
},{immediate:true})
function playVideo(){if(player.current && (player.playing || player.starting))void player.toggle(player.current)}
watch(()=>player.playing,playing=>{if(playing)video.value?.pause()})
</script>
<template>
  <section class="inspiration-galaxy" :aria-label="en?'Inspiration galaxy':'灵感银河'">
    <template v-if="project">
      <header><strong>{{ en?'Inspiration galaxy':'灵感银河' }}</strong><small>MV</small></header>
      <video v-if="!failed" :key="content" ref="video" controls playsinline preload="metadata" :src="content" @play="playVideo" @error="failed=true"/>
      <button v-else type="button" @click="failed=false">{{ en?'Retry video playback':'重新加载 MV' }}</button>
    </template>
    <GalaxyScene v-else/>
  </section>
</template>
<style scoped>
.inspiration-galaxy{flex:none;overflow:hidden;border:1px solid #dce3d9;border-radius:14px;margin:8px 0 14px;background:#f8f8f1}
.inspiration-galaxy header{display:flex;justify-content:space-between;padding:8px 12px;color:#415748}
.inspiration-galaxy video{width:100%;display:block;max-height:38vh;background:#202723}
</style>
