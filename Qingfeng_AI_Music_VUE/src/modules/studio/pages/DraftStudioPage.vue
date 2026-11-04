<script setup lang="ts">
import { t, locale } from '../../../shared/i18n'
import { onMounted, onBeforeUnmount, reactive, ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { ApiError, ensureCreator, currentCreatorId, lastKnownCreatorId } from '../../../shared/http/api-client'
import { readRecovery, writeRecovery, recoveryKey,checkpointRecovery } from '../../../shared/drafts/recovery'
import CreationSteps from '../../../shared/ui/CreationSteps.vue'
import RecoveryCopies from '../../../shared/drafts/RecoveryCopies.vue'
import { loadWork, saveWork, type Work, type DraftInput } from '../api/works'
import SuggestionField from '../components/SuggestionField.vue'
import { normalizeVocalGender } from '../model/vocals'
import { recipientSuggestions, occasionSuggestions } from '../model/suggestions'
import {storyQuestions,storyFlowVersion,questionPart,restoredStoryPart,reachableStoryPart} from '../model/story-flow'
import { continuationRequestKey, retireContinuationRequestKey } from '../model/lyrics-continuation'
import { generateLyrics, getLyricsRequest, getUsage, type Allowance } from '../../works/api/detail'

interface Recovery { flowVersion?:number; draft:DraftInput; language:string; part:number; draftKey:string; workId?:string; version?:number; dirty:boolean; conflict?:boolean }
function validRecovery(value:unknown):value is Recovery {
  const v = value as Recovery
  return !!v && !!v.draft && ['recipient','occasion','story','message','style','title'].every(k => typeof v.draft[k as keyof DraftInput] === 'string') && ['zh-CN','en','mixed'].includes(v.language) && /^[0-9a-f-]{36}$/i.test(v.draftKey)
}
const route=useRoute(), router=useRouter(), part=ref(0), initialized=ref(false), ready=ref(false), aiReady=ref(false), existing=ref<Work>(), baseline=ref(''), saving=ref(false), composing=ref(false), error=ref(''), notice=ref(''), conflict=ref(false), localSaved=ref(false), allowance=ref<Allowance>()
const language=ref<string>(locale.value)
const questionHeading=ref<HTMLElement>(),memoryDirection=ref(-1),validationError=ref('')
const memoryHints=['可以写一个真实的小细节：TA常说的话、习惯的动作，或某次让你记住的陪伴。','TA有没有一个你熟悉的动作或习惯？你为什么记得它？','TA常说的哪句话，让你到现在还记得？','回想一次陪伴：当时在哪里，TA做了什么，让你有什么感受？']
const questionTitles=['这首歌，你想送给谁？','想在什么时刻，把这首歌送给 TA？','有什么小事，你一直记得？','如果只能说一句话，你想对 TA 说什么？','想用什么语言，唱这份心意？']
const questionLabels=['送给谁','送歌时刻','一段回忆','一句心意']
const languages=[{value:'zh-CN',label:'中文',sample:'用熟悉的语言，唱出心里的话。'},{value:'en',label:'English',sample:'让这份心意，用英文表达。'},{value:'mixed',label:'中英混合',sample:'中文和英文，都可以写进同一首歌。'}]
const continuationFailed=ref(false)
const resetting=ref(false)
const draft=reactive<DraftInput>({recipient:'',occasion:String(route.query.occasion||''),story:'',message:'',style:'温暖民谣',title:'',vocalGender:'AUTO'})
const snapshot=():DraftInput=>({...draft,recipient:draft.recipient.trim()})
const dirty=computed(()=>initialized.value && JSON.stringify(snapshot())!==baseline.value)
const valid=computed(()=>!!draft.recipient.trim() && !!draft.occasion.trim() && !!draft.style.trim())
let draftKey:string=crypto.randomUUID(), cacheKey='', alive=true, timer:ReturnType<typeof setTimeout>|undefined, inflight:Promise<Work|undefined>|undefined, internalRoute=false
let recoveredVersion:number|undefined,recoveredWorkId:string|undefined
let initSequence=0,internalContext:string|undefined
const contextOf=(query:Record<string,unknown>)=>query.work?`work:${String(query.work)}`:query.draft?`draft:${String(query.draft)}`:'new'
const asDraft=(work:DraftInput):DraftInput=>({recipient:work.recipient,occasion:work.occasion,story:work.story,message:work.message,style:work.style,title:work.title,vocalGender:normalizeVocalGender(work.vocalGender)})
function recoveryValue():Recovery{return {flowVersion:storyFlowVersion,draft:{...draft},language:language.value,part:part.value,draftKey,workId:existing.value?.id||recoveredWorkId,version:existing.value?.version??recoveredVersion,dirty:dirty.value,conflict:conflict.value}}
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
watch([draft,language,part],schedule,{deep:true,flush:'sync'})
async function initialize() {
  const turn=++initSequence,query={...route.query}
  resetting.value=true;ready.value=false;clearTimeout(timer)
  // 切换作品前先收尾正在保存的快照；旧响应不可把新建页面跳回旧作品。
  if(inflight)try{await inflight}catch{/* 旧作品的本机副本仍保留。 */}
  if(turn!==initSequence)return
  clearTimeout(timer); initialized.value=false; error.value=''; notice.value=''; conflict.value=false; ready.value=false;saving.value=false
  composing.value=false;part.value=0;language.value=locale.value;memoryDirection.value=-1;validationError.value='';existing.value=undefined;recoveredVersion=undefined;recoveredWorkId=undefined;cacheKey='';draftKey=crypto.randomUUID()
  Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:'',message:'',style:'温暖民谣',title:'',vocalGender:'AUTO'});baseline.value=JSON.stringify(snapshot())
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
    else {Object.assign(draft,{recipient:'',occasion:String(query.occasion||''),story:'',message:'',style:'温暖民谣',title:'',vocalGender:'AUTO'});baseline.value=JSON.stringify(snapshot());draftKey=crypto.randomUUID()}
    if (local) {
      language.value=local.language;part.value=restoredStoryPart(local.part,local.flowVersion);draftKey=local.draftKey
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
        if(local){Object.assign(draft,asDraft(local.draft));language.value=local.language;part.value=restoredStoryPart(local.part,local.flowVersion);draftKey=local.draftKey;recoveredVersion=local.version;recoveredWorkId=local.workId;baseline.value='';notice.value='服务暂时连接不上，已恢复本机内容；连接后可重试保存。'}
      }
      ready.value=false
    }
  }
  finally {
    if(turn===initSequence){
      part.value=reachableStoryPart(questionPart(route.query.question)??(route.query.part==='1'?2:part.value),draft)
      // 将恢复出来的问题写入地址，浏览器后退才能准确返回旧草稿的起始问题。
      if(route.query.question!==storyQuestions[part.value]||route.query.part!==undefined){
        internalRoute=true
        try{await router.replace({path:'/create',query:{...route.query,part:undefined,question:storyQuestions[part.value]}})}finally{internalRoute=false}
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
async function focusQuestion(){await nextTick();questionHeading.value?.focus({preventScroll:true});document.querySelector('.story-focus')?.scrollIntoView({block:'start'})}
async function goToPart(target:number){
  if(composing.value||resetting.value)return
  const chosen=reachableStoryPart(target,draft),turn=initSequence
  error.value='';validationError.value='';continuationFailed.value=false
  await router.push({path:'/create',query:{...route.query,part:undefined,question:storyQuestions[chosen]}})
  if(!alive||turn!==initSequence)return
  part.value=chosen;remember();await focusQuestion()
}
watch(()=>route.query.question,value=>{if(initialized.value){part.value=reachableStoryPart(questionPart(value)??0,draft);validationError.value='';remember();void focusQuestion()}})
watch(()=>[draft.recipient,draft.occasion,draft.story,draft.message],()=>{validationError.value='';continuationFailed.value=false})
function rejectAnswer(message:string){validationError.value=message;document.querySelector<HTMLInputElement|HTMLTextAreaElement>('.story-question input,.story-question textarea')?.focus()}
async function next() {
  if(part.value===0&&!draft.recipient.trim()){rejectAnswer('请先填写收件人的称呼。');return}
  if(part.value===1&&!draft.occasion.trim()){rejectAnswer('请选择或填写送歌时刻，也可以选择“不为特别的日子”。');return}
  if(part.value===3&&!draft.story.trim()&&!draft.message.trim()){rejectAnswer('回忆或想说的话，至少写一点，才能写出属于你的歌词。');return}
  if(part.value<4){await goToPart(part.value+1);return}
  await continueMaking()
}
async function continueMaking() {
  if(composing.value || !ready.value)return
  if(!valid.value){await goToPart(!draft.recipient.trim()?0:1);rejectAnswer('请填写称呼和送歌时刻。');return}
  if(!draft.story.trim()&&!draft.message.trim()){await goToPart(2);rejectAnswer('回忆或想说的话，至少写一点，才能写出属于你的歌词。');return}
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
onBeforeRouteUpdate((to,from)=>internalRoute||(composing.value&&to.query.question!==from.query.question?false:permitLeave()))
const unload=(e:BeforeUnloadEvent)=>{remember();if(dirty.value&&!localSaved.value){e.preventDefault();e.returnValue=''}}
const online=()=>{if(ready.value&&!conflict.value)void retry()}
onMounted(()=>{window.addEventListener('beforeunload',unload);window.addEventListener('online',online)})
onBeforeUnmount(()=>{remember();alive=false;initSequence++;clearTimeout(timer);window.removeEventListener('beforeunload',unload);window.removeEventListener('online',online)})
</script>
<template>
  <div class="story-studio">
    <CreationSteps :current="0"/>
    <div class="story-introduction"><h1>{{ t('从一个名字，和一件小事开始。') }}</h1><span class="autosave-state" role="status">{{ saving?t('正在保存…'):dirty?localSaved?t('已保留在本机'):t('有尚未保存的修改'):existing?t('已保存'):t('填写后自动保存') }}</span></div>
    <RecoveryCopies v-if="initialized&&cacheKey" :storage-key="cacheKey" :validate="validRecovery" @restore="restoreCopy"/>
    <div class="draft-layout story-layout"><section class="draft-form story-focus">
      <div class="story-progress"><span>{{ part<4?t('故事填写 · {step}/4',{step:part+1}):t('故事已备好，选择歌词语言') }}</span><span>{{ part<4?t(questionLabels[part]!):t('生成前最后一步') }}</span><div role="progressbar" :aria-label="t('故事填写步骤')" aria-valuemin="0" aria-valuemax="4" :aria-valuenow="Math.min(part+1,4)"><span :style="{width:`${Math.min(part+1,4)*25}%`}"></span></div></div>
      <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
      <div v-if="error" class="notice error" role="alert"><p>{{ t(error) }}</p><div v-if="conflict||part===4||!ready" class="actions"><template v-if="conflict"><button class="button secondary" type="button" @click="resolveConflict(true)">{{ t('用本机内容继续') }}</button><button class="button secondary" type="button" @click="resolveConflict(false)">{{ t('载入服务器版本') }}</button></template><button v-else-if="part===4||!ready" class="button secondary" type="button" :disabled="saving||composing" @click="continuationFailed?continueMaking():retry()">{{ t(continuationFailed?'重新编写歌词':'重试保存') }}</button></div></div>
      <p v-if="initialized&&!localSaved" class="save-info">{{ t('浏览器无法保存本机副本，请确认显示已保存后再离开。') }}</p>
      <form @submit.prevent="next" aria-labelledby="story-question"><fieldset class="draft-fields" :disabled="!initialized||composing||resetting">
        <div :key="part" class="story-question"><h2 id="story-question" ref="questionHeading" tabindex="-1">{{ t(questionTitles[part]!) }}</h2>
          <template v-if="part===0"><p class="story-question-hint">{{ t('一个名字、一个昵称，或者平时最熟悉的称呼。') }}</p><SuggestionField v-model="draft.recipient" :label="t('TA 的称呼')" :placeholder="t('例如：妈妈、小雨、老朋友')" :suggestions="recipientSuggestions" :initial-count="6" :max-length="80" :invalid="!!validationError" :error-id="validationError?'story-validation':undefined" required/></template>
          <template v-else-if="part===1"><p class="story-question-hint">{{ t('纪念一个日子，或只是想把心意告诉 TA。') }}</p><SuggestionField v-model="draft.occasion" :label="t('送歌时刻')" :placeholder="t('选择一个时刻，或写下自己的理由')" :suggestions="occasionSuggestions" :initial-count="6" :max-length="32" :invalid="!!validationError" :error-id="validationError?'story-validation':undefined" translate-value required/></template>
          <template v-else-if="part===2"><p class="story-question-hint">{{ t('不用写完整的故事，一个真实的小细节就很好。') }}</p><label class="field story-answer">{{ t('想写进歌里的回忆') }}<textarea v-model="draft.story" maxlength="4000" :aria-invalid="!!validationError||undefined" :placeholder="t(memoryHints[memoryDirection+1]!)" :aria-describedby="validationError?'story-memory-hint story-validation':'story-memory-hint'"></textarea></label><div class="story-memory-directions" role="group" :aria-label="t('换个角度想一想')"><button v-for="(label,index) in ['一个习惯的动作','一句熟悉的话','一次难忘的陪伴']" :key="label" type="button" :aria-pressed="memoryDirection===index" @click="memoryDirection=memoryDirection===index?-1:index">{{ t(label) }}</button></div><p id="story-memory-hint" class="save-info">{{ t('点一个方向找灵感，不会替你编写经历。') }}</p><button class="text-button story-skip" type="button" @click="goToPart(3)">{{ t('暂时想不起来，先写想说的话') }}</button></template>
          <template v-else-if="part===3"><p class="story-question-hint">{{ t('不必说得漂亮，说出你心里最真实的那句就好。') }}</p><label class="field story-answer">{{ t('最想对 TA 说的话') }}<span v-if="draft.story.trim()" class="story-optional">{{ t('选填') }}</span><textarea v-model="draft.message" maxlength="500" :aria-invalid="!!validationError||undefined" :aria-describedby="validationError?'story-validation':undefined" :placeholder="t('想说的感谢、想念，或一句平时没说出口的话。')"></textarea></label><p class="save-info">{{ draft.story.trim()?t('已经有一段回忆，这句话可以稍后再补。'):t('没有回忆也没关系，留下一句真心话就可以继续。') }}</p></template>
          <template v-else><p class="story-question-hint">{{ t('歌词语言独立于网站显示语言。') }}</p><div class="story-language-options"><label v-for="option in languages" :key="option.value"><input v-model="language" type="radio" name="lyric-language" :value="option.value"><span><strong>{{ t(option.label) }}</strong><small>{{ t(option.sample) }}</small></span></label></div><p class="save-info">{{ t('接下来先为你编写歌词，读过、改好后，再选择旋律与声音。') }}</p><p v-if="allowance" class="save-info">{{ allowance.dailyUnlimited?t('今日歌词编写不限次数'):t('今日还可编写 {remaining} 次歌词',{remaining:allowance.dailyRemaining}) }}</p></template>
        </div>
        <p v-if="validationError" id="story-validation" class="story-validation" role="alert">{{ t(validationError) }}</p>
        <div class="creation-action-bar story-action-bar"><button v-if="part>0" class="button secondary story-back" type="button" @click="goToPart(part-1)">{{ t('上一步') }}</button><button class="button creation-primary" type="submit" :disabled="!initialized||composing||resetting||(part===4&&(!ready||!aiReady||conflict))">{{ composing?t('正在编写歌词…'):part===4?t('生成歌词，继续制作'):part===3?t('选择歌词语言'):t('继续') }}</button></div>
        <div class="story-secondary-actions"><button v-if="part===3&&draft.story.trim()&&!draft.message.trim()" class="text-button" type="button" @click="goToPart(4)">{{ t('先跳过这句心意') }}</button><button v-if="route.query.returnRevision" type="button" class="text-button return-to-lyrics" @click="returnToLyrics">{{ t('返回歌词编辑') }}</button></div>
      </fieldset></form>
      <p v-if="part===4&&initialized&&!aiReady" class="notice">{{ t('歌词生成暂不可用，请稍后再试或手动填写') }} <RouterLink v-if="existing" :to="`/works/${existing.id}`">{{ t('手动填写歌词') }}</RouterLink></p>
    </section><aside class="draft-aside story-summary"><span class="story-summary-mark" aria-hidden="true">♪</span><h2>{{ t('慢慢说，') }}<br>{{ t('我们把它写成歌。') }}</h2><p>{{ t('一段真实的回忆，比一长段赞美更动人。') }}</p><dl v-if="draft.recipient.trim()||draft.occasion.trim()"><div v-if="draft.recipient.trim()"><dt>{{ t('送给谁') }}</dt><dd>{{ draft.recipient }}</dd><button type="button" :disabled="composing||resetting" @click="goToPart(0)">{{ t('修改') }}</button></div><div v-if="draft.occasion.trim()"><dt>{{ t('送歌时刻') }}</dt><dd>{{ occasionSuggestions.includes(draft.occasion)?t(draft.occasion):draft.occasion }}</dd><button type="button" :disabled="composing||resetting" @click="goToPart(1)">{{ t('修改') }}</button></div></dl><p class="story-privacy">{{ t('故事只对自己可见，送出礼物时才会创建分享链接。') }}</p></aside></div>
  </div>
</template>
