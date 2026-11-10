<script setup lang="ts">
import { normalizeLyricsStructure, type LyricsStructure } from '../model/lyrics-structure'
import { t, formatDate, locale } from '../../../shared/i18n'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSystemInfo } from '../../../shared/http/system'
import { ApiError, currentCreatorId } from '../../../shared/http/api-client'
import { readRecovery, writeRecovery, recoveryKey,checkpointRecovery } from '../../../shared/drafts/recovery'
import RecoveryCopies from '../../../shared/drafts/RecoveryCopies.vue'
import type { Work } from '../../studio'
import { listRevisions, getRevision, getLyricsRequest, saveRevision, confirmRevision, generateLyrics, rewriteLyricLine, type Revision, type LyricRewriteAction } from '../api/detail'
const props=defineProps<{work:Work;showInlineSave?:boolean}>(), emit=defineEmits<{selected:[revision:Revision|null];dirty:[value:boolean];ready:[value:boolean];recoverable:[value:boolean];base:[revision:Revision|null]}>()
const route=useRoute(),router=useRouter()
const versions=ref<Revision[]>([]),active=ref<Revision|null>(null),title=ref(props.work.title||(props.work.recipient?t('写给{recipient}的歌',{recipient:props.work.recipient}):t('我的原创歌曲'))),text=ref(''),baseline=ref(''),loading=ref(true),saving=ref(false),composing=ref(false),preparing=ref(false),error=ref(''),notice=ref(''),aiReady=ref(false),localSaved=ref(false)
const language=ref<string>(['zh-CN','en','mixed'].includes(String(route.query.language))?String(route.query.language):locale.value),instructions=ref(''),requestKey=ref('')
const structure=ref<LyricsStructure>(normalizeLyricsStructure({templateId:route.query.templateId,chorusFirst:route.query.chorusFirst==='true'}))
const errorKind=ref<'load'|'save'|'compose'>('save'),requestWaiting=ref(false),requestChecking=ref(false)
const blocked=computed(()=>loading.value||composing.value||preparing.value||requestWaiting.value||requestChecking.value)
const draftValue=()=>({title:title.value.trim(),lyrics:text.value})
const dirty=computed(()=>!loading.value&&JSON.stringify(draftValue())!==baseline.value)
const valid=computed(()=>!!title.value.trim()&&!!text.value.trim())
const needsSave=computed(()=>dirty.value||!active.value||active.value.workVersion!==props.work.version)
const canGenerate=computed(()=>valid.value&&!blocked.value&&!saving.value)
const canSave=computed(()=>canGenerate.value&&needsSave.value)
const savedTime=computed(()=>active.value&&!dirty.value?formatDate(active.value.createdAt):'')
const editor=ref<HTMLTextAreaElement>()
const selection=ref<{source:string;start:number;end:number;line:string;context:string}>()
const linePreview=ref<{selection:NonNullable<typeof selection.value>;text:string}>()
const lineBusy=ref(false),lineError=ref('')
const mirror=ref<HTMLDivElement>(), marker=ref<HTMLSpanElement>(), toolbar=ref<HTMLDivElement>()
const toolbarPosition=ref<{left:string;top:string}>()
const lineActions: {id:LyricRewriteAction;label:string;icon:string}[] = [
 {id:'REWRITE',label:'改写选句',icon:'m13 5 6 6M4 16l12-12 4 4L8 20H4v-4Z'},
 {id:'RHYME',label:'更押韵',icon:'M7 17l5-5m3-3 3-3M4 17a2 2 0 1 0 4 0 2 2 0 1 0-4 0Zm7-5a2 2 0 1 0 4 0 2 2 0 1 0-4 0Zm6-7a2 2 0 1 0 4 0 2 2 0 1 0-4 0Z'},
 {id:'COLLOQUIAL',label:'更口语化',icon:'M12 4a8 8 0 0 0-6 13l-1 3 4-1a8 8 0 1 0 3-15ZM8 12h.01m4 0h.01m4 0h.01'},
 {id:'SHORTEN',label:'缩短一句',icon:'m4 20 6-6m-6 0h6v6M20 4l-6 6m6 0h-6V4'},
]
function placeToolbar(){
 const el=editor.value, copy=mirror.value, selected=marker.value, bar=toolbar.value
 if(!el||!copy||!selected||!bar||!window.matchMedia?.('(min-width: 761px)').matches){toolbarPosition.value=undefined;return}
 const style=getComputedStyle(el)
 copy.style.width=`${el.clientWidth+parseFloat(style.borderLeftWidth)+parseFloat(style.borderRightWidth)}px`
 const area=copy.getBoundingClientRect()
 const boxes=Array.from(selected.getClientRects())
 const visible=boxes.filter(box=>box.bottom-area.top-el.scrollTop>0&&box.top-area.top-el.scrollTop<el.clientHeight)
 const last=visible.at(-1)
 if(!last||bar.offsetWidth+16>el.clientWidth){toolbarPosition.value=undefined;return}
 const x=last.right-area.left-el.scrollLeft, y=last.top-area.top-el.scrollTop
 const side=x+bar.offsetWidth+20<=el.clientWidth
 const left=side?x+14:Math.min(Math.max(last.left-area.left-el.scrollLeft,8),el.clientWidth-bar.offsetWidth-8)
 const below=y+last.height+7
 const top=side?y+(last.height-bar.offsetHeight)/2:below+bar.offsetHeight<=el.clientHeight?below:y-bar.offsetHeight-7
 toolbarPosition.value={left:`${left}px`,top:`${Math.max(4,top)}px`}
}
watch([selection,lineBusy],placeToolbar,{flush:'post'})
let editorObserver:ResizeObserver|undefined
onMounted(()=>{
 if(typeof ResizeObserver==='undefined'||!editor.value)return
 editorObserver=new ResizeObserver(placeToolbar)
 editorObserver.observe(editor.value)
})
onBeforeUnmount(()=>editorObserver?.disconnect())
function captureSelection(){
 const el=editor.value
 if(!el||lineBusy.value)return
 const {selectionStart:start,selectionEnd:end,value:source}=el
 const line=source.slice(start,end)
 selection.value=line.trim()&&line.length<=160&&!/[\r\n\u2028\u2029]/.test(line)&&!/^\s*\[.*\]\s*$/.test(line)
   ?{source,start,end,line,context:source.slice(Math.max(0,start-180),start)+'\n'+source.slice(end,end+179)}
   :undefined
}
async function previewLine(action:LyricRewriteAction){
 const chosen=selection.value
 if(!chosen||chosen.source!==text.value||lineBusy.value||blocked.value||saving.value)return
 lineBusy.value=true;lineError.value='';linePreview.value=undefined
 try{
   const result=await rewriteLyricLine(props.work.id,{requestId:crypto.randomUUID(),action,line:chosen.line,context:chosen.context,language:active.value?.language||language.value})
   if(!result.text?.trim()||result.text.length>160||/[\r\n\u2028\u2029]/.test(result.text)
      ||action==='SHORTEN'&&result.text.trim().length>=chosen.line.trim().length)throw new Error('未得到可用的单句改写，原歌词已保留。')
   if(alive)linePreview.value={selection:chosen,text:result.text}
 }catch(e){
   if(alive)lineError.value=e instanceof ApiError&&e.status===405
     ?'当前后端版本不支持选句润色，请更新后端后重试。'
     :e instanceof Error?e.message:'改写暂不可用，原歌词已保留。'
 }
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
const recentVersions=computed(()=>versions.value.slice(0,5))
const historyPreview=computed(()=>recentVersions.value.find(v=>v.id===historyId.value))
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
    remember()
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
  if(blocked.value||saving.value||lineBusy.value||!aiReady.value)return
  if(dirty.value&&!window.confirm(t('生成新歌词后将显示新版本，当前未保存的修改会被替换，是否继续？')))return
  composing.value=true;error.value='';notice.value='';errorKind.value='compose'
  try{
    if(inflight)await inflight
    requestKey.value ||= crypto.randomUUID();remember()
    const created=await generateLyrics(props.work.id,props.work.version,requestKey.value,language.value,instructions.value,active.value?.id,{...structure.value})
    if(!alive)return
    versions.value=[created,...versions.value.filter(v=>v.id!==created.id)];select(created);requestKey.value='';remember();await syncRoute(created.id);notice.value='新歌词已生成，旧版本已保留。你可以继续修改。'
  }catch(e){if(!alive)return;errorKind.value='compose';error.value=e instanceof Error?e.message:'歌词生成失败，请稍后再试';try{await recoverRequest()}catch{/* 无法确认时保留原请求键，避免重复付费。 */}}
  finally{composing.value=false;remember()}
}
onBeforeUnmount(()=>{remember();alive=false})
async function preserveDraftForNavigation(){if(inflight)await inflight;if(dirty.value){remember();if(!localSaved.value)throw new Error('请先保存歌词')}}
defineExpose({prepareForGeneration,preserveDraftForNavigation,needsSave,canSave,save,saving})
</script>
<template><section class="detail-panel lyrics-panel">
  <p v-if="error" class="notice error" role="alert">{{ t(error) }} <button class="text-button" :disabled="loading||composing||requestChecking||saving" @click="retryError">{{ t(errorKind==='load'?'刷新页面':errorKind==='save'?'重试保存':requestWaiting?'刷新编写状态':'重新编写歌词') }}</button></p>
  <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p>
  <button v-if="requestWaiting&&!error" class="button secondary" :disabled="loading||composing||requestChecking" @click="refreshRequest">{{ t('刷新编写状态') }}</button>
  <p v-if="!loading&&!localSaved" class="save-info">{{ t('浏览器无法保存本机副本，请确认显示已保存后再离开。') }}</p>
  <div class="lyric-title">
    <label for="song-title">{{ t('歌曲名称') }}</label>
    <span class="autosave-state" :class="{saved:active&&!dirty&&!saving}" role="status">{{ loading?t('正在读取歌词…'):saving?t('正在保存…'):dirty?t(localSaved?'已保留本机草稿':'有尚未保存的修改'):active?t('已保存'):t('尚未保存') }}</span>
    <input id="song-title" v-model="title" maxlength="100" :disabled="blocked||saving">
  </div>
  <div class="lyric-editor-box">
    <div class="lyric-editor-label">
      <span id="lyric-editor-help">{{ t('歌词可直接修改，保存后再生成歌曲') }}</span>
      <div class="lyric-editor-actions">
        <button v-if="aiReady" type="button" class="lyric-rewrite-all" :disabled="blocked||saving||lineBusy" @click="compose">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12l5-2 2-5 2 5 5 2-5 2-2 5-2-5-5-2Zm13-7h5m-2.5-2.5v5"/></svg>
          {{ composing?t('正在编写歌词…'):versions.length?t('AI 重新生成全部歌词'):t('根据故事生成歌词') }}
        </button>
        <button type="button" class="lyric-quiet-action" :disabled="blocked||saving||!dirty" :title="t('撤销未保存修改')" @click="revertDraft"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5 4 9l4 4M4 9h10a5 5 0 0 1 0 10h-3"/></svg>{{ t('恢复原版') }}</button>
      </div>
    </div>
    <div class="lyric-text-stage">
      <textarea id="song-lyrics" ref="editor" v-model="text" maxlength="12000" class="lyrics-edit lyric-text" :aria-label="t('歌词')" aria-describedby="lyric-editor-help" :disabled="blocked||saving" :placeholder="t('写下主歌、副歌，或者你想唱给TA听的话……')" @select="captureSelection" @keyup="captureSelection" @pointerup="captureSelection" @scroll.passive="placeToolbar"></textarea>
      <div v-if="selection" ref="mirror" class="lyric-text lyric-mirror" aria-hidden="true">{{ text.slice(0,selection.start) }}<span ref="marker">{{ selection.line }}</span>{{ text.slice(selection.end) }}</div>
      <div v-if="selection||lineBusy" ref="toolbar" class="lyric-line-tools" :class="{'is-anchored':toolbarPosition}" :style="toolbarPosition" role="group" :aria-label="t('选句润色')">
        <span v-if="lineBusy" class="line-busy" role="status">{{ t('正在润色…') }}</span>
        <button v-for="action in lineBusy?[]:lineActions" :key="action.id" type="button" :disabled="blocked||saving" @pointerdown.prevent @click="previewLine(action.id)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="action.icon"/></svg>{{ t(action.id==='REWRITE'?'改写':action.label) }}
        </button>
      </div>
    </div>
  </div>
  <p v-if="lineError" class="notice error" role="alert">{{ t(lineError) }}</p>
  <div v-if="linePreview" class="lyric-line-preview" role="region" :aria-label="t('改写建议')">
    <strong>{{ t('改写建议') }}</strong><p>{{ linePreview.text }}</p>
    <p v-if="!canReplaceLine" class="save-info">{{ t('原歌词已变化或替换后超出长度，可以复制建议后手动修改。') }}</p>
    <div class="actions"><button class="button secondary" type="button" :disabled="!canReplaceLine||blocked||saving" @click="replaceLine">{{ t('替换选句') }}</button><button class="text-button" type="button" @click="linePreview=undefined">{{ t('关闭') }}</button></div>
  </div>
  <div v-if="showInlineSave" class="lyrics-save-bar"><button type="button" class="button lyrics-save-button" :disabled="!canSave" @click="save">{{ saving?t('正在保存…'):t('保存歌词') }}</button></div>
  <details class="lyrics-history">
    <summary class="lyric-version-strip">
      <span class="lyric-version-meta"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6ZM14 3v5h4M9 12h6m-6 4h6"/></svg><strong>{{ t('当前版本') }} · {{ t(dirty||!active?'草稿':'已保存') }}</strong><time v-if="savedTime" :datetime="active?.createdAt">{{ savedTime }}</time></span>
      <span class="lyric-history-link">{{ t('查看历史版本') }} <span aria-hidden="true">›</span></span>
    </summary>
    <label v-if="versions.length" class="field">{{ t('历史版本（最近5条）') }}<select v-model="historyId" :disabled="blocked||saving"><option value="">{{ t('选择版本预览') }}</option><option v-for="revision in recentVersions" :key="revision.id" :value="revision.id">{{ revision.title }} · {{ formatDate(revision.createdAt) }}</option></select></label>
    <template v-if="historyPreview"><pre class="lyric-history-preview">{{ historyPreview.lyrics }}</pre>
      <p v-if="dirty" class="save-info">{{ t('当前还有未保存的修改。') }}</p>
      <div class="actions"><button v-if="dirty" type="button" class="button secondary" :disabled="blocked||saving||!valid" @click="loadHistory(true)">{{ t('保存当前再载入') }}</button><button type="button" class="button secondary" :disabled="blocked||saving" @click="loadHistory()">{{ t(dirty?'放弃修改并载入':'载入此版本') }}</button><button type="button" class="text-button" :disabled="saving" @click="historyId=''">{{ t('取消') }}</button></div>
    </template>
    <p v-if="!versions.length" class="save-info">{{ t('暂无已保存的歌词版本。') }}</p>
    <RecoveryCopies v-if="!loading&&cacheKey&&!blocked" :inert="saving||undefined" :storage-key="cacheKey" :validate="validRecovery" @restore="restoreCopy"/>
  </details>
</section></template>
<style scoped>
.lyrics-panel { padding:20px clamp(16px,2.15vw,36px); background:#fffdfd; border-radius:20px; }
.lyric-title { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:8px 12px; color:#242121; font-size:16px; font-weight:650; }
.lyric-title .autosave-state { font-size:13px; font-weight:500; text-align:right; }
.lyric-title .saved { color:#237954; }
.lyric-title input { grid-column:1/-1; min-width:0; width:100%; padding:9px 13px; border:1px solid #e8e0e2; border-radius:11px; background:#fffdfd; color:inherit; font:inherit; font-weight:500; }
.lyric-editor-label { display:flex; align-items:center; justify-content:space-between; gap:12px; margin:13px 0 10px; color:#72676a; font-size:14px; }
.lyric-quiet-action { display:inline-flex; align-items:center; justify-content:center; gap:6px; min-height:32px; padding:0; border:0; background:none; color:#6f5852; font:inherit; cursor:pointer; }
.lyric-editor-actions { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:6px 12px; }
.lyric-rewrite-all { display:inline-flex; align-items:center; gap:6px; min-height:34px; padding:4px 10px; border:1px solid #edc7ba; border-radius:999px; background:#fff5f0; color:#a63b25; font:inherit; font-weight:650; cursor:pointer; }
.lyric-rewrite-all:hover:not(:disabled) { background:#ffece2; }
.lyric-rewrite-all svg { width:16px; height:16px; flex:none; fill:none; stroke:currentColor; stroke-width:1.6; stroke-linecap:round; stroke-linejoin:round; }
.lyric-quiet-action:disabled { opacity:.63; cursor:default; }
.lyric-quiet-action svg,.lyric-version-strip svg,.lyric-line-tools svg { width:16px; height:16px; flex:none; fill:none; stroke:currentColor; stroke-width:1.7; stroke-linecap:round; stroke-linejoin:round; }
.lyric-text-stage { position:relative; min-width:0; }
.lyric-text { box-sizing:border-box; width:100%; padding:16px clamp(14px,1.85vw,30px); border:1px solid #e8e0e2; border-radius:13px; color:#211d1d; background:#fffdfd; font:inherit; font-size:clamp(15px,1.2vw,19px); font-weight:400; line-height:1.42; letter-spacing:normal; white-space:pre-wrap; overflow-wrap:break-word; }
.lyric-text-stage textarea.lyric-text { display:block; height:clamp(260px,37vh,352px); min-height:220px; resize:vertical; }
.lyric-text-stage textarea::selection { background:#ffebd0; color:#211d1d; }
.lyric-mirror { position:absolute; top:0; left:0; height:0; overflow:hidden; visibility:hidden; pointer-events:none; }
.lyric-line-tools { display:flex; align-items:center; flex-wrap:wrap; width:max-content; max-width:100%; min-height:40px; margin-top:8px; padding:3px 6px; border:1px solid #ede5e6; border-radius:23px; background:#fffdfd; box-shadow:0 5px 18px #3c2b2d15; }
.lyric-line-tools.is-anchored { position:absolute; z-index:1; margin:0; }
.lyric-line-tools button { display:inline-flex; align-items:center; gap:6px; min-height:30px; padding:4px 10px; border:0; border-radius:0; background:none; color:#221c1c; font:inherit; font-size:13px; white-space:nowrap; cursor:pointer; }
.lyric-line-tools button+button { border-left:1px solid #ece5e6; }
.lyric-line-tools button:hover:not(:disabled) { color:#bb321e; }
.lyric-line-tools button:disabled { opacity:.55; cursor:default; }
.line-busy { padding:3px 10px; color:#6f5852; font-size:13px; }
.lyrics-history { margin-top:12px; }
.lyric-version-strip { display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:6px 14px; min-height:42px; padding:7px 13px; border-radius:12px; background:#f7f2f0; color:#74635d; cursor:pointer; font-size:13px; list-style:none; }
.lyric-version-strip::-webkit-details-marker { display:none; }
.lyric-version-meta { display:flex; align-items:center; flex-wrap:wrap; gap:8px; }
.lyric-version-meta strong { font-weight:600; }
.lyric-version-meta time { margin-left:4px; color:#776a66; font-size:12px; }
.lyric-history-link { white-space:nowrap; }
.lyric-line-preview { border:1px solid #efcfc4; border-radius:14px; background:#fff6f1; padding:14px; margin:12px 0; }
.lyric-line-preview p { overflow-wrap:anywhere; }
.lyric-history-preview { max-height:220px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; font:inherit; padding:14px; border-radius:12px; background:#fff6f3; }
@media(max-width:760px) {
 .lyric-line-tools { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); width:100%; border-radius:13px; }
 .lyric-line-tools button { justify-content:center; min-height:40px; }
 .lyric-line-tools button:nth-of-type(odd) { border-left:0; }
 .lyric-editor-label { align-items:start; flex-direction:column; gap:6px; font-size:12px; }
 .lyric-editor-actions { width:100%; justify-content:space-between; }
 .lyric-quiet-action { flex:none; min-height:24px; }
}
</style>
