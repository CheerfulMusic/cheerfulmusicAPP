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
import { listRevisions, getRevision, getLyricsRequest, saveRevision, confirmRevision, generateLyrics, getUsage, rewriteLyricLine, type Revision, type Allowance } from '../api/detail'
const props=defineProps<{work:Work}>(), emit=defineEmits<{selected:[revision:Revision|null];dirty:[value:boolean];ready:[value:boolean];recoverable:[value:boolean];base:[revision:Revision|null]}>()
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
const canGenerate=computed(()=>valid.value&&!blocked.value&&!saving.value)
const editor=ref<HTMLTextAreaElement>()
const selection=ref<{source:string;start:number;end:number;line:string;context:string}>()
const linePreview=ref<{selection:NonNullable<typeof selection.value>;text:string}>()
const lineBusy=ref(false),lineError=ref('')
function captureSelection(){
 const el=editor.value
 if(!el||lineBusy.value)return
 const {selectionStart:start,selectionEnd:end,value:source}=el
 const line=source.slice(start,end)
 selection.value=line.trim()&&line.length<=160&&!/[\r\n]/.test(line)&&!/^\s*\[.*\]\s*$/.test(line)
   ?{source,start,end,line,context:source.slice(Math.max(0,start-180),start)+'\n'+source.slice(end,end+179)}
   :undefined
}
async function previewLine(action:'REWRITE'|'RHYME'){
 const chosen=selection.value
 if(!chosen||chosen.source!==text.value||lineBusy.value||blocked.value||saving.value)return
 lineBusy.value=true;lineError.value='';linePreview.value=undefined
 try{
   const result=await rewriteLyricLine(props.work.id,{requestId:crypto.randomUUID(),action,line:chosen.line,context:chosen.context,language:active.value?.language||language.value})
   if(!result.text?.trim()||result.text.length>160||/[\r\n]/.test(result.text))throw new Error('未得到可用的单句改写，原歌词已保留。')
   if(alive)linePreview.value={selection:chosen,text:result.text}
 }catch(e){if(alive)lineError.value=e instanceof Error?e.message:'改写暂不可用，原歌词已保留。'}
 finally{lineBusy.value=false}
}
const canReplaceLine=computed(()=>{
 const preview=linePreview.value
 return !!preview&&preview.selection.source===text.value&&text.value.length-preview.selection.line.length+preview.text.length<=12000
})
function replaceLine(){
 const preview=linePreview.value
 if(!preview||!canReplaceLine.value||blocked.value||saving.value)return
 const {start,end,source}=preview.selection
 text.value=source.slice(0,start)+preview.text+source.slice(end)
 linePreview.value=undefined
 void nextTick(()=>{editor.value?.focus({preventScroll:true});editor.value?.setSelectionRange(start+preview.text.length,start+preview.text.length)})
}
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
watch([dirty,active,composing,canGenerate],()=>{emit('dirty',dirty.value);emit('selected',dirty.value||composing.value?null:active.value);emit('ready',canGenerate.value);emit('base',active.value)},{immediate:true})
watch(text,()=>{selection.value=undefined})
watch(localSaved,value=>emit('recoverable',value),{immediate:true})
function select(value:Revision|null){active.value=value;title.value=value?.title||props.work.title||(props.work.recipient?t('写给{recipient}的歌',{recipient:props.work.recipient}):t('我的原创歌曲'));text.value=value?.lyrics||'';baseline.value=JSON.stringify(draftValue());remember()}
async function syncRoute(id:string){await nextTick();if(alive)await router.replace({query:{...route.query,revision:id,language:language.value,templateId:structure.value.templateId,chorusFirst:String(structure.value.chorusFirst)}})}
const historyId=ref('')
const historyPreview=computed(()=>versions.value.find(v=>v.id===historyId.value))
async function loadHistory(saveCurrent=false){
 const target=historyPreview.value
 if(!target||blocked.value||saving.value)return
 try{
   if(saveCurrent)await persist()
   if(!alive)return
   select(target);historyId.value='';error.value='';notice.value=''
   if(target.language)language.value=target.language
   if(target.templateId)structure.value=normalizeLyricsStructure(target)
   await syncRoute(target.id)
 }catch{/* 当前编辑与保存错误留在表单中。 */}
}
function revertDraft(){
 if(!dirty.value||blocked.value||saving.value||!window.confirm(t('撤销当前尚未保存的歌词修改？')))return
 select(active.value);error.value='';notice.value=''
}
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
    if(active.value?.language)language.value=active.value.language
    if(active.value?.templateId)structure.value=normalizeLyricsStructure(active.value)
    if(local){language.value=local.language;structure.value=normalizeLyricsStructure(local.structure??structure.value);instructions.value=local.instructions;requestKey.value=local.workVersion===props.work.version?local.requestKey:''
      if(local.dirty){select(versions.value.find(v=>v.id===local.baseId)||active.value);title.value=local.title;text.value=local.lyrics;notice.value=local.workVersion===props.work.version?'已恢复本机尚未保存的歌词。':'故事已更新，本机歌词已恢复，保存后将成为当前故事的新版本。'}
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
    const created=await saveRevision(props.work.id,props.work.version,submitted.title,submitted.lyrics,active.value?.id)
    versions.value=[created,...versions.value.filter(v=>v.id!==created.id)];active.value=created;baseline.value=JSON.stringify({title:created.title,lyrics:created.lyrics});remember();await syncRoute(created.id);return created
  }catch(e){error.value=e instanceof Error?e.message:'保存失败，请重试';remember();throw e}
  finally{saving.value=false;inflight=undefined}})()
  return inflight
}
async function save(){if(blocked.value||saving.value||!valid.value||!needsSave.value)return;try{notice.value='';await persist()}catch{/* 表单已显示错误。 */}}
async function prepareForGeneration(saveChanges=false):Promise<Revision>{
  if(blocked.value)throw new Error('请等待歌词处理完成。')
  if(saving.value)await inflight
  if(needsSave.value&&!saveChanges)throw new ApiError('请先点击保存，保存当前歌词后再生成歌曲。',409,'LYRICS_NOT_SAVED')
  preparing.value=true;error.value='';errorKind.value='save'
  try{
    if(inflight)await inflight
    let revision=needsSave.value?await persist():active.value!
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
const regenerateSection=ref<HTMLDetailsElement>()
async function reviewMusicDirection(){
  if(blocked.value||saving.value)return
  const direction=t('按当前音乐方向调整句长 节奏和表达 保留故事事实与重要原句')
  if(!instructions.value.includes(direction)&&instructions.value.length+direction.length+1<=1000)instructions.value+=(instructions.value?'\n':'')+direction
  await nextTick();if(regenerateSection.value){regenerateSection.value.open=true;regenerateSection.value.scrollIntoView({block:'center',behavior:'smooth'})}
}
defineExpose({prepareForGeneration,preserveDraftForNavigation,reviewMusicDirection,needsSave})
</script>
<template><section class="detail-panel lyrics-panel">
  <div class="detail-heading"><span class="autosave-state" role="status">{{ loading?t('正在读取歌词…'):saving?t('正在保存…'):dirty?t(localSaved?'已保留本机草稿':'有尚未保存的修改'):active?t('已保存'):t('尚未保存') }}</span></div>
  <p v-if="error" class="notice error" role="alert">{{ t(error) }} <button class="text-button" :disabled="loading||composing||requestChecking||saving" @click="retryError">{{ t(errorKind==='load'?'刷新页面':errorKind==='save'?'重试保存':requestWaiting?'刷新编写状态':'重新编写歌词') }}</button></p>
  <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
  <button v-if="requestWaiting&&!error" class="button secondary" :disabled="loading||composing||requestChecking" @click="refreshRequest">{{ t('刷新编写状态') }}</button>
  <p v-if="!loading&&!localSaved" class="save-info">{{ t('浏览器无法保存本机副本，请确认显示已保存后再离开。') }}</p>
  <RecoveryCopies v-if="!loading&&cacheKey&&!blocked" :inert="saving||undefined" :storage-key="cacheKey" :validate="validRecovery" @restore="restoreCopy"/>
  <label class="field">{{ t('歌曲名称') }}<input v-model="title" maxlength="100" :disabled="blocked||saving"></label>
  <div class="lyric-editor-box">
    <div class="lyric-editor-label"><label for="song-lyrics">{{ t('歌词') }}</label><button type="button" class="text-button" :disabled="blocked||saving||!dirty" @click="revertDraft">{{ t('撤销未保存修改') }}</button></div>
    <textarea id="song-lyrics" ref="editor" v-model="text" maxlength="12000" class="lyrics-edit" :disabled="blocked||saving" :placeholder="t('写下主歌、副歌，或者你想唱给TA听的话……')" @select="captureSelection" @keyup="captureSelection" @pointerup="captureSelection"></textarea>
    <div v-if="selection||lineBusy" class="lyric-line-tools" role="group" :aria-label="t('选句润色')">
      <span v-if="lineBusy" role="status">{{ t('正在润色…') }}</span>
      <template v-else><button type="button" class="button secondary" :disabled="blocked||saving" @pointerdown.prevent @click="previewLine('REWRITE')">{{ t('改写选句') }}</button><button type="button" class="button secondary" :disabled="blocked||saving" @pointerdown.prevent @click="previewLine('RHYME')">{{ t('更押韵') }}</button></template>
    </div>
  </div>
  <p v-if="lineError" class="notice error" role="alert">{{ t(lineError) }}</p>
  <div v-if="linePreview" class="lyric-line-preview" role="region" :aria-label="t('改写建议')">
    <strong>{{ t('改写建议') }}</strong><p>{{ linePreview.text }}</p>
    <p v-if="!canReplaceLine" class="save-info">{{ t('原歌词已变化或替换后超出长度，可以复制建议后手动修改。') }}</p>
    <div class="actions"><button class="button secondary" type="button" :disabled="!canReplaceLine||blocked||saving" @click="replaceLine">{{ t('替换选句') }}</button><button class="text-button" type="button" @click="linePreview=undefined">{{ t('关闭') }}</button></div>
  </div>
  <div class="lyrics-save-bar"><p class="save-info">{{ t('选中一句歌词，可以用 AI 润色。') }}</p><button type="button" class="button lyrics-save-button" :disabled="blocked||saving||!valid||!needsSave" @click="save">{{ saving?t('正在保存…'):t('保存歌词') }}</button></div>
  <details ref="regenerateSection" v-if="aiReady" class="creation-fold lyrics-regenerate" :open="!versions.length"><summary>{{ versions.length?t('想换个写法？调整并重新生成'):t('根据故事生成歌词') }}</summary><LyricsTemplatePicker v-model="structure" :disabled="blocked||saving"/><div class="regenerate-fields"><label class="field">{{ t('歌词语言') }}<select v-model="language" :disabled="blocked"><option value="zh-CN">{{ t('中文') }}</option><option value="en">English</option><option value="mixed">{{ t('中英混合') }}</option></select></label><label class="field">{{ t('修改要求（可选）') }}<textarea v-model="instructions" maxlength="1000" :disabled="blocked" :placeholder="t('例如：保留第二段，副歌加入妈妈的名字，少一点直白表达。')"></textarea></label></div><div class="lyric-writing-directions"><p class="save-info">{{ t('点选润色方向，也可以自己补充要求；点击重新生成后才会开始创作。') }}</p><div class="suggestion-tags" role="group" :aria-label="t('歌词润色方向')"><button v-for="direction in writingDirections" :key="direction.label" type="button" class="suggestion-tag" :class="{selected:directionSelected(direction.value)}" :aria-pressed="directionSelected(direction.value)" :disabled="blocked||(!directionSelected(direction.value)&&instructions.length+t(direction.value).length+1>1000)" @click="toggleDirection(direction.value)">{{ t(direction.label) }}</button></div></div><p v-if="versions.length" class="save-info">{{ t('重新生成以已保存的歌词为基础；如需保留手动修改，请先保存。') }}</p><button class="button secondary" :disabled="blocked||saving" @click="compose">{{ composing?t('正在编写歌词…'):versions.length?t('重新生成歌词'):t('根据故事生成歌词') }}</button><p class="save-info">{{ t('重新生成会使用一次歌词创作额度。') }} <span v-if="allowance">{{ allowance.dailyUnlimited?t('今日歌词编写不限次数'):t('今日还可编写 {remaining} 次歌词',{remaining:allowance.dailyRemaining}) }}</span></p></details>
  <details v-if="versions.length" class="creation-fold lyrics-history">
    <summary>{{ t('查看历史歌词版本') }}</summary>
    <label class="field">{{ t('历史版本（最近100条）') }}<select v-model="historyId" :disabled="blocked||saving"><option value="">{{ t('选择版本预览') }}</option><option v-for="revision in versions" :key="revision.id" :value="revision.id">{{ revision.title }} · {{ formatDate(revision.createdAt) }}</option></select></label>
    <template v-if="historyPreview"><pre class="lyric-history-preview">{{ historyPreview.lyrics }}</pre>
      <p v-if="dirty" class="save-info">{{ t('当前还有未保存的修改。') }}</p>
      <div class="actions"><button v-if="dirty" type="button" class="button secondary" :disabled="blocked||saving||!valid" @click="loadHistory(true)">{{ t('保存当前再载入') }}</button><button type="button" class="button secondary" :disabled="blocked||saving" @click="loadHistory()">{{ t(dirty?'放弃修改并载入':'载入此版本') }}</button><button type="button" class="text-button" :disabled="saving" @click="historyId=''">{{ t('取消') }}</button></div>
    </template>
  </details>
  <p class="save-info">{{ t('点击保存或重新生成歌词才会新增版本，已经生成的歌曲不会改变。') }}</p>
</section></template>
<style scoped>
.detail-heading { justify-content: flex-end; margin-bottom: 0; }
.lyric-editor-label { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:12px 0 8px; font-weight:600; }
.lyric-editor-label button { font-weight:400; }
.lyric-editor-box { position:relative; min-width:0; }
.lyric-editor-box textarea { box-sizing:border-box; width:100%; min-height:250px; padding:14px; border:1px solid var(--line,#ead9df); border-radius:14px; color:var(--ink); background:#fffdfd; font:inherit; line-height:1.8; resize:vertical; }
.lyric-line-tools { display:flex; justify-content:flex-end; flex-wrap:wrap; align-items:center; gap:8px; padding:8px 0; }
.lyric-line-tools .button { padding:6px 14px; min-height:36px; }
.lyric-line-preview { border:1px solid #efcfc4; border-radius:14px; background:#fff6f1; padding:14px; margin:12px 0; }
.lyric-line-preview p { overflow-wrap:anywhere; }
.lyric-history-preview { max-height:220px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; font:inherit; padding:14px; border-radius:12px; background:#fff6f3; }
@media(max-width:600px) { .lyric-line-tools { justify-content:flex-start; } .lyric-editor-box textarea { min-height:215px; } }
@media(min-width:761px) {
  .lyric-editor-box:has(.lyric-line-tools) { padding-bottom:45px; }
  .lyric-line-tools { position:absolute; right:10px; bottom:2px; padding:4px; border:1px solid #ead9df; border-radius:999px; background:#fffafa; box-shadow:0 4px 15px #54313c14; }
}
</style>
