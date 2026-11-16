<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { getJson } from '../../../shared/http/api-client'
import { locale } from '../../../shared/i18n'
const props=defineProps<{projectId:string}>()
const emit=defineEmits<{ 'state-change': [state: string] }>()
interface Status {id:string;state:string;shots:{state:string}[]}
const status=ref<Status>(),error=ref(false)
const en=computed(()=>locale.value==='en')
const labels:Record<string,string>={QUEUED:'排队中',RUNNING:'镜头生成中',ASSEMBLING:'配乐合成中',READY:'MV 已完成',FAILED:'镜头生成失败',ASSEMBLY_FAILED:'合成失败，可请求重新合成',UNCERTAIN:'等待核对上游任务，请勿重复生成',CANCELLED:'已取消'}
const active=new Set(['QUEUED','RUNNING','ASSEMBLING','UNCERTAIN'])
let timer:ReturnType<typeof setTimeout>|undefined,sequence=0,disposed=false,failures=0
const url=computed(()=>status.value?.state==='READY'?`/api/app/music-videos/${encodeURIComponent(props.projectId)}/content`:'')
async function refresh(){
  const id=props.projectId,version=++sequence;clearTimeout(timer)
  if(!/^[a-f0-9-]{36}$/i.test(id))return
  try{
    const result=await getJson<Status>(`app/music-videos/${id}`)
    if(disposed||version!==sequence)return
    if(result.id!==id||typeof result.state!=='string'||!Array.isArray(result.shots))throw Error('Invalid status')
    status.value=result;error.value=false;failures=0;emit('state-change',result.state)
    if(active.has(result.state))timer=setTimeout(()=>void refresh(),10000)
  }catch{
    if(disposed||version!==sequence)return
    error.value=true
    if(++failures<3)timer=setTimeout(()=>void refresh(),10000*failures)
  }
}
watch(()=>props.projectId,()=>{status.value=undefined;failures=0;void refresh()},{immediate:true})
function identityChanged(){sequence++;clearTimeout(timer);status.value=undefined;error.value=true}
window.addEventListener('qingfeng:identity-changed',identityChanged)
onBeforeUnmount(()=>{disposed=true;sequence++;clearTimeout(timer);window.removeEventListener('qingfeng:identity-changed',identityChanged)})
</script>
<template><div class="video-progress" aria-live="polite"><p v-if="status">{{ en?status.state:(labels[status.state]||status.state) }} · {{ status.shots.filter(s=>s.state==='READY').length }}/{{ status.shots.length }} {{ en?'shots ready':'个镜头完成' }}</p><p v-else-if="!error">{{ en?'Loading progress…':'正在读取进度…' }}</p><p v-if="error">{{ en?'Progress unavailable; the task may still be running.':'暂时无法获取进度，任务可能仍在运行。' }} <button type="button" @click="failures=0;refresh()">{{ en?'Refresh':'刷新' }}</button></p><template v-if="url"><video :src="url" controls preload="none" :aria-label="en?'Music video':'音乐视频'"/><a :href="url+'?download=true'">{{ en?'Download MV':'下载 MV' }}</a></template></div></template>
<style scoped>.video-progress{margin-top:10px;color:#75568f;font-size:13px}.video-progress video{width:100%;max-height:360px;border-radius:12px;background:#181024}.video-progress a{display:inline-block;margin-top:8px;color:#7743cb}.video-progress button{border:1px solid #dacbea;border-radius:8px;background:white;color:inherit;cursor:pointer}</style>
