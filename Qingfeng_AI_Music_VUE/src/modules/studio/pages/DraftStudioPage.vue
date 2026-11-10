<script setup lang="ts">
import LyricsTemplatePicker from '../../works/components/LyricsTemplatePicker.vue'
import CreditCostHint from '../../../shared/credits/CreditCostHint.vue'
import { useCredits } from '../../../shared/credits/useCredits'
import { guardCredits } from '../../../shared/credits/credit-gate'
import { normalizeLyricsStructure, lyricsStructureKey, type LyricsStructure } from '../../works/model/lyrics-structure'
import { composeStyleDetailed, splitStyle, styleTail as compileStyleTail } from '../../agent/model/style-compiler'
import { agentLanguage, agentStructure } from '../../agent/model/param-compiler'
import '../styles/inspiration-studio.css'
import StudioAtmosphere from '../components/StudioAtmosphere.vue'
import { t, locale } from '../../../shared/i18n'
import { onMounted, onBeforeUnmount, reactive, ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { ApiError, ensureCreator, currentCreatorId, lastKnownCreatorId, associatedGuestCreatorId } from '../../../shared/http/api-client'
import { readRecovery, writeRecovery, recoveryKey,checkpointRecovery } from '../../../shared/drafts/recovery'
import CreationSteps from '../../../shared/ui/CreationSteps.vue'
import RecoveryCopies from '../../../shared/drafts/RecoveryCopies.vue'
import { loadWork, saveWork, rewriteInspiration, type Work, type DraftInput } from '../api/works'
import { normalizeVocalGender } from '../model/vocals'
import { styleFromInspiration } from '../model/suggestions'
import { inspirationPresets, inspirationSelection, type InspirationSelection } from '../model/inspiration-presets'
import { continuationRequestKey, retireContinuationRequestKey } from '../model/lyrics-continuation'
import { generateLyrics, getLyricsRequest } from '../../works/api/detail'
import { clearAgentBrief, readAgentBrief, shouldApplyAgentBrief } from '../../agent/model/brief'

const storyFlowVersion = 3
interface Recovery { structure?:LyricsStructure; flowVersion?:number; draft:DraftInput; language:string; part:number; draftKey:string; workId?:string; version?:number; dirty:boolean; conflict?:boolean }
function validRecovery(value:unknown):value is Recovery {
  const v = value as Recovery
  return !!v && !!v.draft && ['recipient','occasion','story','message','style','title'].every(k => typeof v.draft[k as keyof DraftInput] === 'string') && ['zh-CN','en','mixed'].includes(v.language) && /^[0-9a-f-]{36}$/i.test(v.draftKey)
}
const route=useRoute(), router=useRouter(), initialized=ref(false), ready=ref(false), aiReady=ref(false), existing=ref<Work>(), baseline=ref(''), saving=ref(false), composing=ref(false), error=ref(''), notice=ref(''), conflict=ref(false), localSaved=ref(false), creditShort=ref(false)
const { credits, refreshCredits } = useCredits()
const language=ref<string>(locale.value)
// 已编译进风格文本的参数说明，只读展示，便于提交前核对。
const compiledParams=computed(()=>splitStyle(draft.style).tail)
// 因为作品描述长度放不下而没能提交的参数，明确告诉用户，不做静默丢弃。
const omittedParams=ref<string[]>([])
const structure=ref<LyricsStructure>(normalizeLyricsStructure())
const validationError=ref(''), selectedHint=ref(-1)
const rewriting=ref(false), rewriteError=ref(''), rewriteStatus=ref('')
const rewriteKey=ref('')
const rewriteBackup=ref<{source:string;result:string}>()
const inspirationPlaceholder=computed(()=>t('写下你的故事、情绪、场景或主题……'))
const presetBackup=ref<{before:InspirationSelection;filled:InspirationSelection;previousHint:number}>()
const canUndoPreset=computed(()=>!!presetBackup.value&&draft.story===presetBackup.value.filled.story&&draft.style===presetBackup.value.filled.style&&draft.vocalGender===presetBackup.value.filled.vocalGender)
const continuationFailed=ref(false)
const resetting=ref(false)
const draft=reactive<DraftInput>({recipient:'',occasion:String(route.query.occasion||''),story:'',message:'',style:styleFromInspiration(route.query.style),title:'',vocalGender:'AUTO'})
watch(()=>draft.style,style=>{selectedHint.value=inspirationPresets.findIndex((preset,index)=>index<5&&preset.style===style)},{flush:'sync'})
async function chooseInspiration(index:number) {
  if(!initialized.value||resetting.value||composing.value||rewriting.value)return
  const preset=inspirationPresets[index];if(!preset)return
  const before={story:draft.story,style:draft.style,vocalGender:draft.vocalGender},filled=inspirationSelection(preset,t),previousHint=selectedHint.value
  checkpointRecovery(cacheKey,recoveryValue())
  Object.assign(draft,filled);selectedHint.value=index;presetBackup.value={before,filled,previousHint}
  rewriteBackup.value=undefined;rewriteError.value='';rewriteStatus.value='';validationError.value=''
  await nextTick();document.querySelector<HTMLTextAreaElement>('#inspiration-input')?.focus()
}
function undoInspiration() {
  const backup=presetBackup.value;if(!backup||!canUndoPreset.value)return
  Object.assign(draft,backup.before);selectedHint.value=backup.previousHint;presetBackup.value=undefined
}
const snapshot=():DraftInput=>({...draft,recipient:draft.recipient.trim()})
const dirty=computed(()=>initialized.value && JSON.stringify(snapshot())!==baseline.value)
const valid=computed(()=>!!(draft.story.trim()||draft.message.trim()) && !!draft.style.trim())
let draftKey:string=crypto.randomUUID(), cacheKey='', alive=true, timer:ReturnType<typeof setTimeout>|undefined, inflight:Promise<Work|undefined>|undefined, internalRoute=false
let recoveredVersion:number|undefined,recoveredWorkId:string|undefined
let initSequence=0,internalContext:string|undefined
const contextOf=(query:Record<string,unknown>)=>query.work?`work:${String(query.work)}`:query.draft?`draft:${String(query.draft)}`:'new'
const asDraft=(work:DraftInput):DraftInput=>({recipient:work.recipient,occasion:work.occasion,story:work.story,message:work.message,style:work.style,title:work.title,vocalGender:normalizeVocalGender(work.vocalGender)})
function recoveryValue():Recovery{return {flowVersion:storyFlowVersion,draft:{...draft},language:language.value,structure:{...structure.value},part:0,draftKey,workId:existing.value?.id||recoveredWorkId,version:existing.value?.version??recoveredVersion,dirty:dirty.value,conflict:conflict.value}}
function remember() {
  if(!cacheKey||!initialized.value)return
  const value=recoveryValue();localSaved.value=writeRecovery(cacheKey,value)
  const owner=currentCreatorId(),source=associatedGuestCreatorId()
  const belongs=owner&&(cacheKey.startsWith(recoveryKey(owner,'story',''))||source&&cacheKey.startsWith(recoveryKey(source,'story','')))
  if(existing.value&&belongs&&cacheKey!==recoveryKey(owner,'story',existing.value.id))writeRecovery(recoveryKey(owner,'story',existing.value.id),value)
}
function restoreCopy(value:Recovery){
 if(!initialized.value||resetting.value||saving.value||composing.value)return
 if(!checkpointRecovery(cacheKey,recoveryValue())){error.value='无法保留当前编辑，已停止恢复。请先复制内容，或清理浏览器存储后重试。';return}
 clearTimeout(timer)
 Object.assign(draft,asDraft(value.draft));language.value=value.language;structure.value=normalizeLyricsStructure(value.structure)
 conflict.value=!!existing.value&&value.version!==undefined&&value.version!==existing.value.version
 error.value=conflict.value?'另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。':''
 notice.value='已恢复本机副本，替换前的编辑也已另存。';remember();if(!conflict.value)schedule()
}
function schedule() {
  remember(); clearTimeout(timer)
  if (dirty.value && valid.value && ready.value && !conflict.value && !composing.value) timer=setTimeout(()=>{void persist().catch(()=>{})},1200)
}
watch([draft,language,structure],schedule,{deep:true,flush:'sync'})
async function initialize() {
  const turn=++initSequence,query={...route.query}
  // Agent 创作整理好的灵感只预填新建草稿；成功接手并写入本机副本后才清除，失败时保留供刷新重试。
  const agentBrief=shouldApplyAgentBrief(query)?readAgentBrief():undefined
  // Agent 识别到的参数在这里一次性编译：补充描述进风格文本，语言与段落各有自己的去处。
  const briefTail=compileStyleTail(agentBrief?.params)
  const briefComposition=composeStyleDetailed(agentBrief?.style||styleFromInspiration(query.style),briefTail)
  const briefStyle=briefComposition.style
  const briefLanguage=agentLanguage(agentBrief?.params)
  const briefStructure=agentStructure(agentBrief?.params)
  let agentBriefApplied=false
  rewriting.value=false;rewriteError.value='';rewriteStatus.value='';rewriteBackup.value=undefined
  presetBackup.value=undefined
  resetting.value=true;ready.value=false;clearTimeout(timer)
  // 切换作品前先收尾正在保存的快照；旧响应不可把新建页面跳回旧作品。
  if(inflight)try{await inflight}catch{/* 旧作品的本机副本仍保留。 */}
  if(turn!==initSequence)return
  clearTimeout(timer); initialized.value=false; error.value=''; notice.value=''; conflict.value=false; ready.value=false;saving.value=false
  composing.value=false;language.value=locale.value;structure.value=normalizeLyricsStructure();selectedHint.value=-1;validationError.value='';existing.value=undefined;recoveredVersion=undefined;recoveredWorkId=undefined;cacheKey='';draftKey=crypto.randomUUID()
  Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:agentBrief?.story||'',message:'',style:briefStyle,title:'',vocalGender:agentBrief?.params?.vocal??'AUTO'});baseline.value=JSON.stringify(snapshot())
  if(briefLanguage)language.value=briefLanguage
  if(briefStructure)structure.value=briefStructure
  omittedParams.value=briefComposition.dropped
  try {
    await ensureCreator()
    const system=await getSystemInfo();if(turn!==initSequence)return;ready.value=system.capabilities.workPersistence; aiReady.value=system.capabilities.lyricsGeneration
    if (!ready.value) throw new Error('暂时无法保存故事，请稍后再试。')
    const id=String(query.work||'')
    cacheKey=recoveryKey(currentCreatorId(),'story',id||String(query.draft||draftKey))
    const local=readRecovery(cacheKey,validRecovery)
    const serverId=id||local?.workId
    const found=serverId?await loadWork(serverId):undefined
    if(turn!==initSequence)return
    existing.value=found
    recoveredVersion=existing.value?.version;recoveredWorkId=existing.value?.id
    if (existing.value) {Object.assign(draft,asDraft(existing.value));baseline.value=JSON.stringify(asDraft(existing.value))}
    else {Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:agentBrief?.story||'',message:'',style:briefStyle,title:'',vocalGender:agentBrief?.params?.vocal??'AUTO'});baseline.value=JSON.stringify(snapshot());draftKey=crypto.randomUUID();agentBriefApplied=!!agentBrief}
    if (local) {
      language.value=local.language;structure.value=normalizeLyricsStructure(local.structure);draftKey=local.draftKey
      if (local.dirty) {
        Object.assign(draft,asDraft(local.draft));notice.value='已恢复本机尚未保存的内容。'
        conflict.value=!!local.conflict || (!!existing.value && local.version!==undefined && local.version!==existing.value.version)
        if (conflict.value) error.value='另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。'
      }
    }
    if(!id){const previousKey=cacheKey;cacheKey=recoveryKey(currentCreatorId(),'story',draftKey);const target=`draft:${draftKey}`;internalContext=target;internalRoute=true;try{await router.replace({path:'/create',query:{...query,draft:draftKey}})}finally{internalRoute=false;if(internalContext===target)internalContext=undefined};if(turn!==initSequence)return;if(previousKey!==cacheKey&&local)writeRecovery(cacheKey,local)}
    // 积分余额与歌词价格都从后端读取；读不到时提示整块不显示，不猜测数字。
    void refreshCredits().catch(()=>{})
  } catch(e) {
    if(turn!==initSequence)return
    ready.value=false;error.value=e instanceof Error?e.message:'服务暂时不可用'
    if(e instanceof ApiError && (e.status===0||e.status===408||e.status>=500)){
      const owner=currentCreatorId()||lastKnownCreatorId()
      if(owner){cacheKey=recoveryKey(owner,'story',String(query.work||query.draft||draftKey));const local=readRecovery(cacheKey,validRecovery)
        if(local){Object.assign(draft,asDraft(local.draft));language.value=local.language;structure.value=normalizeLyricsStructure(local.structure);draftKey=local.draftKey;recoveredVersion=local.version;recoveredWorkId=local.workId;baseline.value='';notice.value='服务暂时连接不上，已恢复本机内容；连接后可重试保存。'}
      }
      ready.value=false
    }
  }
  finally {
    if(turn===initSequence){
      // 旧向导的问题参数统一回到单页灵感编辑，不删除旧草稿内容。
      if(route.query.question!==undefined||route.query.part!==undefined){
        internalRoute=true
        try{await router.replace({path:'/create',query:{...route.query,part:undefined,question:undefined}})}finally{internalRoute=false}
      }
      if(turn!==initSequence||!alive)return
      initialized.value=true;resetting.value=false;remember()
      if(agentBriefApplied)clearAgentBrief()
      if(!conflict.value)schedule()
    }
  }
}
async function identityChanged() {
  if(!initialized.value)return
  try{await ensureCreator()}catch(e){ready.value=false;error.value=e instanceof Error?e.message:'身份核对失败，请保留当前编辑后重试。';return}
  const owner=currentCreatorId();if(!owner)return
  const next=recoveryKey(owner,'story',String(route.query.work||route.query.draft||draftKey))
  if(cacheKey===next)return
  clearTimeout(timer);remember()
  const source=associatedGuestCreatorId()
  if(source&&cacheKey.startsWith(recoveryKey(source,'story',''))) {
    if(!checkpointRecovery(next,recoveryValue())){ready.value=false;error.value='本机草稿备份失败，请保留当前编辑，恢复浏览器存储后重试。';return}
  }
  await initialize()
}
onMounted(()=>window.addEventListener('qingfeng:identity-changed',identityChanged))
onBeforeUnmount(()=>window.removeEventListener('qingfeng:identity-changed',identityChanged))
onMounted(initialize)
watch(()=>contextOf(route.query),context=>{if(context!==internalContext)void initialize()})
function persist():Promise<Work|undefined> {
  clearTimeout(timer)
  if (inflight) return inflight
  if (!ready.value || !valid.value || conflict.value) return Promise.resolve(existing.value)
  if (existing.value && !dirty.value) return Promise.resolve(existing.value)
  const submitted=snapshot(),targetWork=existing.value,requestDraftKey=draftKey,turn=initSequence
  saving.value=true
  inflight=(async()=>{
    try {
      const saved=await saveWork(submitted,targetWork,requestDraftKey)
      if(!alive||turn!==initSequence)return saved
      existing.value=saved;baseline.value=JSON.stringify(asDraft(saved));error.value=''
      // 新建向导保留同一个draft地址，浏览器后退不会误变成另一份新草稿。
      remember()
      return saved
    } catch(e) {
      if(!alive||turn!==initSequence)throw e
      conflict.value=e instanceof ApiError && e.code==='WORK_VERSION_CONFLICT'
      error.value=conflict.value?'另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。':e instanceof Error?e.message:'保存失败，请重试'
      remember();throw e
    } finally {inflight=undefined;if(alive&&turn===initSequence){saving.value=false;if(dirty.value&&!error.value)schedule()}}
  })()
  return inflight
}
async function flush() {
  const turn=initSequence
  const check=()=>{if(!alive||turn!==initSequence)throw new Error('页面已切换，已停止后续操作。')}
  if(!ready.value||!valid.value||conflict.value)throw new Error('请先解决保存问题，再继续创作。')
  if(inflight)await inflight
  check()
  while(!existing.value||dirty.value){const saved=await persist();check();if(!saved||conflict.value)throw new Error('请先解决保存问题，再继续创作。')}
  return existing.value
}
async function retry(){const turn=initSequence;try{if(!ready.value){await initialize();return}await flush();if(turn===initSequence)notice.value='故事已保存。'}catch{/* 错误或旧页面的副本均保留。 */}}
async function resolveConflict(useLocal:boolean){
  if(!existing.value)return
  const id=existing.value.id,turn=initSequence
  if(!window.confirm(t(useLocal?'将用本机内容更新服务器故事，确定继续吗？':'载入服务器版本将替换本机修改，确定继续吗？')))return
  try{const latest=await loadWork(id);if(!alive||turn!==initSequence)return;existing.value=latest;baseline.value=JSON.stringify(asDraft(latest));if(!useLocal)Object.assign(draft,asDraft(latest));conflict.value=false;error.value='';remember();await flush()}
  catch(e){if(alive&&turn===initSequence)error.value=e instanceof Error?e.message:'保存失败，请重试'}
}
watch(()=>[draft.story,draft.message],()=>{validationError.value='';continuationFailed.value=false})
function rejectAnswer(message:string){validationError.value=message;document.querySelector<HTMLTextAreaElement>('#inspiration-input')?.focus()}
/**
 * 改写每次都会调用付费模型：每日免费额度内不扣积分，用完后每次扣一次积分。
 * 余额不足时先刷新一次再判断，避免用过期数字拦下刚充值完的用户；后端仍会再次校验。
 */
async function blockedByRewrite(){
  const gate=await guardCredits(()=>credits.value,refreshCredits,view=>view.rewriteFreeRemaining>0?0:view.rewriteCost)
  if(!gate.blocked)return false
  rewriteError.value=t('积分不足，当前余额 {balance} 积分，改写需要 {cost} 积分。',{balance:gate.balance as number,cost:gate.cost})
  return true
}
async function rewriteIdea() {
  if(rewriting.value||composing.value||!initialized.value||resetting.value||conflict.value)return
  if(!draft.story.trim()){rejectAnswer('请先写下一点创作灵感，再使用AI改写。');return}
  const source=draft.story, requestedLanguage=language.value, turn=initSequence, owner=currentCreatorId()
  rewriting.value=true;rewriteError.value='';rewriteStatus.value=''
  try {
    if(await blockedByRewrite())return
    const key=rewriteKey.value||(rewriteKey.value=crypto.randomUUID())
    const result=await rewriteInspiration(source,requestedLanguage,key)
    rewriteKey.value=''
    if(!alive||turn!==initSequence||owner!==currentCreatorId())return
    if(draft.story!==source||language.value!==requestedLanguage||conflict.value){rewriteStatus.value='内容已修改，本次改写未覆盖当前输入，请重新改写。';return}
    if(!checkpointRecovery(cacheKey,recoveryValue())){rewriteError.value='无法备份原文，本次改写未覆盖内容。';return}
    rewriteBackup.value={source,result:result.text};draft.story=result.text
    rewriteStatus.value='已优化创作描述，你可以继续编辑或恢复原文。'
  } catch(e) {
    // 后端已把这次请求记为失败：用同一个键重试只会得到「上次未完成」，此时换新键重新发起。
    if(e instanceof ApiError&&e.code==='REWRITE_REQUEST_FAILED')rewriteKey.value=''
    if(alive&&turn===initSequence&&owner===currentCreatorId())rewriteError.value=e instanceof Error?e.message:'AI改写失败，请稍后重试；原文仍会保留。'
  } finally {if(alive&&turn===initSequence)rewriting.value=false; void refreshCredits().catch(()=>{})}
}
function restoreRewrite() {
  const backup=rewriteBackup.value
  if(!backup||rewriting.value||draft.story!==backup.result)return
  if(!checkpointRecovery(cacheKey,recoveryValue())){rewriteError.value='无法备份原文，本次改写未覆盖内容。';return}
  draft.story=backup.source;rewriteBackup.value=undefined;rewriteError.value='';rewriteStatus.value='已恢复改写前的原文。'
}
/**
 * 积分不足时先刷新一次余额再判断，避免用过期数字拦下刚充值完的用户；
 * 刷新后仍然不足才拦截。后端在调用模型前还会再校验一次余额，前端拦截不是唯一防线。
 */
async function blockedByCredits(){
  const gate=await guardCredits(()=>credits.value,refreshCredits)
  if(!gate.blocked)return false
  creditShort.value=true
  error.value=t('积分不足，当前余额 {balance} 积分，生成需要 {cost} 积分。',{balance:gate.balance as number,cost:gate.cost})
  return true
}
async function next() {
  if(rewriting.value)return
  if(!draft.story.trim()&&!draft.message.trim()){rejectAnswer('请先写下一点创作灵感。');return}
  await continueMaking()
}
async function continueMaking() {
  if(composing.value || rewriting.value || !ready.value)return
  if(!valid.value){rejectAnswer('请先写下一点创作灵感。');return}
  if(await blockedByCredits())return
  composing.value=true;error.value='';notice.value='';creditShort.value=false
  continuationFailed.value=false
  const turn=initSequence
  let writing=false
  try {
    const work=await flush()
    if(!work||!alive||turn!==initSequence)return
    writing=true
    let key=continuationRequestKey(work.id,work.version,language.value,lyricsStructureKey(structure.value))
    const previous=await getLyricsRequest(work.id,key)
    if(previous.status==='SUCCEEDED'&&previous.revisionId){if(alive&&turn===initSequence)await router.push({path:`/works/${work.id}`,query:{revision:previous.revisionId,language:language.value,templateId:structure.value.templateId,chorusFirst:String(structure.value.chorusFirst)}});return}
    if(previous.status==='PENDING'||previous.status==='UNKNOWN')throw new Error('歌词仍在编写中，可以稍后刷新编写状态。')
    if(previous.status==='FAILED'){retireContinuationRequestKey(work.id,work.version,language.value,lyricsStructureKey(structure.value));key=continuationRequestKey(work.id,work.version,language.value,lyricsStructureKey(structure.value))}
    const revision=await generateLyrics(work.id,work.version,key,language.value,'',undefined,{...structure.value})
    if(alive&&turn===initSequence)await router.push({path:`/works/${work.id}`,query:{revision:revision.id,language:language.value,templateId:structure.value.templateId,chorusFirst:String(structure.value.chorusFirst)}})
  } catch(e){if(alive&&turn===initSequence){continuationFailed.value=writing;error.value=e instanceof Error?e.message:'歌词生成失败，请稍后再试'}}
  finally{if(turn===initSequence){composing.value=false;void refreshCredits().catch(()=>{})}}
}
async function returnToLyrics(){try{const work=await flush();if(work)await router.push({path:`/works/${work.id}`,query:{revision:String(route.query.returnRevision||'')}})}catch{/* 保存错误会显示在故事表单。 */}}
const permitLeave=()=>!dirty.value||localSaved.value||window.confirm(t('当前内容尚未保存，确定离开吗？'))
onBeforeRouteLeave(permitLeave)
onBeforeRouteUpdate(()=>internalRoute||!composing.value&&permitLeave())
const unload=(e:BeforeUnloadEvent)=>{remember();if(dirty.value&&!localSaved.value){e.preventDefault();e.returnValue=''}}
const online=()=>{if(ready.value&&!conflict.value)void retry()}
onMounted(()=>{window.addEventListener('beforeunload',unload);window.addEventListener('online',online)})
onBeforeUnmount(()=>{remember();alive=false;initSequence++;clearTimeout(timer);window.removeEventListener('beforeunload',unload);window.removeEventListener('online',online)})
</script>
<template>
  <div class="story-studio inspiration-studio single-inspiration-studio">
    <StudioAtmosphere/>
    <CreationSteps :current="0"/>
    <div class="story-introduction">
      <h1>{{ t('从这一刻起，你也是歌手。') }}</h1>
      <p class="studio-introduction-copy">{{ t('用几句话表达你的灵感，AI 会为你写成歌词。') }}</p>
      <span class="autosave-state" role="status">{{ saving?t('正在保存…'):dirty?localSaved?t('已保留在本机'):t('有尚未保存的修改'):existing?t('已保存'):t('填写后自动保存') }}</span>
    </div>
    <RecoveryCopies v-if="initialized&&cacheKey" :storage-key="cacheKey" :validate="validRecovery" @restore="restoreCopy"/>
    <section class="draft-form story-focus">
      <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
      <div v-if="error" class="notice error" role="alert"><p>{{ t(error) }} <RouterLink v-if="creditShort" class="text-button" to="/recharge">{{ t('去充值') }}</RouterLink></p><div class="actions">
        <template v-if="conflict"><button class="button secondary" type="button" @click="resolveConflict(true)">{{ t('用本机内容继续') }}</button><button class="button secondary" type="button" @click="resolveConflict(false)">{{ t('载入服务器版本') }}</button></template>
        <button v-else class="button secondary" type="button" :disabled="saving||composing" @click="continuationFailed?continueMaking():retry()">{{ t(continuationFailed?'重新编写歌词':'重试保存') }}</button>
      </div></div>
      <p v-if="initialized&&!localSaved" class="save-info">{{ t('浏览器无法保存本机副本，请确认显示已保存后再离开。') }}</p>
      <form @submit.prevent="next" :aria-label="t('写下创作灵感')">
        <fieldset class="draft-fields" :disabled="!initialized||composing||resetting">
          <div class="inspiration-input-box" :class="{'is-invalid':validationError}">
            <div class="inspiration-editor-toolbar">
              <div class="inspiration-rewrite-tools">
                <div class="inspiration-rewrite-action">
                  <button type="button" class="inspiration-label inspiration-rewrite" :disabled="rewriting||!ready||!aiReady||conflict" :aria-busy="rewriting" aria-describedby="inspiration-rewrite-purpose" @click="rewriteIdea"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3ZM21 2v5m-2.5-2.5h5"/></svg>{{ t(rewriting?'AI改写中…':'AI改写') }}</button>
                  <span id="inspiration-rewrite-purpose" class="inspiration-rewrite-purpose">{{ t('帮你把想法写得更具体') }}</span>
                  <CreditCostHint v-if="credits&&credits.enabled&&(credits.rewriteCost>0||credits.rewriteFreeRemaining>0)" class="inspiration-rewrite-cost" :cost="credits.rewriteCost" :balance="credits.balance" :free-remaining="credits.rewriteFreeRemaining" :busy="rewriting" variant="rewrite"/>
                </div>
                <button v-if="rewriteBackup&&draft.story===rewriteBackup.result" class="inspiration-restore" type="button" :disabled="rewriting" @click="restoreRewrite">{{ t('恢复原文') }}</button>
              </div>
              <LyricsTemplatePicker v-model="structure" :disabled="composing||rewriting||resetting||!initialized"/>
            </div>
            <textarea id="inspiration-input" v-model="draft.story" maxlength="4000" :aria-label="t('写下创作灵感')" :placeholder="inspirationPlaceholder" :aria-invalid="!!validationError||undefined" :aria-describedby="validationError?'inspiration-guidance story-validation':'inspiration-guidance'"></textarea>
            <p v-if="compiledParams" class="studio-agent-params">
              <span class="studio-agent-params-title">{{ t('已识别参数') }}</span>
              <span class="studio-agent-params-value">{{ compiledParams }}</span>
            </p>
            <p v-if="omittedParams.length" class="studio-agent-omitted">{{ t('已省略') }}：{{ omittedParams.join('、') }}<small>{{ t('作品描述最多 80 字，放不下的参数没有提交。') }}</small></p>
            <div class="inspiration-input-footer">
              <span class="inspiration-counter">{{ draft.story.length }} / 4000</span>
              <label class="inspiration-language">
                <span class="inspiration-language-title"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a17 17 0 0 1 0 18 17 17 0 0 1 0-18Z"/></svg>{{ t('歌词语言') }}</span>
                <span class="inspiration-language-select"><select v-model="language" name="lyric-language" :aria-label="t('歌词语言')"><option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option><option value="mixed">{{ t('中英混合') }}</option></select><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4"/></svg></span>
              </label>
            </div>
            <p v-if="rewriteError" class="inspiration-rewrite-message error" role="alert">{{ t(rewriteError) }}</p>
            <p v-else-if="rewriteStatus" class="inspiration-rewrite-message" role="status">{{ t(rewriteStatus) }}</p>
          </div>
          <p v-if="validationError" id="story-validation" class="story-validation" role="alert">{{ t(validationError) }}</p>
          <div class="inspiration-examples" role="group" :aria-label="t('选择创作风格')"><span>{{ t('选一种风格') }}</span><button v-for="(example,index) in inspirationPresets" :key="example.label" type="button" :disabled="rewriting" :aria-pressed="selectedHint===index" @click="chooseInspiration(index)">{{ t(example.label) }}</button></div>
          <p id="inspiration-guidance" class="save-info">{{ t('点击标签填入创作描述，你可以继续修改，补充想唱的主题。') }} <button v-if="canUndoPreset" type="button" class="text-button" @click="undoInspiration">{{ t('撤销填入') }}</button></p>
          <details v-if="draft.recipient||draft.occasion||draft.message" class="legacy-inspiration-context"><summary>{{ t('已保留的旧版作品信息') }}</summary><p v-if="draft.recipient">{{ t('收件人：') }}{{ draft.recipient }}</p><p v-if="draft.occasion">{{ draft.occasion }}</p><p v-if="draft.message" class="legacy-message">{{ draft.message }}</p></details>
          <div class="creation-action-bar story-action-bar"><button class="button creation-primary" type="submit" :disabled="!initialized||composing||rewriting||resetting||!ready||!aiReady||conflict">{{ composing?t('正在编写歌词…'):t('生成歌词') }}<span v-if="!composing" aria-hidden="true">→</span></button><CreditCostHint v-if="credits&&credits.enabled&&credits.lyricsCost>0" :cost="credits.lyricsCost" :balance="credits.balance" :busy="composing"/></div>
          <p class="inspiration-next-note">{{ t('先生成歌词，修改满意后再生成音乐。') }}</p>
          <div v-if="route.query.returnRevision" class="story-secondary-actions"><button type="button" class="text-button return-to-lyrics" @click="returnToLyrics">{{ t('返回歌词编辑') }}</button></div>
        </fieldset>
      </form>
      <p v-if="initialized&&!aiReady" class="notice">{{ t('歌词生成暂不可用，请稍后再试或手动填写') }} <RouterLink v-if="existing" :to="`/works/${existing.id}`">{{ t('手动填写歌词') }}</RouterLink></p>
    </section>
    <p class="studio-privacy-note">{{ t('灵感和草稿仅自己可见，分享由你主动创建。') }}</p>
  </div>
</template>
