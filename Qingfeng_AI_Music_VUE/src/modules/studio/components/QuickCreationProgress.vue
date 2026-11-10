<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount,computed} from 'vue'
import {t} from '../../../shared/i18n'
import {latestQuickCreation,retryQuickCreation,quickInProgress,type QuickCreation} from '../api/quick-creation'
const props=defineProps<{workId:string}>(),emit=defineEmits<{checked:[ok:boolean];busy:[value:boolean];updated:[value:QuickCreation]}>()
const flow=ref<QuickCreation|null>(null),issue=ref(''),retrying=ref(false),loaded=ref(false)
let alive=true,polling=false
const labels:Record<string,string>={QUEUED:'创作已排队',WRITING:'正在编写歌词',LYRICS_READY:'歌词已完成 正在准备音乐',SUBMITTING_MUSIC:'正在准备音乐',MUSIC_QUEUED:'音乐已排队',SUBMITTING:'正在提交音乐',RUNNING:'正在制作音乐',SAVING:'正在保存音频',SUBMISSION_UNKNOWN:'提交结果待核对',SUCCEEDED:'你的歌曲已完成',FAILED:'这次创作暂未完成',CANCELLED:'已撤销'}
const active=computed(()=>!!flow.value&&quickInProgress(flow.value.status))
const failureCopy=computed(()=>{
 const code=flow.value?.errorCode
 if(code==='INSUFFICIENT_CREDITS')return '积分不足 已有歌词已保留 充值后可继续生成音乐'
 if(code==='WORK_VERSION_CONFLICT')return '内容已经修改 请使用自定义创作继续完善当前作品'
 if(code?.startsWith('LYRICS_'))return '歌词暂未完成 故事已保留 可以重新编写或使用自定义创作'
 if(code?.includes('QUOTA'))return '本次创作额度暂不可用 已有内容已保留'
 return '故事与已有歌词都已保留 可以继续制作'
})
async function refresh(){if(polling||!alive)return;polling=true;let ok=false;try{const result=await latestQuickCreation(props.workId);ok=true;if(alive){const previous=flow.value?.status;flow.value=result;issue.value='';emit('busy',!!result&&quickInProgress(result.status));if(result&&previous!==result.status)emit('updated',result)}}catch{if(alive&&flow.value)issue.value='进度暂时无法读取 请稍后刷新 原任务不会重复提交'}finally{polling=false;if(alive){loaded.value=true;emit('checked',ok)}}}
async function retry(){if(!flow.value||retrying.value)return;retrying.value=true;try{flow.value=await retryQuickCreation(props.workId,flow.value.id);emit('busy',quickInProgress(flow.value.status));issue.value=''}catch(e){issue.value=e instanceof Error?e.message:'暂时无法继续创作'}finally{retrying.value=false}}
onMounted(()=>{void refresh();document.addEventListener('visibilitychange',visible)})
function visible(){if(document.visibilityState==='visible')void refresh()}
const timer=window.setInterval(()=>{if(document.visibilityState==='visible'&&(!loaded.value||active.value))void refresh()},4000)
defineExpose({refresh})
onBeforeUnmount(()=>{alive=false;window.clearInterval(timer);document.removeEventListener('visibilitychange',visible)})
</script>
<template><section v-if="flow" class="quick-progress" aria-live="polite"><div class="quick-progress-icon" :class="{active}">♪</div><div class="quick-progress-copy"><strong>{{ t(labels[flow.status]||'正在核对创作进度') }}</strong><p>{{ t(active?'可以离开页面 在我的音乐中继续查看进度':flow.status==='SUCCEEDED'?'歌曲已保存 可以试听与分享':failureCopy) }}</p><p v-if="issue" role="alert">{{ t(issue) }}</p><div class="actions"><RouterLink v-if="flow.errorCode==='INSUFFICIENT_CREDITS'" class="button" to="/recharge">{{ t('去充值') }}</RouterLink><RouterLink v-if="flow.status==='FAILED'" :to="`/create?work=${workId}&mode=custom`">{{ t('自定义创作') }}</RouterLink><button class="button secondary" @click="refresh">{{ t('刷新进度') }}</button><button v-if="flow.canRetry" class="button" :disabled="retrying" @click="retry">{{ t(retrying?'正在继续…':flow.revisionId?'继续生成音乐':'重新编写并继续') }}</button><a v-if="flow.revisionId" :href="`/works/${workId}?revision=${flow.revisionId}`">{{ t(flow.status==='SUCCEEDED'?'试听与分享':'查看已有歌词') }}</a></div></div></section></template>
<style scoped>.quick-progress{display:flex;gap:20px;margin:20px 0;padding:24px;border:1px solid #f0c5bc;border-radius:22px;background:#fff6f2}.quick-progress-icon{font-size:36px;color:#f93822}.quick-progress-copy{min-width:0}.quick-progress strong{font-size:20px}.quick-progress p{color:#806967;line-height:1.7}.quick-progress a{color:#bf3b29;align-self:center}.quick-progress .button{font-size:14px;min-height:40px;padding:9px 18px}.active{animation:pulse 2s ease-in-out infinite}@keyframes pulse{50%{opacity:.5}}@media(prefers-reduced-motion:reduce){.active{animation:none}}</style>
