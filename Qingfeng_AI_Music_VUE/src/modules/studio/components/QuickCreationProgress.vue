<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount,computed,watch} from 'vue'
import {t} from '../../../shared/i18n'
import {latestQuickCreation,retryQuickCreation,quickInProgress,type QuickCreation} from '../api/quick-creation'
import MusicGenerationDialog from '../../works/components/MusicGenerationDialog.vue'
const props=withDefaults(defineProps<{
 workId:string;title?:string;autoOpen?:boolean;resultReady?:boolean;initialFlow?:QuickCreation|null
 awaitingSubmission?:boolean;completionMode?:'reveal'|'navigate'
 dialogOpen?:boolean;submissionStatus?:string;submissionPending?:boolean;submissionIssue?:string
}>(),{title:'',autoOpen:true,resultReady:false,completionMode:'reveal',dialogOpen:undefined})
const emit=defineEmits<{checked:[ok:boolean];busy:[value:boolean];updated:[value:QuickCreation];resolved:[value:QuickCreation|null];view:[];visibility:[value:boolean]}>()
const flow=ref<QuickCreation|null>(null),issue=ref(''),retrying=ref(false),loaded=ref(false)
const localOpen=ref(false)
const open=computed(()=>props.dialogOpen??localOpen.value)
function setOpen(value:boolean){
 if(props.dialogOpen===undefined)localOpen.value=value
 else if(value!==props.dialogOpen)emit('visibility',value)
}
let openedFlow='',presentedResult=''
let alive=true,polling=false,workSequence=0
const labels:Record<string,string>={QUEUED:'创作已排队',WRITING:'正在编写歌词',LYRICS_READY:'歌词已完成 正在准备音乐',SUBMITTING_MUSIC:'正在准备音乐',MUSIC_QUEUED:'音乐已排队',SUBMITTING:'正在提交音乐',RUNNING:'正在制作音乐',SAVING:'正在保存音频',SUBMISSION_UNKNOWN:'提交结果待核对',SUCCEEDED:'你的歌曲已完成',FAILED:'这次创作暂未完成',CANCELLED:'已撤销'}
const active=computed(()=>!!flow.value&&quickInProgress(flow.value.status))
const uncertainLyrics=computed(()=>flow.value?.errorCode==='LYRICS_SUBMISSION_UNKNOWN')
const dialogStatus=computed(()=>flow.value?.status==='QUEUED'&&flow.value.taskId?'MUSIC_QUEUED':flow.value?.status||props.submissionStatus||'QUEUED')
const failureCopy=computed(()=>{
 const code=flow.value?.errorCode
 if(code==='INSUFFICIENT_CREDITS')return '积分不足 已有歌词已保留 充值后可继续生成音乐'
 if(code==='WORK_VERSION_CONFLICT')return '内容已经修改 请使用自定义创作继续完善当前作品'
 if(code==='LYRICS_PROVIDER_CONNECT_FAILED')return '暂时连不上歌词服务，创作描述已保留，可以稍后重试。'
 if(code?.startsWith('LYRICS_'))return '歌词暂未生成，故事已保留。可以重试歌词生成，或切换到自定义创作。'
 if(code?.includes('QUOTA'))return '本次创作额度暂不可用 已有内容已保留'
 return '故事与已有歌词都已保留 可以继续制作'
})
function receive(result:QuickCreation|null){
 const previous=flow.value;flow.value=result;issue.value='';emit('resolved',result);emit('busy',!!result&&quickInProgress(result.status))
 if(result&&(previous?.id!==result.id||previous.status!==result.status||previous.revisionId!==result.revisionId||previous.taskId!==result.taskId))emit('updated',result)
 if(result&&props.dialogOpen===undefined&&props.autoOpen&&quickInProgress(result.status)&&openedFlow!==result.id){openedFlow=result.id;setOpen(true)}
}
async function refresh(){
 if(!props.workId||props.submissionPending||polling||!alive)return
 const turn=workSequence
 const current=()=>alive&&turn===workSequence
 polling=true
 let ok=false
 try{
  const result=await latestQuickCreation(props.workId)
  ok=true
  if(current()&&(result||!flow.value&&!props.initialFlow))receive(result)
 }catch{
  if(current())issue.value='进度暂时无法读取 请稍后刷新 原任务不会重复提交'
 }finally{
  if(current()){polling=false;loaded.value=true;emit('checked',ok)}
 }
}
async function retry(){
 if(!flow.value||retrying.value)return
 const turn=workSequence
 const current=()=>alive&&turn===workSequence
 retrying.value=true
 try{
  const result=await retryQuickCreation(props.workId,flow.value.id)
  if(current()){receive(result);setOpen(true)}
 }catch(e){
  if(current())issue.value=e instanceof Error?e.message:'暂时无法继续创作'
 }finally{if(current())retrying.value=false}
}
// 音频已能展示时再收起弹窗，避免“已完成”先到、歌曲结果尚未读取的空白过渡。
watch([flow,()=>props.resultReady],()=>{
 if(flow.value?.status==='SUCCEEDED'&&props.resultReady){
  const waiting=open.value&&document.visibilityState==='visible'
  setOpen(false)
  if(props.autoOpen&&presentedResult!==flow.value.id&&(props.completionMode==='reveal'||waiting)){presentedResult=flow.value.id;emit('view')}
 }
},{flush:'post'})
watch(localOpen,value=>emit('visibility',value))
watch(()=>props.workId,()=>{
 workSequence++;polling=false;retrying.value=false;loaded.value=false
 flow.value=null;issue.value='';openedFlow='';presentedResult='';localOpen.value=false
 if(props.initialFlow?.workId===props.workId)receive(props.initialFlow)
 else void refresh()
})
watch(()=>props.initialFlow,value=>{if(value?.workId===props.workId&&value!==flow.value)receive(value)})
watch(()=>props.awaitingSubmission,value=>{if(value)void refresh()})
onMounted(()=>{if(props.initialFlow)receive(props.initialFlow);else void refresh();document.addEventListener('visibilitychange',visible)})
function visible(){if(document.visibilityState==='visible')void refresh()}
const timer=window.setInterval(()=>{if(document.visibilityState==='visible'&&(!loaded.value||(active.value&&!uncertainLyrics.value)||!!issue.value||props.awaitingSubmission&&!flow.value))void refresh()},4000)
defineExpose({show:()=>setOpen(true),refresh})
onBeforeUnmount(()=>{alive=false;window.clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
</script>
<template>
 <section v-if="workId&&!submissionPending&&(flow&&(!resultReady||active||completionMode==='navigate')||issue||!loaded)" class="quick-progress" aria-live="polite">
  <div class="quick-progress-icon" :class="{active}">♪</div>
  <div class="quick-progress-copy">
   <strong>{{ t(uncertainLyrics?'歌词结果待核对':flow?labels[flow.status]||'正在核对创作进度':'正在核对创作进度') }}</strong>
   <p v-if="flow">{{ t(uncertainLyrics?'歌词服务连接中断，创作描述已保留。可以刷新查看，请勿重复提交。':active?'可以离开页面 在我的音乐中继续查看进度':flow.status==='SUCCEEDED'?(resultReady?'歌曲已保存 可以试听与分享':'正在读取歌曲，请稍候…'):failureCopy) }}</p>
   <p v-if="issue" role="alert">{{ t(issue) }}</p>
   <div class="actions">
    <button v-if="flow&&active" class="button" @click="setOpen(true)">{{ t('查看生成进度') }}</button>
    <button v-if="flow?.status==='SUCCEEDED'&&resultReady&&completionMode==='navigate'" class="button" @click="emit('view')">{{ t('试听歌曲') }}</button>
    <RouterLink v-if="flow?.errorCode==='INSUFFICIENT_CREDITS'" class="button" to="/recharge">{{ t('去充值') }}</RouterLink>
    <RouterLink v-if="flow?.errorCode==='AUDIO_ARCHIVE_FAILED'&&completionMode==='navigate'" :to="`/works/${workId}?mode=quick`">{{ t('查看创作记录') }}</RouterLink>
    <RouterLink v-if="flow&&['FAILED','CANCELLED'].includes(flow.status)" :to="`/create?work=${workId}&mode=custom`">{{ t('自定义创作') }}</RouterLink>
    <RouterLink v-if="uncertainLyrics&&completionMode==='navigate'" class="button" to="/create?mode=quick">{{ t('继续创作一首歌') }}</RouterLink>
    <button class="button secondary" @click="refresh">{{ t('刷新进度') }}</button>
    <button v-if="flow?.canRetry" class="button" :disabled="retrying" @click="retry">{{ t(retrying?'正在继续…':flow.revisionId?'继续生成音乐':'重试歌词生成') }}</button>
   </div>
  </div>
 </section>
 <MusicGenerationDialog v-if="dialogOpen!==undefined||flow" :open="open" :status="dialogStatus" :title="title" :created-at="flow?.createdAt" :sync-issue="!!issue" :error="flow?.status==='FAILED'?failureCopy:issue||submissionIssue" :error-code="flow?.errorCode" quick :result-ready="resultReady" @close="setOpen(false)" @view="setOpen(false);emit('view')"/>
</template>
<style scoped>.quick-progress{display:flex;gap:20px;margin:20px 0;padding:24px;border:1px solid #f0c5bc;border-radius:22px;background:#fff6f2}.quick-progress-icon{font-size:36px;color:#f93822}.quick-progress-copy{min-width:0}.quick-progress strong{font-size:20px}.quick-progress p{color:#806967;line-height:1.7}.quick-progress a{color:#bf3b29;align-self:center}.quick-progress .button{font-size:14px;min-height:40px;padding:9px 18px}.active{animation:pulse 2s ease-in-out infinite}@keyframes pulse{50%{opacity:.5}}@media(prefers-reduced-motion:reduce){.active{animation:none}}</style>
