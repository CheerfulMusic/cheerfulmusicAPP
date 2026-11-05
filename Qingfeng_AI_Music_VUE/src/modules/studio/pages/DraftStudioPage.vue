<script setup lang="ts">
import '../styles/inspiration-studio.css'
import StudioAtmosphere from '../components/StudioAtmosphere.vue'
import { t, locale } from '../../../shared/i18n'
import { onMounted, onBeforeUnmount, reactive, ref, computed, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { ApiError, ensureCreator, currentCreatorId, lastKnownCreatorId } from '../../../shared/http/api-client'
import { readRecovery, writeRecovery, recoveryKey,checkpointRecovery } from '../../../shared/drafts/recovery'
import CreationSteps from '../../../shared/ui/CreationSteps.vue'
import RecoveryCopies from '../../../shared/drafts/RecoveryCopies.vue'
import { loadWork, saveWork, rewriteInspiration, type Work, type DraftInput } from '../api/works'
import { normalizeVocalGender } from '../model/vocals'
import { styleFromInspiration } from '../model/suggestions'
import { continuationRequestKey, retireContinuationRequestKey } from '../model/lyrics-continuation'
import { generateLyrics, getLyricsRequest, getUsage, type Allowance } from '../../works/api/detail'

const storyFlowVersion = 3
interface Recovery { flowVersion?:number; draft:DraftInput; language:string; part:number; draftKey:string; workId?:string; version?:number; dirty:boolean; conflict?:boolean }
function validRecovery(value:unknown):value is Recovery {
  const v = value as Recovery
  return !!v && !!v.draft && ['recipient','occasion','story','message','style','title'].every(k => typeof v.draft[k as keyof DraftInput] === 'string') && ['zh-CN','en','mixed'].includes(v.language) && /^[0-9a-f-]{36}$/i.test(v.draftKey)
}
const route=useRoute(), router=useRouter(), initialized=ref(false), ready=ref(false), aiReady=ref(false), existing=ref<Work>(), baseline=ref(''), saving=ref(false), composing=ref(false), error=ref(''), notice=ref(''), conflict=ref(false), localSaved=ref(false), allowance=ref<Allowance>()
const language=ref<string>(locale.value)
const validationError=ref(''), selectedHint=ref(-1)
const rewriting=ref(false), rewriteError=ref(''), rewriteStatus=ref('')
const rewriteBackup=ref<{source:string;result:string}>()
const inspirationExamples=[
  {label:'感人的故事',hint:'一段让你记住的经历，当时发生了什么，你有什么感受？'},
  {label:'旅行与自由',hint:'一场旅行、沿途的风景，或一次想要出发的心情。'},
  {label:'雨夜心绪',hint:'某个安静的夜晚，心里浮现的画面和情绪。'},
  {label:'治愈与成长',hint:'一次小小的改变、重新出发的勇气，或想对自己说的话。'},
  {label:'自己的主题',hint:'写下你的故事、情绪、场景或主题……'},
]
const inspirationPlaceholder=computed(()=>t(inspirationExamples[selectedHint.value]?.hint||'写下你的故事、情绪、场景或主题……'))
const continuationFailed=ref(false)
const resetting=ref(false)
const draft=reactive<DraftInput>({recipient:'',occasion:String(route.query.occasion||''),story:'',message:'',style:styleFromInspiration(route.query.style),title:'',vocalGender:'AUTO'})
const snapshot=():DraftInput=>({...draft,recipient:draft.recipient.trim()})
const dirty=computed(()=>initialized.value && JSON.stringify(snapshot())!==baseline.value)
const valid=computed(()=>!!(draft.story.trim()||draft.message.trim()) && !!draft.style.trim())
let draftKey:string=crypto.randomUUID(), cacheKey='', alive=true, timer:ReturnType<typeof setTimeout>|undefined, inflight:Promise<Work|undefined>|undefined, internalRoute=false
let recoveredVersion:number|undefined,recoveredWorkId:string|undefined
let initSequence=0,internalContext:string|undefined
const contextOf=(query:Record<string,unknown>)=>query.work?`work:${String(query.work)}`:query.draft?`draft:${String(query.draft)}`:'new'
const asDraft=(work:DraftInput):DraftInput=>({recipient:work.recipient,occasion:work.occasion,story:work.story,message:work.message,style:work.style,title:work.title,vocalGender:normalizeVocalGender(work.vocalGender)})
function recoveryValue():Recovery{return {flowVersion:storyFlowVersion,draft:{...draft},language:language.value,part:0,draftKey,workId:existing.value?.id||recoveredWorkId,version:existing.value?.version??recoveredVersion,dirty:dirty.value,conflict:conflict.value}}
function remember() {
  if(!cacheKey||!initialized.value)return
  const value=recoveryValue();localSaved.value=writeRecovery(cacheKey,value)
  if(existing.value&&cacheKey!==recoveryKey(currentCreatorId(),'story',existing.value.id))writeRecovery(recoveryKey(currentCreatorId(),'story',existing.value.id),value)
}
function restoreCopy(value:Recovery){
 if(!initialized.value||resetting.value||saving.value||composing.value)return
 if(!checkpointRecovery(cacheKey,recoveryValue())){error.value='无法保留当前编辑，已停止恢复。请先复制内容，或清理浏览器存储后重试。';return}
 clearTimeout(timer)
 Object.assign(draft,asDraft(value.draft));language.value=value.language
 conflict.value=!!existing.value&&value.version!==undefined&&value.version!==existing.value.version
 error.value=conflict.value?'另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。':''
 notice.value='已恢复本机副本，替换前的编辑也已另存。';remember();if(!conflict.value)schedule()
}
function schedule() {
  remember(); clearTimeout(timer)
  if (dirty.value && valid.value && ready.value && !conflict.value && !composing.value) timer=setTimeout(()=>{void persist().catch(()=>{})},1200)
}
watch([draft,language],schedule,{deep:true,flush:'sync'})
async function initialize() {
  const turn=++initSequence,query={...route.query}
  rewriting.value=false;rewriteError.value='';rewriteStatus.value='';rewriteBackup.value=undefined
  resetting.value=true;ready.value=false;clearTimeout(timer)
  // 切换作品前先收尾正在保存的快照；旧响应不可把新建页面跳回旧作品。
  if(inflight)try{await inflight}catch{/* 旧作品的本机副本仍保留。 */}
  if(turn!==initSequence)return
  clearTimeout(timer); initialized.value=false; error.value=''; notice.value=''; conflict.value=false; ready.value=false;saving.value=false
  composing.value=false;language.value=locale.value;selectedHint.value=-1;validationError.value='';existing.value=undefined;recoveredVersion=undefined;recoveredWorkId=undefined;cacheKey='';draftKey=crypto.randomUUID()
  Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:'',message:'',style:styleFromInspiration(query.style),title:'',vocalGender:'AUTO'});baseline.value=JSON.stringify(snapshot())
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
    else {Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:'',message:'',style:styleFromInspiration(query.style),title:'',vocalGender:'AUTO'});baseline.value=JSON.stringify(snapshot());draftKey=crypto.randomUUID()}
    if (local) {
      language.value=local.language;draftKey=local.draftKey
      if (local.dirty) {
        Object.assign(draft,asDraft(local.draft));notice.value='已恢复本机尚未保存的内容。'
        conflict.value=!!local.conflict || (!!existing.value && local.version!==undefined && local.version!==existing.value.version)
        if (conflict.value) error.value='另一页面已更新故事。本机内容已保留，请先核对再选择保存或载入。'
      }
    }
    if(!id){const previousKey=cacheKey;cacheKey=recoveryKey(currentCreatorId(),'story',draftKey);const target=`draft:${draftKey}`;internalContext=target;internalRoute=true;try{await router.replace({path:'/create',query:{...query,draft:draftKey}})}finally{internalRoute=false;if(internalContext===target)internalContext=undefined};if(turn!==initSequence)return;if(previousKey!==cacheKey&&local)writeRecovery(cacheKey,local)}
    void getUsage().then(v=>{if(turn===initSequence)allowance.value=v.lyrics}).catch(()=>{})
  } catch(e) {
    if(turn!==initSequence)return
    ready.value=false;error.value=e instanceof Error?e.message:'服务暂时不可用'
    if(e instanceof ApiError && (e.status===0||e.status===408||e.status>=500)){
      const owner=currentCreatorId()||lastKnownCreatorId()
      if(owner){cacheKey=recoveryKey(owner,'story',String(query.work||query.draft||draftKey));const local=readRecovery(cacheKey,validRecovery)
        if(local){Object.assign(draft,asDraft(local.draft));language.value=local.language;draftKey=local.draftKey;recoveredVersion=local.version;recoveredWorkId=local.workId;baseline.value='';notice.value='服务暂时连接不上，已恢复本机内容；连接后可重试保存。'}
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
      initialized.value=true;resetting.value=false;remember();if(!conflict.value)schedule()
    }
  }
}
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
async function rewriteIdea() {
  if(rewriting.value||composing.value||!initialized.value||resetting.value||conflict.value)return
  if(!draft.story.trim()){rejectAnswer('请先写下一点创作灵感，再使用AI改写。');return}
  const source=draft.story, requestedLanguage=language.value, turn=initSequence, owner=currentCreatorId()
  rewriting.value=true;rewriteError.value='';rewriteStatus.value=''
  try {
    const result=await rewriteInspiration(source,requestedLanguage)
    if(!alive||turn!==initSequence||owner!==currentCreatorId())return
    if(draft.story!==source||language.value!==requestedLanguage||conflict.value){rewriteStatus.value='内容已修改，本次改写未覆盖当前输入，请重新改写。';return}
    if(!checkpointRecovery(cacheKey,recoveryValue())){rewriteError.value='无法备份原文，本次改写未覆盖内容。';return}
    rewriteBackup.value={source,result:result.text};draft.story=result.text
    rewriteStatus.value='已优化创作描述，你可以继续编辑或恢复原文。'
  } catch(e) {
    if(alive&&turn===initSequence&&owner===currentCreatorId())rewriteError.value=e instanceof Error?e.message:'AI改写失败，请稍后重试；原文仍会保留。'
  } finally {if(alive&&turn===initSequence)rewriting.value=false}
}
function restoreRewrite() {
  const backup=rewriteBackup.value
  if(!backup||rewriting.value||draft.story!==backup.result)return
  if(!checkpointRecovery(cacheKey,recoveryValue())){rewriteError.value='无法备份原文，本次改写未覆盖内容。';return}
  draft.story=backup.source;rewriteBackup.value=undefined;rewriteError.value='';rewriteStatus.value='已恢复改写前的原文。'
}
async function next() {
  if(rewriting.value)return
  if(!draft.story.trim()&&!draft.message.trim()){rejectAnswer('请先写下一点创作灵感。');return}
  await continueMaking()
}
async function continueMaking() {
  if(composing.value || rewriting.value || !ready.value)return
  if(!valid.value){rejectAnswer('请先写下一点创作灵感。');return}
  composing.value=true;error.value='';notice.value=''
  continuationFailed.value=false
  const turn=initSequence
  let writing=false
  try {
    const work=await flush()
    if(!work||!alive||turn!==initSequence)return
    writing=true
    let key=continuationRequestKey(work.id,work.version,language.value)
    const previous=await getLyricsRequest(work.id,key)
    if(previous.status==='SUCCEEDED'&&previous.revisionId){if(alive&&turn===initSequence)await router.push({path:`/works/${work.id}`,query:{revision:previous.revisionId,language:language.value}});return}
    if(previous.status==='PENDING'||previous.status==='UNKNOWN')throw new Error('歌词仍在编写中，可以稍后刷新编写状态。')
    if(previous.status==='FAILED'){retireContinuationRequestKey(work.id,work.version,language.value);key=continuationRequestKey(work.id,work.version,language.value)}
    const revision=await generateLyrics(work.id,work.version,key,language.value)
    if(alive&&turn===initSequence)await router.push({path:`/works/${work.id}`,query:{revision:revision.id,language:language.value}})
  } catch(e){if(alive&&turn===initSequence){continuationFailed.value=writing;error.value=e instanceof Error?e.message:'歌词生成失败，请稍后再试'}}
  finally{if(turn===initSequence)composing.value=false}
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
      <div v-if="error" class="notice error" role="alert"><p>{{ t(error) }}</p><div class="actions">
        <template v-if="conflict"><button class="button secondary" type="button" @click="resolveConflict(true)">{{ t('用本机内容继续') }}</button><button class="button secondary" type="button" @click="resolveConflict(false)">{{ t('载入服务器版本') }}</button></template>
        <button v-else class="button secondary" type="button" :disabled="saving||composing" @click="continuationFailed?continueMaking():retry()">{{ t(continuationFailed?'重新编写歌词':'重试保存') }}</button>
      </div></div>
      <p v-if="initialized&&!localSaved" class="save-info">{{ t('浏览器无法保存本机副本，请确认显示已保存后再离开。') }}</p>
      <form @submit.prevent="next" :aria-label="t('写下创作灵感')">
        <fieldset class="draft-fields" :disabled="!initialized||composing||resetting">
          <div class="inspiration-input-box" :class="{'is-invalid':validationError}">
            <div class="inspiration-rewrite-tools">
              <button type="button" class="inspiration-label inspiration-rewrite" :disabled="rewriting||!ready||!aiReady||conflict" :aria-busy="rewriting" @click="rewriteIdea"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3ZM21 2v5m-2.5-2.5h5"/></svg>{{ t(rewriting?'AI改写中…':'AI改写') }}</button>
              <button v-if="rewriteBackup&&draft.story===rewriteBackup.result" class="inspiration-restore" type="button" :disabled="rewriting" @click="restoreRewrite">{{ t('恢复原文') }}</button>
            </div>
            <textarea id="inspiration-input" v-model="draft.story" maxlength="4000" :aria-label="t('写下创作灵感')" :placeholder="inspirationPlaceholder" :aria-invalid="!!validationError||undefined" :aria-describedby="validationError?'inspiration-guidance story-validation':'inspiration-guidance'"></textarea>
            <div class="inspiration-input-footer">
              <span class="inspiration-counter">{{ draft.story.length }} / 4000</span>
              <label class="inspiration-language">{{ t('歌词语言') }}<select v-model="language" name="lyric-language"><option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option><option value="mixed">{{ t('中英混合') }}</option></select></label>
            </div>
            <p v-if="rewriteError" class="inspiration-rewrite-message error" role="alert">{{ t(rewriteError) }}</p>
            <p v-else-if="rewriteStatus" class="inspiration-rewrite-message" role="status">{{ t(rewriteStatus) }}</p>
          </div>
          <p v-if="validationError" id="story-validation" class="story-validation" role="alert">{{ t(validationError) }}</p>
          <div class="inspiration-examples" role="group" :aria-label="t('灵感方向')"><span>{{ t('还没灵感？') }}</span><button v-for="(example,index) in inspirationExamples" :key="example.label" type="button" :aria-pressed="selectedHint===index" @click="selectedHint=selectedHint===index?-1:index">{{ t(example.label) }}</button></div>
          <p id="inspiration-guidance" class="save-info">{{ t('示例标签只切换提示，不会替换你已经写下的内容。') }}</p>
          <details v-if="draft.recipient||draft.occasion||draft.message" class="legacy-inspiration-context"><summary>{{ t('已保留的旧版作品信息') }}</summary><p v-if="draft.recipient">{{ t('收件人：') }}{{ draft.recipient }}</p><p v-if="draft.occasion">{{ draft.occasion }}</p><p v-if="draft.message" class="legacy-message">{{ draft.message }}</p></details>
          <div class="creation-action-bar story-action-bar"><button class="button creation-primary" type="submit" :disabled="!initialized||composing||rewriting||resetting||!ready||!aiReady||conflict">{{ composing?t('正在编写歌词…'):t('生成歌词') }}<span v-if="!composing" aria-hidden="true">→</span></button></div>
          <p class="inspiration-next-note">{{ t('先生成歌词，修改满意后再生成音乐。') }}</p>
          <div v-if="route.query.returnRevision" class="story-secondary-actions"><button type="button" class="text-button return-to-lyrics" @click="returnToLyrics">{{ t('返回歌词编辑') }}</button></div>
          <p v-if="allowance" class="save-info inspiration-allowance">{{ allowance.dailyUnlimited?t('今日歌词编写不限次数'):t('今日还可编写 {remaining} 次歌词',{remaining:allowance.dailyRemaining}) }}</p>
        </fieldset>
      </form>
      <p v-if="initialized&&!aiReady" class="notice">{{ t('歌词生成暂不可用，请稍后再试或手动填写') }} <RouterLink v-if="existing" :to="`/works/${existing.id}`">{{ t('手动填写歌词') }}</RouterLink></p>
    </section>
    <p class="studio-privacy-note">{{ t('灵感和草稿仅自己可见，分享由你主动创建。') }}</p>
  </div>
</template>
