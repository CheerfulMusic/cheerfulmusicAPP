<script setup lang="ts">
import CreationMusicChoices from '../components/CreationMusicChoices.vue'
import QuickInspirationPanel from '../components/QuickInspirationPanel.vue'
import {appendInspiration,creationMode,chooseCreationStyle,inspirationLimit,type CreationMode} from '../model/creation-mode'
import {legacyPreferences,validMusicPreferences} from '../../works/model/music-preferences'
import {loadMusicCatalog,loadMusicSettings,saveMusicSettings,type MusicCatalog,type MusicPreferences} from '../../works/api/music-settings'
import QuickCreationProgress from '../components/QuickCreationProgress.vue'
import LyricsTemplatePicker from '../../works/components/LyricsTemplatePicker.vue'
import {useQuickSession} from '../model/use-quick-session'
import {followQuickInBackground} from '../model/background-quick'
import {quickInProgress} from '../api/quick-creation'
import CreditCostHint from '../../../shared/credits/CreditCostHint.vue'
import { useCredits } from '../../../shared/credits/useCredits'
import { guardCredits } from '../../../shared/credits/credit-gate'
import { normalizeLyricsStructure, lyricsStructureKey, type LyricsStructure } from '../../works/model/lyrics-structure'
import { composeStyleDetailed, splitStyle, styleTail as compileStyleTail } from '../../agent/model/style-compiler'
import { agentLanguage, agentStructure } from '../../agent/model/param-compiler'
import '../styles/inspiration-studio.css'
import StudioAtmosphere from '../components/StudioAtmosphere.vue'
import { t, locale } from '../../../shared/i18n'
import { nextTick, onMounted, onBeforeUnmount, reactive, ref, computed, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { getSystemInfo, getPhotoLyricsAvailable, usesLocalPhotoCapability } from '../../../shared/http/system'
import { ApiError, ensureCreator, currentCreatorId, lastKnownCreatorId, associatedGuestCreatorId } from '../../../shared/http/api-client'
import { readRecovery, writeRecovery, recoveryKey,checkpointRecovery } from '../../../shared/drafts/recovery'
import { loadWork, saveWork, rewriteInspiration, type Work, type DraftInput } from '../api/works'
import { normalizeVocalGender } from '../model/vocals'
import { styleFromInspiration } from '../model/suggestions'
import { inspirationPresets, type InspirationPreset } from '../model/inspiration-presets'
import { continuationRequestKey, existingContinuationRequestKey, retireContinuationRequestKey } from '../model/lyrics-continuation'
import { generateLyrics, getLyricsRequest, getUsage, type Allowance } from '../../works/api/detail'
import { clearAgentBrief, readAgentBrief, shouldApplyAgentBrief } from '../../agent/model/brief'

const storyFlowVersion = 3
interface Recovery { mode?:CreationMode;preferences?:MusicPreferences;structure?:LyricsStructure; flowVersion?:number; draft:DraftInput; language:string; part:number; draftKey:string; workId?:string; version?:number; dirty:boolean; conflict?:boolean }
function validRecovery(value:unknown):value is Recovery {
  const v = value as Recovery
  return !!v && !!v.draft && ['recipient','occasion','story','message','style','title'].every(k => typeof v.draft[k as keyof DraftInput] === 'string') && ['zh-CN','en','mixed'].includes(v.language) && /^[0-9a-f-]{36}$/i.test(v.draftKey)
}
const route=useRoute(), router=useRouter(), initialized=ref(false), ready=ref(false), aiReady=ref(false), existing=ref<Work>(), baseline=ref(''), saving=ref(false), composing=ref(false), error=ref(''), notice=ref(''), conflict=ref(false), localSaved=ref(true), allowance=ref<Allowance>(), creditShort=ref(false)
const language=ref<string>(locale.value)
const mode=ref<CreationMode>(creationMode(route.query.mode,!!route.query.work)),quickReady=ref(false)
const photoReady=ref(false), photoWork=ref(false)
const quickSession=useQuickSession()
const {flow:quickFlow,workId:quickWorkId,title:quickTitle,locked:quickLocked,dialogOpen:quickDialogOpen,initialStatus:quickInitialStatus,submissionPending:quickSubmissionPending,unknown:quickUnknown,issue:quickIssue,canResume:quickCanResume}=quickSession
function setQuickDialogOpen(open:boolean){if(open)quickSession.show();else quickSession.close()}
async function viewQuickResult(){
 if(!quickWorkId.value||quickFlow.value?.status!=='SUCCEEDED')return
 await router.push({path:`/works/${quickWorkId.value}`,query:{quick:quickFlow.value.id,mode:'quick',language:quickSession.intent.value?.language||language.value}})
}
const musicCatalog=ref<MusicCatalog>(),preferences=ref<MusicPreferences>(),musicBaseline=ref(''),musicIssue=ref(''),musicPersisted=ref(false)
const customTouched=ref(false)
const musicChanged=computed(()=>!!preferences.value&&JSON.stringify(preferences.value)!==musicBaseline.value)
async function changeMode(next:CreationMode){if(composing.value||rewriting.value||quickLocked.value)return;mode.value=next;await router.replace({query:{...route.query,mode:next}})}
const defaultStructure = {templateId:'AAB',chorusFirst:false} as const satisfies LyricsStructure
const structure=ref<LyricsStructure>({...defaultStructure})
let legacyStructure:LyricsStructure|undefined
const { credits, refreshCredits } = useCredits()
const compiledParams=computed(()=>splitStyle(draft.style).tail)
const omittedParams=ref<string[]>([])
const validationError=ref('')
const inspirationOpen=ref(false)
const inspirationInput=ref<HTMLTextAreaElement>()
const rewriting=ref(false), rewriteError=ref(''), rewriteStatus=ref('')
const rewriteKey=ref('')
const rewriteBackup=ref<{source:string;result:string}>()
const inspirationPlaceholder=computed(()=>t('写下你的故事、情绪、场景或主题……'))
const continuationFailed=ref(false)
const resetting=ref(false)
const draft=reactive<DraftInput>({recipient:'',occasion:String(route.query.occasion||''),story:'',message:'',style:styleFromInspiration(route.query.style),title:'',vocalGender:'AUTO'})
const inspirationEditsDisabled=computed(()=>!initialized.value||resetting.value||composing.value||rewriting.value||quickLocked.value)
watch(mode,()=>{inspirationOpen.value=false})

function chooseStyle(preset:InspirationPreset) {
  if(inspirationEditsDisabled.value)return
  const selected=chooseCreationStyle(draft,preset,t)
  if(!selected){validationError.value='请先缩短创作描述，为风格提示留出空间。';return}
  Object.assign(draft,selected)
  syncStylePreferences()
  rewriteBackup.value=undefined;rewriteError.value='';rewriteStatus.value='';validationError.value=''
}

async function useInspiration(description:string) {
  if(inspirationEditsDisabled.value)return
  const story=appendInspiration(draft.story,description)
  if(story===null){
    validationError.value='请先缩短创作描述，为灵感留出空间。'
    return
  }
  draft.story=story
  inspirationOpen.value=false
  rewriteBackup.value=undefined;rewriteError.value='';rewriteStatus.value='';validationError.value=''
  await nextTick()
  inspirationInput.value?.focus()
  inspirationInput.value?.setSelectionRange(story.length,story.length)
}
function syncStylePreferences(){
  if(!preferences.value)return
  const defaults={...legacyPreferences({...draft,id:'',version:0,createdAt:'',updatedAt:''}),freeText:''}
  if(customTouched.value||musicPersisted.value)preferences.value={...preferences.value,vocalGender:draft.vocalGender,recipeId:null,choices:{...preferences.value.choices,genre:defaults.choices.genre||[]}}
  else{musicBaseline.value=JSON.stringify(defaults);preferences.value=defaults}
}
const snapshot=():DraftInput=>({...draft,recipient:draft.recipient.trim()})
const dirty=computed(()=>initialized.value && JSON.stringify(snapshot())!==baseline.value)
const valid=computed(()=>(photoWork.value||!!(draft.story.trim()||draft.message.trim())) && !!draft.style.trim())
let draftKey:string=crypto.randomUUID(), cacheKey='', alive=true, timer:ReturnType<typeof setTimeout>|undefined, inflight:Promise<Work|undefined>|undefined, internalRoute=false
let recoveredVersion:number|undefined,recoveredWorkId:string|undefined
let localPending=false
let initSequence=0,internalContext:string|undefined
const contextOf=(query:Record<string,unknown>)=>query.work?`work:${String(query.work)}`:query.draft?`draft:${String(query.draft)}`:'new'
const asDraft=(work:DraftInput):DraftInput=>({recipient:work.recipient,occasion:work.occasion,story:work.story,message:work.message,style:work.style,title:work.title,vocalGender:normalizeVocalGender(work.vocalGender)})
function recoveryValue():Recovery{return {mode:mode.value,preferences:preferences.value,flowVersion:storyFlowVersion,draft:{...draft},language:language.value,structure:{...structure.value},part:0,draftKey,workId:existing.value?.id||recoveredWorkId,version:existing.value?.version??recoveredVersion,dirty:dirty.value,conflict:conflict.value}}
function remember(force=false) {
  clearTimeout(timer)
  if(!initialized.value||!force&&!localPending)return
  localPending=true
  if(!cacheKey){localSaved.value=false;return}
  const value=recoveryValue()
  localSaved.value=writeRecovery(cacheKey,value)
  localPending=!localSaved.value
  const owner=currentCreatorId(),source=associatedGuestCreatorId()
  const belongs=owner&&(cacheKey.startsWith(recoveryKey(owner,'story',''))||source&&cacheKey.startsWith(recoveryKey(source,'story','')))
  if(existing.value&&belongs&&cacheKey!==recoveryKey(owner,'story',existing.value.id))writeRecovery(recoveryKey(owner,'story',existing.value.id),value)
}
function scheduleLocalSave() {
  if(!initialized.value||resetting.value)return
  // The sync watcher only marks changes, so even immediate navigation can flush them.
  localPending=true
  clearTimeout(timer)
  timer=setTimeout(()=>remember(),500)
}
watch([draft,language,mode,preferences,structure],scheduleLocalSave,{deep:true,flush:'sync'})
watch(preferences,()=>{if(initialized.value&&mode.value==='custom')customTouched.value=true},{deep:true,flush:'sync'})
function restoreTemplate(saved?:LyricsStructure){
  legacyStructure=saved?normalizeLyricsStructure(saved):undefined
  structure.value=legacyStructure??{...defaultStructure}
}
async function initialize() {
  remember()
  const turn=++initSequence,query={...route.query}
  quickSession.reset()
  inspirationOpen.value=false
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
  resetting.value=true;ready.value=false;clearTimeout(timer)
  // 切换作品前先收尾正在保存的快照；旧响应不可把新建页面跳回旧作品。
  if(inflight)try{await inflight}catch{/* 旧作品的本机副本仍保留。 */}
  if(turn!==initSequence)return
  localPending=false;localSaved.value=true;initialized.value=false; error.value=''; notice.value=''; conflict.value=false; ready.value=false;saving.value=false
  mode.value=creationMode(query.mode,!!query.work);musicCatalog.value=undefined;preferences.value=undefined;musicBaseline.value='';musicPersisted.value=false;customTouched.value=false
  composing.value=false;photoWork.value=false;language.value=locale.value;restoreTemplate();validationError.value='';existing.value=undefined;recoveredVersion=undefined;recoveredWorkId=undefined;cacheKey='';draftKey=crypto.randomUUID()
  Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:agentBrief?.story||'',message:'',style:briefStyle,title:'',vocalGender:agentBrief?.params?.vocal??'AUTO'});baseline.value=JSON.stringify(snapshot())
  if(briefLanguage)language.value=briefLanguage
  if(briefStructure)structure.value=briefStructure
  omittedParams.value=briefComposition.dropped
  try {
    await ensureCreator()
    const system=await getSystemInfo();if(turn!==initSequence)return;ready.value=system.capabilities.workPersistence; aiReady.value=system.capabilities.lyricsGeneration;quickReady.value=system.capabilities.quickCreation===true
    if(!usesLocalPhotoCapability)photoReady.value=system.capabilities.photoLyrics===true
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
      mode.value=creationMode(query.mode||local.mode,!!id)
      language.value=local.language;restoreTemplate(local.structure);draftKey=local.draftKey
      if (local.dirty) {
        Object.assign(draft,asDraft(local.draft))
        conflict.value=!!local.conflict || (!!existing.value && local.version!==undefined && local.version!==existing.value.version)
        if (conflict.value) error.value='另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。'
      }
    }
    if(!id){const previousKey=cacheKey;cacheKey=recoveryKey(currentCreatorId(),'story',draftKey);const target=`draft:${draftKey}`;internalContext=target;internalRoute=true;try{await router.replace({path:'/create',query:{...query,draft:draftKey}})}finally{internalRoute=false;if(internalContext===target)internalContext=undefined};if(turn!==initSequence)return;if(previousKey!==cacheKey&&local)writeRecovery(cacheKey,local)}
    try{
      const [catalog,music]=await Promise.all([loadMusicCatalog(),existing.value?loadMusicSettings(existing.value.id):Promise.resolve(null)])
      if(turn!==initSequence)return
      musicCatalog.value=catalog
      photoWork.value=!!music?.photoInspiration
      if(!query.style&&!existing.value&&!local&&!agentBrief)draft.style='自动推荐'
      const initial=music?.direction?.preferences||{...legacyPreferences({...draft,id:'',version:0,createdAt:'',updatedAt:''}),...(agentBrief?{}:{freeText:''}),...(draft.style==='自动推荐'?{choices:{genre:[],instruments:[]}}:{})}
      musicPersisted.value=!!music?.direction;musicBaseline.value=JSON.stringify(initial);preferences.value=initial
      if(local?.preferences&&validMusicPreferences(local.preferences,catalog)&&local.version===existing.value?.version){preferences.value=local.preferences;customTouched.value=JSON.stringify(local.preferences)!==musicBaseline.value}
      musicIssue.value=''
    }catch{musicIssue.value='音乐选项暂时无法读取 请刷新后再使用自定义创作'}
    if(found&&mode.value==='quick')await quickSession.restore(found.id,found.title)
    if(turn!==initSequence)return
    void getUsage().then(v=>{if(turn===initSequence)allowance.value=v.lyrics}).catch(()=>{})
    // 积分余额与歌词价格都从后端读取；读不到时提示整块不显示，不猜测数字。
    void refreshCredits().catch(()=>{})
  } catch(e) {
    if(turn!==initSequence)return
    ready.value=false;error.value=e instanceof Error?e.message:'服务暂时不可用'
    if(e instanceof ApiError && (e.status===0||e.status===408||e.status>=500)){
      const owner=currentCreatorId()||lastKnownCreatorId()
      if(owner){cacheKey=recoveryKey(owner,'story',String(query.work||query.draft||draftKey));const local=readRecovery(cacheKey,validRecovery)
        if(local){Object.assign(draft,asDraft(local.draft));language.value=local.language;restoreTemplate(local.structure);draftKey=local.draftKey;recoveredVersion=local.version;recoveredWorkId=local.workId;baseline.value='';notice.value='服务暂时连接不上，已恢复本机内容；连接后可重试保存。'}
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
      initialized.value=true;resetting.value=false
      if(agentBriefApplied){remember(true);if(localSaved.value)clearAgentBrief()}
    }
  }
}
async function identityChanged() {
  if(!initialized.value)return
  try{await ensureCreator()}catch(e){ready.value=false;error.value=e instanceof Error?e.message:'身份核对失败，请保留当前编辑后重试。';return}
  const owner=currentCreatorId();if(!owner)return
  const next=recoveryKey(owner,'story',String(route.query.work||route.query.draft||draftKey))
  if(cacheKey===next)return
  remember()
  const source=associatedGuestCreatorId()
  if(source&&cacheKey.startsWith(recoveryKey(source,'story',''))) {
    if(!checkpointRecovery(next,recoveryValue())){ready.value=false;error.value='本机草稿备份失败，请保留当前编辑，恢复浏览器存储后重试。';return}
  }
  await initialize()
}
onMounted(()=>window.addEventListener('qingfeng:identity-changed',identityChanged))
onBeforeUnmount(()=>window.removeEventListener('qingfeng:identity-changed',identityChanged))
onMounted(()=>{
  if(!usesLocalPhotoCapability)return
  void getPhotoLyricsAvailable().then(available=>{if(alive)photoReady.value=available}).catch(()=>{})
})
onMounted(initialize)
watch([()=>contextOf(route.query),()=>route.query.mode],([context,routeMode],[previousContext])=>{
  if(context===internalContext)return
  if(context!==previousContext||routeMode==='custom'&&mode.value!=='custom')void initialize()
})
function persist():Promise<Work|undefined> {
  if(quickWorkId.value)return Promise.resolve(existing.value)
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
      remember(true)
      return saved
    } catch(e) {
      if(!alive||turn!==initSequence)throw e
      conflict.value=e instanceof ApiError && e.code==='WORK_VERSION_CONFLICT'
      error.value=conflict.value?'另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。':e instanceof Error?e.message:'保存失败，请重试'
      remember(true);throw e
    } finally {inflight=undefined;if(alive&&turn===initSequence)saving.value=false}
  })()
  return inflight
}
async function flush() {
  remember()
  const turn=initSequence
  const check=()=>{if(!alive||turn!==initSequence)throw new Error('页面已切换，已停止后续操作。')}
  if(!ready.value||!valid.value||conflict.value)throw new Error('请先解决保存问题，再继续创作。')
  if(inflight)await inflight
  check()
  while(!existing.value||dirty.value){const saved=await persist();check();if(!saved||conflict.value)throw new Error('请先解决保存问题，再继续创作。')}
  return existing.value
}
async function applyChosenMusic(work:Work,turn:number){
  const chosen=preferences.value
  if(!chosen||(!(mode.value==='custom'&&!musicPersisted.value)&&!musicChanged.value))return work
  const saved=await saveMusicSettings(work.id,work.version,chosen)
  if(!alive||turn!==initSequence)throw new Error('页面已切换，已停止后续操作。')
  existing.value=saved.work;baseline.value=JSON.stringify(asDraft(saved.work));Object.assign(draft,asDraft(saved.work))
  preferences.value=saved.direction?.preferences||chosen;musicBaseline.value=JSON.stringify(preferences.value);musicPersisted.value=true;remember(true)
  return saved.work
}
async function retry(){
  try {
    if(!ready.value){
      remember()
      if(!localPending)await initialize()
      return
    }
    await flush()
  }catch{/* 错误或旧页面的副本均保留。 */}
}
async function resolveConflict(useLocal:boolean){
  if(!existing.value)return
  const id=existing.value.id,turn=initSequence
  if(!window.confirm(t(useLocal?'将用本机内容更新服务器故事，确定继续吗？':'载入服务器版本将替换本机修改，确定继续吗？')))return
  try{const latest=await loadWork(id);if(!alive||turn!==initSequence)return;existing.value=latest;baseline.value=JSON.stringify(asDraft(latest));if(!useLocal)Object.assign(draft,asDraft(latest));conflict.value=false;error.value='';remember(true);if(useLocal)await flush()}
  catch(e){if(alive&&turn===initSequence)error.value=e instanceof Error?e.message:'保存失败，请重试'}
}
watch(()=>[draft.story,draft.message],()=>{validationError.value='';continuationFailed.value=false})
function rejectAnswer(message:string){validationError.value=message;inspirationInput.value?.focus({preventScroll:true})}
/**
 * 改写每次都会调用付费模型：每日免费额度内不扣积分，用完后每次扣一次积分。
 * 余额不足时先刷新一次再判断，避免用过期数字拦下刚充值完的用户；后端仍会再次校验。
 */
async function blockedByRewrite(){
  const gate=await guardCredits(()=>credits.value,refreshCredits,view=>view.rewriteFreeRemaining===0?view.rewriteCost:0)
  if(!gate.blocked)return false
  rewriteError.value=t('积分不足，当前余额 {balance} 积分，改写需要 {cost} 积分。',{balance:gate.balance as number,cost:gate.cost})
  return true
}
async function rewriteIdea() {
  if(rewriting.value||composing.value||quickLocked.value||!initialized.value||resetting.value||conflict.value)return
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
  if(!backup||rewriting.value||quickLocked.value||draft.story!==backup.result)return
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
  if(!photoWork.value&&!draft.story.trim()&&!draft.message.trim()){rejectAnswer('请先写下一点创作灵感。');return}
  await continueMaking()
}
async function continueMaking() {
  if(composing.value || rewriting.value || quickLocked.value || !ready.value)return
  if(!valid.value){rejectAnswer('请先写下一点创作灵感。');return}
  const chosenStructure={...structure.value}
  composing.value=true;error.value='';notice.value='';creditShort.value=false
  if(mode.value==='quick')quickSession.prepare()
  continuationFailed.value=false
  const turn=initSequence
  let writing=false
  try {
    const work=await flush()
    if(!work||!alive||turn!==initSequence)return
    const prepared=await applyChosenMusic(work,turn)
    if(!alive||turn!==initSequence)return
    if(mode.value==='quick'){
      if(!quickReady.value)throw new Error('一键创作暂不可用 可以切换自定义创作')
      // 先固定作品地址，刷新能查询原任务；本站任务受理后仍留在输入页等待。
      const target=`work:${prepared.id}`;internalContext=target;internalRoute=true
      try{
        await router.replace({
          path:'/create',
          query:{...route.query,work:prepared.id,draft:undefined,mode:'quick'},
          state:{studioDraftPromotion:{draft:String(route.query.draft||''),work:prepared.id}},
        })
      }finally{internalRoute=false;if(internalContext===target)internalContext=undefined}
      if(!alive||turn!==initSequence)return
      await quickSession.submit(prepared.id,prepared.version,language.value,prepared.title)
      return
    }
    writing=true
    // Recover an older request before starting a new lyric generation.
    const previousStructure=legacyStructure??normalizeLyricsStructure()
    const previousKey=lyricsStructureKey(previousStructure)
    const structureKey=lyricsStructureKey(chosenStructure)
    if(previousKey!==structureKey){
      const legacyKey=existingContinuationRequestKey(prepared.id,prepared.version,language.value,previousKey)
      if(legacyKey){
        const legacy=await getLyricsRequest(prepared.id,legacyKey)
        if(!alive||turn!==initSequence)return
        if(legacy.status==='SUCCEEDED'&&legacy.revisionId){
          await router.push({path:`/works/${work.id}`,query:{revision:legacy.revisionId,language:language.value,templateId:previousStructure.templateId,chorusFirst:String(previousStructure.chorusFirst)}})
          return
        }
        if(legacy.status==='PENDING'||legacy.status==='UNKNOWN')throw new Error('歌词仍在编写中，可以稍后刷新编写状态。')
      }
    }
    let key=continuationRequestKey(prepared.id,prepared.version,language.value,structureKey)
    const previous=await getLyricsRequest(prepared.id,key)
    if(previous.status==='SUCCEEDED'&&previous.revisionId){if(alive&&turn===initSequence)await router.push({path:`/works/${work.id}`,query:{revision:previous.revisionId,language:language.value,templateId:chosenStructure.templateId,chorusFirst:String(chosenStructure.chorusFirst)}});return}
    if(previous.status==='PENDING'||previous.status==='UNKNOWN')throw new Error('歌词仍在编写中，可以稍后刷新编写状态。')
    if(previous.status==='FAILED'){retireContinuationRequestKey(prepared.id,prepared.version,language.value,structureKey);key=continuationRequestKey(prepared.id,prepared.version,language.value,structureKey)}
    if(!alive||turn!==initSequence||await blockedByCredits()||!alive||turn!==initSequence)return
    const revision=await generateLyrics(prepared.id,prepared.version,key,language.value,'',undefined,chosenStructure)
    if(alive&&turn===initSequence)await router.push({path:`/works/${work.id}`,query:{revision:revision.id,language:language.value,templateId:chosenStructure.templateId,chorusFirst:String(chosenStructure.chorusFirst)}})
  } catch(e){if(alive&&turn===initSequence){quickSession.abortPreparation();continuationFailed.value=writing;error.value=e instanceof Error?e.message:'歌词生成失败，请稍后再试'}}
  finally{if(turn===initSequence){composing.value=false;void refreshCredits().catch(()=>{})}}
}
async function returnToLyrics(){
  if(composing.value||rewriting.value||quickLocked.value)return
  const turn=initSequence,revision=String(route.query.returnRevision||'')
  composing.value=true;error.value=''
  try{
    const work=await flush()
    if(!work||!alive||turn!==initSequence)return
    const prepared=await applyChosenMusic(work,turn)
    if(alive&&turn===initSequence)await router.push({path:`/works/${prepared.id}`,query:{revision}})
  }catch(e){if(alive&&turn===initSequence)error.value=e instanceof Error?e.message:'设置保存失败，请重试'}
  finally{if(turn===initSequence)composing.value=false}
}
const quickUnrecoverable=()=>!!quickSession.intent.value&&!quickFlow.value&&!quickSession.recoverable.value
function permitLeave(){
  remember()
  if(quickUnrecoverable())return window.confirm(t('浏览器无法保留本次请求标识 请先核对生成进度 确定离开吗'))
  return !localPending||window.confirm(t('当前内容尚未保存，确定离开吗？'))
}
function followAfterLeaving(){
  const flow=quickFlow.value, intent=quickSession.intent.value
  if(!quickWorkId.value||flow&&!quickInProgress(flow.status)||!flow&&!intent)return
  followQuickInBackground({owner:intent?.owner||currentCreatorId(),workId:quickWorkId.value,flowId:flow?.id||intent?.flowId,language:intent?.language||language.value,status:flow?.status})
}
onBeforeRouteLeave(to=>{
  if(!permitLeave())return false
  if(to.params.id!==quickWorkId.value)followAfterLeaving()
})
onBeforeRouteUpdate(to=>{
  if(internalRoute)return
  if(composing.value)return false
  if(contextOf(to.query)!==contextOf(route.query)&&!permitLeave())return false
  if(to.query.work!==quickWorkId.value)followAfterLeaving()
})
const unload=(event:BeforeUnloadEvent)=>{
  remember()
  if(localPending||quickUnrecoverable()){event.preventDefault();event.returnValue=''}
}
const saveOnPageHide=()=>remember()
const saveWhenHidden=()=>{if(document.visibilityState==='hidden')remember()}
onMounted(()=>{
  window.addEventListener('beforeunload',unload)
  window.addEventListener('pagehide',saveOnPageHide)
  document.addEventListener('visibilitychange',saveWhenHidden)
})
onBeforeUnmount(()=>{
  remember();alive=false;initSequence++
  window.removeEventListener('beforeunload',unload)
  window.removeEventListener('pagehide',saveOnPageHide)
  document.removeEventListener('visibilitychange',saveWhenHidden)
})
</script>
<template>
  <div class="story-studio inspiration-studio single-inspiration-studio dual-creation-studio"
    :class="{'custom-creation-mode':mode==='custom','studio-quick-locked':quickLocked,'studio-inspiration-open':inspirationOpen}">
    <StudioAtmosphere/>
    <div class="creation-mode-switch" :class="{'has-photo-mode':photoReady}" role="group" :aria-label="t('选择创作方式')">
      <button type="button" :aria-pressed="mode==='quick'" :disabled="composing||rewriting||quickLocked" @click="changeMode('quick')">{{ t('快速生成') }}</button>
      <button type="button" :aria-pressed="mode==='custom'" :disabled="composing||rewriting||quickLocked" @click="changeMode('custom')">{{ t('自定义创作') }}</button>
      <button v-if="photoReady" type="button" :disabled="composing||rewriting||quickLocked" @click="router.push('/create/photo')">{{ t('用照片写一首歌') }}</button>
    </div>
    <div class="story-introduction">
      <h1>{{ t('从这一刻起，你也是歌手。') }}</h1>
      <p class="studio-introduction-copy">{{ t(mode==='quick'?'用几句话表达你的灵感 AI 会为你写成歌曲':'先选音乐方向 再写出适合演唱的歌词') }}</p>
    </div>
    <div class="creation-sound-divider" aria-hidden="true"><span></span>ııııı<span></span></div>
    <div class="creation-page-layout"><section class="draft-form story-focus">
      <p v-if="notice" class="notice" role="status">{{ t(notice) }}</p>
      <div v-if="error" class="notice error" role="alert"><p>{{ t(error) }} <RouterLink v-if="creditShort" class="text-button" to="/recharge">{{ t('去充值') }}</RouterLink></p><div class="actions">
        <template v-if="conflict&&!quickLocked"><button class="button secondary" type="button" @click="resolveConflict(true)">{{ t('用本机内容继续') }}</button><button class="button secondary" type="button" @click="resolveConflict(false)">{{ t('载入服务器版本') }}</button></template>
        <button v-else-if="!quickLocked" class="button secondary" type="button" :disabled="saving||composing" @click="continuationFailed?continueMaking():retry()">{{ t(continuationFailed?'重新编写歌词':'重试保存') }}</button>
      </div></div>
      <p v-if="initialized&&!localSaved" class="save-info">{{ t('浏览器暂时无法保存草稿，请先复制你的创作描述再离开。') }}</p>
      <form @submit.prevent="next" :aria-label="t('写下创作灵感')">
        <fieldset class="draft-fields" :disabled="!initialized||composing||resetting||quickLocked">
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
              <strong v-if="mode==='custom'" class="creation-story-label">{{ t('写下你的故事') }}</strong>
            </div>
            <textarea id="inspiration-input" ref="inspirationInput" v-model="draft.story" :maxlength="inspirationLimit" :aria-label="t('写下创作灵感')" :placeholder="inspirationPlaceholder" :aria-invalid="!!validationError||undefined" :aria-describedby="validationError?'story-validation':undefined"></textarea>
            <p v-if="compiledParams" class="studio-agent-params">
              <span class="studio-agent-params-title">{{ t('已识别参数') }}</span>
              <span class="studio-agent-params-value">{{ compiledParams }}</span>
            </p>
            <p v-if="omittedParams.length" class="studio-agent-omitted">{{ t('已省略') }}：{{ omittedParams.join('、') }}<small>{{ t('作品描述最多 80 字，放不下的参数没有提交。') }}</small></p>
            <div class="inspiration-input-footer">
              <span class="inspiration-counter">{{ draft.story.length }} / {{ inspirationLimit }}</span>
              <label class="inspiration-language">
                <span class="inspiration-language-title"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a17 17 0 0 1 0 18 17 17 0 0 1 0-18Z"/></svg>{{ t('歌词语言') }}</span>
                <span class="inspiration-language-select"><select v-model="language" name="lyric-language" :aria-label="t('歌词语言')"><option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option><option value="mixed">{{ t('中英混合') }}</option></select><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4"/></svg></span>
              </label>
            </div>
            <p v-if="rewriteError" class="inspiration-rewrite-message error" role="alert">{{ t(rewriteError) }}</p>
            <p v-else-if="rewriteStatus" class="inspiration-rewrite-message" role="status">{{ t(rewriteStatus) }}</p>
          </div>
          <p v-if="validationError" id="story-validation" class="story-validation" role="alert">{{ t(validationError) }}</p>
          <QuickInspirationPanel v-if="mode==='quick'" v-model:expanded="inspirationOpen" :has-text="!!draft.story.trim()"
            :disabled="inspirationEditsDisabled" @choose="useInspiration"/>
          <div v-if="mode==='quick'" class="inspiration-examples" role="group" aria-labelledby="quick-style-label" aria-describedby="inspiration-guidance">
            <span id="quick-style-label">{{ t('选一种风格') }}</span>
            <button v-for="example in inspirationPresets" :key="example.label" type="button" :disabled="rewriting" :aria-pressed="draft.style===example.style" @click="chooseStyle(example)">{{ t(example.label) }}</button>
          </div>
          <p v-if="mode==='quick'" id="inspiration-guidance" class="save-info">{{ t('点击曲风标签添加可编辑的风格描述。') }}</p>
          <CreationMusicChoices v-if="mode==='custom'&&musicCatalog&&preferences" v-model="preferences" :catalog="musicCatalog" :disabled="composing||rewriting||resetting"><template #structure><LyricsTemplatePicker v-model="structure" :disabled="composing||rewriting||resetting||!initialized||quickLocked"/></template></CreationMusicChoices>
          <p v-if="mode==='custom'&&musicIssue" class="notice error" role="alert">{{ t(musicIssue) }}</p>
          <details v-if="draft.recipient||draft.occasion||draft.message" class="legacy-inspiration-context"><summary>{{ t('已保留的旧版作品信息') }}</summary><p v-if="draft.recipient">{{ t('收件人：') }}{{ draft.recipient }}</p><p v-if="draft.occasion">{{ draft.occasion }}</p><p v-if="draft.message" class="legacy-message">{{ draft.message }}</p></details>
          <div class="creation-action-bar story-action-bar"><button class="button creation-primary" type="submit" :disabled="!initialized||composing||rewriting||resetting||quickLocked||!ready||!aiReady||conflict||(mode==='quick'?!quickReady:!musicCatalog)">{{ composing?t(mode==='quick'?'正在开始创作…':'正在编写歌词…'):t(mode==='quick'?'一键生成音乐':'生成歌词') }}<span v-if="!composing" aria-hidden="true">→</span></button><CreditCostHint v-if="credits&&credits.enabled&&credits.lyricsCost>0" :cost="credits.lyricsCost" :balance="credits.balance" :busy="composing"/></div>
          <p class="inspiration-next-note">{{ t(mode==='quick'?'AI 自动完成歌词与谱曲 生成后即可试听':'下一步可编辑歌词 确认后再生成音乐') }}</p>
          <p v-if="mode==='quick'&&!quickReady&&initialized" class="save-info">{{ t('一键创作暂不可用 可以切换自定义创作') }}</p>
          <div v-if="route.query.returnRevision" class="story-secondary-actions"><button type="button" class="text-button return-to-lyrics" @click="returnToLyrics">{{ t('返回歌词编辑') }}</button></div>
        </fieldset>
      </form>
      <section v-if="quickLocked" class="notice quick-input-lock" role="status"><p>{{ t(quickFlow?.status==='SUCCEEDED'?'本次创作已完成 可试听歌曲或继续创作新歌':quickFlow&&['FAILED','CANCELLED'].includes(quickFlow.status)?'本次创作已结束 可继续原任务或切换自定义创作':'本次创作要求已固定 制作期间不能修改或重复提交') }}</p><button v-if="!quickFlow" class="button secondary" type="button" @click="quickSession.show">{{ t('查看生成进度') }}</button><RouterLink v-if="quickFlow&&['SUCCEEDED','FAILED','CANCELLED'].includes(quickFlow.status)" class="button secondary" to="/create?mode=quick">{{ t('继续创作一首歌') }}</RouterLink></section>
      <section v-if="quickUnknown" class="notice" role="alert"><p>{{ t(quickIssue) }}</p><button v-if="quickCanResume" class="button secondary" type="button" @click="quickSession.resume">{{ t('核对并恢复原请求') }}</button></section>
      <p v-else-if="quickIssue&&!quickLocked" class="notice error" role="alert">{{ t(quickIssue) }}</p>
      <QuickCreationProgress :work-id="quickWorkId" :title="quickTitle||draft.title"
        :initial-flow="quickFlow" :dialog-open="quickDialogOpen" :submission-status="quickInitialStatus"
        :submission-pending="quickSubmissionPending" :submission-issue="quickIssue"
        :result-ready="quickFlow?.status==='SUCCEEDED'" :awaiting-submission="quickUnknown" completion-mode="navigate"
        @resolved="quickSession.resolve" @visibility="setQuickDialogOpen" @view="viewQuickResult"/>
      <p v-if="initialized&&!aiReady" class="notice">{{ t('歌词生成暂不可用，请稍后再试或手动填写') }} <RouterLink v-if="existing" :to="`/works/${existing.id}`">{{ t('手动填写歌词') }}</RouterLink></p>
    </section><aside v-if="mode==='custom'" class="creation-inspiration-aside" aria-label="Cheerful Music"><div class="creation-record"><span>♫</span></div><h2>{{ t('让灵感 变成音乐') }}</h2><p>{{ t('无论是情感 故事 还是一个灵感 都能为你谱写动人的旋律') }}</p><div class="creation-waveform" aria-hidden="true">ııııııııııııııııııı</div><small>{{ t('可以在生成音乐前继续微调') }}</small></aside></div>
    <p class="studio-privacy-note">{{ t('灵感和草稿仅自己可见，分享由你主动创建。') }}</p>
  </div>
</template>

<style scoped>
/* Inserting status cards must not make the browser re-anchor the locked editor. */
:global(html:has(.studio-quick-locked)) { overflow-anchor: none; }
</style>
