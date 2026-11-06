<script setup lang="ts">
import LyricsTemplatePicker from './LyricsTemplatePicker.vue'
import { normalizeLyricsStructure, type LyricsStructure } from '../model/lyrics-structure'
import {english} from '../../../shared/i18n/en'
import { t, formatDate, locale } from '../../../shared/i18n'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { ApiError, currentCreatorId } from '../../../shared/http/api-client'
import { readRecovery, writeRecovery, recoveryKey,checkpointRecovery } from '../../../shared/drafts/recovery'
import RecoveryCopies from '../../../shared/drafts/RecoveryCopies.vue'
import type { Work } from '../../studio'
import { listRevisions, getRevision, getLyricsRequest, saveRevision, confirmRevision, generateLyrics, getUsage, type Revision, type Allowance } from '../api/detail'
const props=defineProps<{work:Work}>(), emit=defineEmits<{selected:[revision:Revision|null];dirty:[value:boolean];ready:[value:boolean];recoverable:[value:boolean]}>()
const route=useRoute(),router=useRouter()
const versions=ref<Revision[]>([]),active=ref<Revision|null>(null),title=ref(props.work.title||(props.work.recipient?t('写给{recipient}的歌',{recipient:props.work.recipient}):t('我的原创歌曲'))),text=ref(''),baseline=ref(''),loading=ref(true),saving=ref(false),composing=ref(false),preparing=ref(false),error=ref(''),notice=ref(''),aiReady=ref(false),localSaved=ref(false),allowance=ref<Allowance>()
const language=ref<string>(['zh-CN','en','mixed'].includes(String(route.query.language))?String(route.query.language):locale.value),instructions=ref(''),requestKey=ref('')
const structure=ref<LyricsStructure>(normalizeLyricsStructure({templateId:route.query.templateId,chorusFirst:route.query.chorusFirst==='true'}))
const errorKind=ref<'load'|'save'|'compose'>('save'),requestWaiting=ref(false),requestChecking=ref(false)
const writingDirections=[
 {label:'更含蓄一些',value:'用动作和细节承载情感，减少直白表白，但保留一处真诚直接的表达。'},
 {label:'更多画面感',value:'围绕故事里已有的物件、动作或原话增加画面，不补编经历。'},
 {label:'少些套话',value:'删减空泛祝福和重复的赞美，保留真实细节与自然口语。'},
 {label:'副歌更难忘',value:'让副歌围绕一个来自故事的核心意象，提炼短而有回味的记忆句。'},
 {label:'情绪有递进',value:'让第二段和桥段带来新的理解，不反复讲同一种心情。'},
]
const directionVariants=(value:string)=>[value,english[value]||value]
const directionSelected=(value:string)=>instructions.value.split('\n').some(line=>directionVariants(value).includes(line))
function toggleDirection(value:string){
 if(directionSelected(value)){instructions.value=instructions.value.split('\n').filter(line=>!directionVariants(value).includes(line)).join('\n');return}
 const next=instructions.value+(instructions.value&&!instructions.value.endsWith('\n')?'\n':'')+t(value)
 if(next.length<=1000)instructions.value=next
}
const blocked=computed(()=>loading.value||composing.value||preparing.value||requestWaiting.value||requestChecking.value)
const draftValue=()=>({title:title.value.trim(),lyrics:text.value})
const dirty=computed(()=>!loading.value&&JSON.stringify(draftValue())!==baseline.value)
const valid=computed(()=>!!title.value.trim()&&!!text.value.trim())
const needsSave=computed(()=>dirty.value||!active.value||active.value.workVersion!==props.work.version)
let alive=true,inflight:Promise<Revision>|undefined,cacheKey=''
interface Recovery {structure?:LyricsStructure;title:string;lyrics:string;workVersion:number;baseId:string|null;language:string;instructions:string;requestKey:string;dirty:boolean}
function validRecovery(v:unknown):v is Recovery {const r=v as Recovery;return !!r&&typeof r.title==='string'&&typeof r.lyrics==='string'&&r.title.length<=100&&r.lyrics.length<=12000&&['zh-CN','en','mixed'].includes(r.language)&&typeof r.instructions==='string'&&typeof r.requestKey==='string'}
function remember(){if(loading.value||!cacheKey)return;localSaved.value=writeRecovery(cacheKey,{...draftValue(),workVersion:props.work.version,baseId:active.value?.id||null,language:language.value,structure:{...structure.value},instructions:instructions.value,requestKey:requestKey.value,dirty:dirty.value} satisfies Recovery)}
function restoreCopy(value:Recovery){
 if(blocked.value||saving.value)return
 if(!checkpointRecovery(cacheKey,{...draftValue(),workVersion:props.work.version,baseId:active.value?.id||null,language:language.value,structure:{...structure.value},instructions:instructions.value,requestKey:requestKey.value,dirty:dirty.value} satisfies Recovery)){errorKind.value='save';error.value='无法保留当前编辑，已停止恢复。请先复制内容，或清理浏览器存储后重试。';return}
 active.value=versions.value.find(v=>v.id===value.baseId)||null
 baseline.value=JSON.stringify({title:active.value?.title||'',lyrics:active.value?.lyrics||''})
 title.value=value.title;text.value=value.lyrics;language.value=value.language;structure.value=normalizeLyricsStructure(value.structure);instructions.value=value.instructions
 requestKey.value=value.workVersion===props.work.version?value.requestKey:''
 error.value='';notice.value='已恢复本机副本，替换前的编辑也已另存。';remember()
}
// 输入只保留本机恢复副本；仅用户明确保存时写入版本历史。
watch([title,text,language,structure,instructions,requestKey],remember,{flush:'sync'})
watch([dirty,active,composing,preparing,valid,loading,saving],()=>{emit('dirty',dirty.value);emit('selected',dirty.value||composing.value?null:active.value);emit('ready',valid.value&&!needsSave.value&&!blocked.value&&!saving.value)},{immediate:true})
watch(localSaved,value=>emit('recoverable',value),{immediate:true})
function select(value:Revision|null){active.value=value;title.value=value?.title||props.work.title||(props.work.recipient?t('写给{recipient}的歌',{recipient:props.work.recipient}):t('我的原创歌曲'));text.value=value?.lyrics||'';baseline.value=JSON.stringify(draftValue());remember()}
async function syncRoute(id:string){await nextTick();if(alive)await router.replace({query:{...route.query,revision:id,language:language.value,templateId:structure.value.templateId,chorusFirst:String(structure.value.chorusFirst)}})}
async function choose(event:Event){if(saving.value||blocked.value)return;const target=event.target as HTMLSelectElement;try{if(dirty.value&&!window.confirm(t('切换版本会放弃未保存的歌词，是否继续？'))){target.value=active.value?.id||'';return}const revision=versions.value.find(v=>v.id===target.value)||null;select(revision);if(revision)await syncRoute(revision.id);error.value='';notice.value=''}catch{target.value=active.value?.id||''}}
async function updateAllowance(){try{allowance.value=(await getUsage()).lyrics}catch{/* 额度以提交时的后端检查为准。 */}}
async function recoverRequest(){
  const key=requestKey.value;if(!key)return
  requestChecking.value=true
  try{
    const state=await getLyricsRequest(props.work.id,key)
    if(!alive||key!==requestKey.value)return
    requestWaiting.value=state.status==='PENDING'||state.status==='UNKNOWN'
    if(state.status==='FAILED'){
      requestKey.value='';notice.value='上次歌词编写未完成，现已恢复。点击重新编写即可再试，已有内容会保留。'
    }else if(state.status==='SUCCEEDED'&&state.revisionId){
      const found=await getRevision(props.work.id,state.revisionId)
      if(!alive||key!==requestKey.value)return
      versions.value=[found,...versions.value.filter(v=>v.id!==found.id)]
      // 结果恢复不覆盖用户尚未保存的编辑。
      if(JSON.stringify(draftValue())===baseline.value){select(found);await syncRoute(found.id)}
      requestKey.value='';error.value='';notice.value='上次编写的歌词已找回，可在历史版本中查看。'
    }else if(requestWaiting.value)notice.value='歌词仍在编写中，可以稍后刷新编写状态。'
    remember();void updateAllowance()
  }finally{requestChecking.value=false}
}
async function refreshRequest(){try{await recoverRequest()}catch(e){errorKind.value='compose';error.value=e instanceof Error?e.message:'暂时无法确认编写状态，请稍后刷新。'}}
async function retryError(){if(errorKind.value==='load'){window.location.reload();return}if(errorKind.value==='save')await save();else if(requestWaiting.value)await refreshRequest();else await compose()}
onMounted(async()=>{
  try{
    const [history,system]=await Promise.all([listRevisions(props.work.id),getSystemInfo()]);versions.value=history;aiReady.value=system.capabilities.lyricsGeneration
    select(history.find(v=>v.id===route.query.revision)||history[0]||null)
    cacheKey=recoveryKey(currentCreatorId(),'lyrics',props.work.id)
    const local=readRecovery(cacheKey,validRecovery)
    if(local){language.value=local.language;structure.value=normalizeLyricsStructure(local.structure??structure.value);instructions.value=local.instructions;requestKey.value=local.requestKey
      if(local.dirty){title.value=local.title;text.value=local.lyrics;notice.value=local.workVersion===props.work.version?'已恢复本机尚未保存的歌词。':'故事已更新，本机歌词已恢复，保存后将成为当前故事的新版本。'}
    }
    if(requestKey.value)await recoverRequest()
    void updateAllowance()
  }catch(e){errorKind.value='load';error.value=e instanceof Error?e.message:'歌词读取失败'}
  finally{loading.value=false;remember()}
})
function persist():Promise<Revision>{
  if(inflight)return inflight
  if(!valid.value)return Promise.reject(new Error('请填写标题和歌词，并检查长度'))
  if(active.value&&!dirty.value&&active.value.workVersion===props.work.version)return Promise.resolve(active.value)
  const submitted=draftValue();saving.value=true;error.value='';errorKind.value='save'
  inflight=(async()=>{try{
    const created=await saveRevision(props.work.id,props.work.version,submitted.title,submitted.lyrics)
    versions.value=[created,...versions.value.filter(v=>v.id!==created.id)];active.value=created;baseline.value=JSON.stringify({title:created.title,lyrics:created.lyrics});remember();await syncRoute(created.id);return created
  }catch(e){error.value=e instanceof Error?e.message:'保存失败，请重试';remember();throw e}
  finally{saving.value=false;inflight=undefined}})()
  return inflight
}
async function save(){if(blocked.value||saving.value||!valid.value||!needsSave.value)return;try{notice.value='';await persist()}catch{/* 表单已显示错误。 */}}
async function prepareForGeneration():Promise<Revision>{
  if(blocked.value)throw new Error('请等待歌词处理完成。')
  if(saving.value)await inflight
  if(needsSave.value)throw new ApiError('请先点击保存，保存当前歌词后再生成歌曲。',409,'LYRICS_NOT_SAVED')
  preparing.value=true;error.value='';errorKind.value='save'
  try{
    if(inflight)await inflight
    let revision=active.value!
    if(!revision.confirmed){revision=await confirmRevision(props.work.id,revision.id);versions.value=versions.value.map(v=>v.id===revision.id?revision:v);active.value=revision}
    remember();return revision
  }catch(e){error.value=e instanceof Error?e.message:'保存失败，请重试';throw e}
  finally{preparing.value=false}
}
watch([language,instructions,structure],()=>{if(!composing.value&&!requestWaiting.value&&!requestChecking.value)requestKey.value=''},{flush:'sync'})
async function compose(){
  if(blocked.value||saving.value||!aiReady.value)return
  if(dirty.value&&!window.confirm(t('生成新歌词后将显示新版本，当前未保存的修改会被替换，是否继续？')))return
  composing.value=true;error.value='';notice.value='';errorKind.value='compose'
  try{
    if(inflight)await inflight
    requestKey.value ||= crypto.randomUUID();remember()
    const created=await generateLyrics(props.work.id,props.work.version,requestKey.value,language.value,instructions.value,active.value?.id,{...structure.value})
    versions.value=[created,...versions.value.filter(v=>v.id!==created.id)];select(created);requestKey.value='';remember();await syncRoute(created.id);notice.value='新歌词已生成，旧版本已保留。你可以继续修改。'
  }catch(e){errorKind.value='compose';error.value=e instanceof Error?e.message:'歌词生成失败，请稍后再试';try{await recoverRequest()}catch{/* 无法确认时保留原请求键，避免重复付费。 */}}
  finally{composing.value=false;remember();void updateAllowance()}
}
onBeforeUnmount(()=>{remember();alive=false})
async function preserveDraftForNavigation(){if(inflight)await inflight;if(dirty.value){remember();if(!localSaved.value)throw new Error('请先保存歌词')}}
defineExpose({prepareForGeneration,preserveDraftForNavigation})
</script>
<template><section class="detail-panel lyrics-panel">
  <div class="detail-heading"><h2>{{ t('把想说的话，写进歌里。') }}</h2><span class="autosave-state" role="status">{{ saving?t('正在保存…'):needsSave?t('有尚未保存的修改'):t('已保存') }}</span></div>
  <p class="detail-muted">{{ t('歌词可以直接修改，改好后点击保存，再生成歌曲。') }}</p>
  <p v-if="error" class="notice error" role="alert">{{ t(error) }} <button class="text-button" :disabled="loading||composing||requestChecking||saving" @click="retryError">{{ t(errorKind==='load'?'刷新页面':errorKind==='save'?'重试保存':requestWaiting?'刷新编写状态':'重新编写歌词') }}</button></p>
  <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
  <button v-if="requestWaiting&&!error" class="button secondary" :disabled="loading||composing||requestChecking" @click="refreshRequest">{{ t('刷新编写状态') }}</button>
  <p v-if="!loading&&!localSaved" class="save-info">{{ t('浏览器无法保存本机副本，请确认显示已保存后再离开。') }}</p>
  <RecoveryCopies v-if="!loading&&cacheKey&&!blocked" :inert="saving||undefined" :storage-key="cacheKey" :validate="validRecovery" @restore="restoreCopy"/>
  <label class="field">{{ t('歌曲名称') }}<input v-model="title" maxlength="100" :disabled="blocked"></label>
  <label class="field">{{ t('歌词') }}<textarea v-model="text" maxlength="12000" class="lyrics-edit" :disabled="blocked" :placeholder="t('写下主歌、副歌，或者你想唱给TA听的话……')"></textarea></label>
  <div class="lyrics-save-bar"><p class="save-info" role="status">{{ needsSave?localSaved?t('修改已保留在本机，点击保存才会新增版本。'):t('修改尚未保存，请点击保存。'):t('已保存，继续修改后可以保存为新版本。') }}</p><button type="button" class="button lyrics-save-button" :disabled="blocked||saving||!valid||!needsSave" @click="save">{{ saving?t('正在保存…'):t('保存') }}</button></div>
  <details v-if="aiReady" class="creation-fold lyrics-regenerate" :open="!versions.length"><summary>{{ versions.length?t('想换个写法？调整并重新生成'):t('根据故事生成歌词') }}</summary><LyricsTemplatePicker v-model="structure" :disabled="blocked||saving"/><div class="regenerate-fields"><label class="field">{{ t('歌词语言') }}<select v-model="language" :disabled="blocked"><option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option><option value="mixed">{{ t('中英混合') }}</option></select></label><label class="field">{{ t('修改要求（可选）') }}<textarea v-model="instructions" maxlength="1000" :disabled="blocked" :placeholder="t('例如：保留第二段，副歌加入妈妈的名字，少一点直白表达。')"></textarea></label></div><div class="lyric-writing-directions"><p class="save-info">{{ t('点选润色方向，也可以自己补充要求；点击重新生成后才会开始创作。') }}</p><div class="suggestion-tags" role="group" :aria-label="t('歌词润色方向')"><button v-for="direction in writingDirections" :key="direction.label" type="button" class="suggestion-tag" :class="{selected:directionSelected(direction.value)}" :aria-pressed="directionSelected(direction.value)" :disabled="blocked||(!directionSelected(direction.value)&&instructions.length+t(direction.value).length+1>1000)" @click="toggleDirection(direction.value)">{{ t(direction.label) }}</button></div></div><p v-if="versions.length" class="save-info">{{ t('重新生成以已保存的歌词为基础；如需保留手动修改，请先保存。') }}</p><button class="button secondary" :disabled="blocked||saving" @click="compose">{{ composing?t('正在编写歌词…'):versions.length?t('重新生成歌词'):t('根据故事生成歌词') }}</button><p class="save-info">{{ t('重新生成会使用一次歌词创作额度。') }} <span v-if="allowance">{{ allowance.dailyUnlimited?t('今日歌词编写不限次数'):t('今日还可编写 {remaining} 次歌词',{remaining:allowance.dailyRemaining}) }}</span></p></details>
  <details v-if="versions.length" class="creation-fold lyrics-history"><summary>{{ t('查看历史歌词版本') }}</summary><label class="field">{{ t('历史版本（最近100条）') }}<select :value="active?.id||''" :disabled="blocked||saving" @change="choose"><option value="">{{ t('新写一版') }}</option><option v-for="revision in versions" :key="revision.id" :value="revision.id">{{ revision.confirmed?t('已确认'):t('待确认') }} · {{ formatDate(revision.createdAt) }}</option></select></label></details>
  <p class="save-info">{{ t('点击保存或重新生成歌词才会新增版本，已经生成的歌曲不会改变。') }}</p>
</section></template>
