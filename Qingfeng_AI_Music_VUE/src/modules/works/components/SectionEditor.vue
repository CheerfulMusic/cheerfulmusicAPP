<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {t,formatDate,defaultCover} from '../../../shared/i18n'
import {ApiError,currentCreatorId} from '../../../shared/http/api-client'
import {usePlayer} from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import type {Work} from '../../studio'
import {audioUrl,cancelTask,type AudioAsset,type GenerationTask} from '../api/detail'
import {getAudioSelection,listSectionEdits,sectionContext,selectAudio,submitSectionEdit,type SectionContext,type SectionEdit,type SectionInput} from '../api/section-edits'
import {hintedLine,lyricSlices,replaceLyrics,sectionReplacement,suggestedWindow,validWindow,windowLines,alignWindow,type EditHint} from '../model/section-edit'
const props=defineProps<{work:Work;asset:AudioAsset;title:string;cover?:string;open:boolean;hint?:EditHint;tasks:GenerationTask[]}>()
const emit=defineEmits<{close:[];refresh:[];adopted:[]}>()
const player=usePlayer(),dialog=ref<HTMLDialogElement>(),context=ref<SectionContext>(),items=ref<SectionEdit[]>([]),loading=ref(false),sending=ref(false),adopting=ref(false),error=ref(''),notice=ref('')
const first=ref(-1),last=ref(-1),replacement=ref(''),start=ref(0),end=ref(10),loop=ref(true),selectionVersion=ref(0),selectionId=ref<string|null>(null),uncertain=ref<SectionInput>(),compared=ref<SectionEdit>()
const slices=computed(()=>lyricSlices(context.value?.lyrics||''))
const original=computed(()=>first.value>=0&&last.value>=first.value&&slices.value[last.value]?(context.value?.lyrics||'').slice(slices.value[first.value]!.start,slices.value[last.value]!.end):'')
const full=computed(()=>first.value>=0&&slices.value[last.value]?replaceLyrics(context.value?.lyrics||'',slices.value[first.value]!.start,slices.value[last.value]!.end,replacement.value):'')
const active=computed(()=>items.value.find(e=>e.sourceAssetId===props.asset.id&&['QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN'].includes(e.status)))
const locked=computed(()=>loading.value||sending.value||!!uncertain.value||!!active.value)
const canSubmit=computed(()=>context.value?.available&&!locked.value&&first.value>=0&&last.value>=first.value&&!!replacement.value.trim()&&replacement.value.trim()!==original.value.trim()&&full.value.length<=5000&&validWindow(start.value,end.value,context.value.duration||0))
let alive=true,sequence=0,refreshSequence=0,previewing=false
const clock=(value:number)=>`${Math.floor(value/60).toString().padStart(2,'0')}:${(value%60).toFixed(2).padStart(5,'0')}`
const key=()=>`qingfeng.section-edit:${currentCreatorId()}:${props.work.id}:${props.asset.id}`
function remember(){if(!context.value?.available)return;try{sessionStorage.setItem(key(),JSON.stringify({first:first.value,last:last.value,replacement:replacement.value,start:start.value,end:end.value,uncertain:uncertain.value}))}catch{/* 失败时仍在内存保留同一请求标识。 */}}
watch([first,last,replacement,start,end,uncertain],remember)
watch(loop,value=>{if(previewing&&player.segment)player.segment.loop=value})
const track=(id:string,variant=false)=>({id,title:variant?`${props.title} · ${t('修改版试听')}`:props.title,cover:props.cover||defaultCover(),audio:audioUrl(props.work.id,id),provenance:'GENERATED' as const})
async function preview(id:string,from:number,until:number,variant=false){previewing=true;await player.playSegment(track(id,variant),Math.max(0,from),until,loop.value)}
function stopPreview(){if(previewing&&player.current?.audio.includes(`/app/works/${props.work.id}/audio/`))player.stopSegment(true);previewing=false}
async function refresh(){
  const turn=++refreshSequence,work=props.work.id
  try{
    const [found,selected]=await Promise.all([listSectionEdits(work),getAudioSelection(work)])
    if(!alive||turn!==refreshSequence||props.work.id!==work)return
    items.value=found;selectionVersion.value=selected.version;selectionId.value=selected.assetId
    if(uncertain.value){const accepted=props.tasks.find(task=>task.requestKey===uncertain.value?.requestKey);if(accepted){uncertain.value=undefined;error.value='';notice.value='修改请求已受理，可以在下方查看进度。';emit('close')}}
    if(['修改版正在制作，原版仍可正常试听。','修改请求已受理，可以在下方查看进度。'].includes(notice.value)){
      if(found[0]?.status==='SUCCEEDED')notice.value='修改版已准备好，先对比试听，再决定是否使用。'
      else if(['FAILED','CANCELLED'].includes(found[0]?.status||''))notice.value=''
    }
    if(compared.value)compared.value=found.find(e=>e.taskId===compared.value?.taskId)
  }catch(e){if(alive&&turn===refreshSequence)error.value=e instanceof Error?e.message:'修改记录暂时无法读取，请重试。'}
}
function pick(from:number,to:number){
  if(replacement.value.trim()!==original.value.trim()&&original.value&&!window.confirm(t('切换歌词范围会替换当前编辑内容，是否继续？')))return
  first.value=from;last.value=Math.max(from,to)
  replacement.value=original.value
}
async function openEditor(){
  let saved:unknown;try{saved=JSON.parse(sessionStorage.getItem(key())||'null')}catch{/* 没有可恢复的草稿。 */}
  const turn=++sequence;loading.value=true;error.value='';notice.value='';context.value=undefined;uncertain.value=undefined
  await nextTick();if(!dialog.value?.open)dialog.value?.showModal()
  try{
    const found=await sectionContext(props.work.id,props.asset.id)
    if(!alive||turn!==sequence)return
    context.value=found
    if(!found.available)return
    const window=alignWindow(suggestedWindow(found.duration!,props.hint),found.lines||[],found.duration!);start.value=window.start;end.value=window.end
    const selected=hintedLine(slices.value,props.hint)
    first.value=selected;last.value=selected
    const covered=windowLines(slices.value,found.lines||[],start.value,end.value)
    if(covered.length){
      const lines=selected>=0?[...covered,selected]:covered
      first.value=Math.min(...lines);last.value=Math.max(...lines)
    }
    replacement.value=original.value
    if(props.hint?.replacement&&selected>=0){
      replacement.value=sectionReplacement(original.value,slices.value[first.value]!,slices.value[selected]!,props.hint.replacement)
    }else if(props.hint?.replacement){
      first.value=-1;last.value=-1;replacement.value=''
    }
    const draft=saved as {first:number;last:number;replacement:string;start:number;end:number;uncertain?:SectionInput}|null
    if(draft&&Number.isInteger(draft.first)&&Number.isInteger(draft.last)&&draft.first>=0&&draft.last>=draft.first&&draft.last<slices.value.length&&typeof draft.replacement==='string'&&draft.replacement.length<=5000&&validWindow(draft.start,draft.end,found.duration!)){
      if(draft.uncertain?.sourceAssetId===props.asset.id&&/^[a-f0-9-]{36}$/i.test(draft.uncertain.requestKey)){first.value=draft.first;last.value=draft.last;replacement.value=draft.replacement;start.value=draft.start;end.value=draft.end;uncertain.value=draft.uncertain}
      else if(!props.hint){first.value=draft.first;last.value=draft.last;replacement.value=draft.replacement;start.value=draft.start;end.value=draft.end}
    }
    await refresh()
  }catch(e){if(alive&&turn===sequence)error.value=e instanceof Error?e.message:'原曲信息暂时无法读取，请重试。'}
  finally{if(turn===sequence)loading.value=false}
}
async function submit(retry=false){
  if(sending.value||(!retry&&!canSubmit.value))return
  const work=props.work.id
  const input=retry?uncertain.value:{sourceAssetId:props.asset.id,workVersion:props.work.version,requestKey:crypto.randomUUID(),start:start.value,end:end.value,textStart:slices.value[first.value]!.start,textEnd:slices.value[last.value]!.end,replacementLyrics:replacement.value.trim()}
  if(!input)return
  sending.value=true;error.value='';uncertain.value=input;remember();stopPreview()
  try{
    await submitSectionEdit(work,input);uncertain.value=undefined;notice.value='修改版正在制作，原版仍可正常试听。';emit('refresh');await refresh()
    emit('close');await nextTick();document.getElementById('section-edit-history')?.scrollIntoView({block:'center',behavior:'smooth'})
  }catch(e){
    error.value=e instanceof Error?e.message:'提交结果暂未确认，请先核对修改进度。'
    // 网络、超时和5xx都可能发生在事务受理后，必须保留原请求标识供核对。
    if(e instanceof ApiError&&e.status>=400&&e.status<500&&e.status!==408)uncertain.value=undefined
    if(props.tasks.some(task=>task.requestKey===input.requestKey)){uncertain.value=undefined;error.value='';notice.value='修改请求已受理，可以在下方查看进度。';emit('close')}
    emit('refresh');await refresh()
  }finally{sending.value=false;remember()}
}
async function adopt(item:SectionEdit){
  if(!item.resultAssetId||item.status!=='SUCCEEDED'||adopting.value)return
  adopting.value=true;error.value='';stopPreview()
  try{await selectAudio(props.work.id,item.resultAssetId,selectionVersion.value);notice.value='已使用修改版，原版仍保留。';await refresh();emit('adopted')}
  catch(e){error.value=e instanceof Error?e.message:'暂时无法选择这个版本，请刷新后重试。';await refresh()}
  finally{adopting.value=false}
}
async function cancel(item:SectionEdit){try{await cancelTask(props.work.id,item.taskId);emit('refresh');await refresh()}catch(e){error.value=e instanceof Error?e.message:'歌曲已开始提交，暂时无法撤销。';emit('refresh');await refresh()}}
async function compare(assetId:string){await refresh();compared.value=items.value.find(e=>e.resultAssetId===assetId);await nextTick();document.getElementById('section-edit-history')?.scrollIntoView({block:'center',behavior:'smooth'})}
function close(){remember();stopPreview();emit('close')}
watch(()=>props.open,value=>{if(value)void openEditor();else{sequence++;loading.value=false;dialog.value?.close();stopPreview()}},{immediate:true})
watch(()=>props.tasks.map(t=>`${t.id}:${t.status}:${t.assetId}`).join('|'),()=>{void refresh()})
onMounted(()=>{void refresh()})
onBeforeUnmount(()=>{alive=false;sequence++;refreshSequence++;dialog.value?.close();stopPreview()})
defineExpose({compare})
</script>

<template>
  <section v-if="items.length||notice||error" id="section-edit-history" class="section-edit-history" :aria-label="t('局部修改记录')">
    <header><div><h3>{{ t('听听这一处改变') }}</h3><p>{{ t('先对比试听，满意后再使用修改版。') }}</p></div><button class="text-button" @click="emit('refresh');refresh()">{{ t('刷新修改进度') }}</button></header>
    <p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p><p v-if="error&&!open" class="notice error" role="alert">{{ t(error) }}</p>
    <article v-for="item in items" :key="item.taskId" class="section-version" :class="{compared:compared?.taskId===item.taskId}">
      <div class="section-version-top"><strong>{{ clock(item.start) }} — {{ clock(item.end) }}</strong><span>{{ t(item.status==='SUCCEEDED'?'修改版已准备好':item.status==='CANCELLED'?'已撤销':item.status==='FAILED'?'这次修改未完成':item.status==='SUBMISSION_UNKNOWN'?'提交结果待核对':'修改版制作中') }}</span></div>
      <p class="section-version-lyrics">{{ item.replacementLyrics }}</p><small>{{ formatDate(item.createdAt) }}</small>
      <div class="section-compare-actions"><button class="button secondary" @click="preview(item.sourceAssetId,item.start-2,item.end+2)"><PlaybackIcon/>{{ t('听原版') }}</button><button v-if="item.resultAssetId&&item.status==='SUCCEEDED'" class="button secondary" @click="preview(item.resultAssetId,item.start-2,item.end+2,true)"><PlaybackIcon/>{{ t('听修改版') }}</button><button v-if="item.resultAssetId&&item.status==='SUCCEEDED'" class="button" :disabled="adopting||selectionId===item.resultAssetId" @click="adopt(item)">{{ selectionId===item.resultAssetId?t('当前使用版本'):t('使用这个版本') }}</button><button v-if="item.status==='QUEUED'" class="text-button" @click="cancel(item)">{{ t('撤销这次修改') }}</button></div>
      <p v-if="item.status==='FAILED'||item.status==='CANCELLED'" class="save-info">{{ t('原版和历史版本仍可正常试听。') }}</p>
    </article>
    <button v-if="player.segment&&previewing" class="text-button" @click="stopPreview">{{ t('停止片段试听') }}</button>
  </section>
  <dialog v-if="open" ref="dialog" class="section-editor-dialog" :aria-label="t('局部改词')" @cancel.prevent="close">
    <header class="section-editor-header"><div><span>{{ title }}</span><h2>{{ t('让这一句，更贴近心意。') }}</h2></div><button class="section-editor-close" :aria-label="t('关闭局部改词')" @click="close">×</button></header>
    <div class="section-editor-body">
      <p v-if="loading" class="notice" role="status">{{ t('正在读取原曲信息…') }}</p><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p>
      <div v-if="context&&!context.available" class="section-unavailable"><p>{{ t(context.reason||'这首歌曲缺少可编辑的原曲信息。') }}</p><button class="button secondary" @click="openEditor">{{ t('重新读取原曲') }}</button></div>
      <template v-if="context?.available">
        <p class="section-editor-intro">{{ t('只重新演唱选中的片段，尽量延续原曲。片段内旋律和唱腔可能变化。') }}</p>
        <p class="section-mobile-range">{{ t('重新演唱范围') }} {{ clock(start) }} — {{ clock(end) }}</p>
        <div class="section-editor-grid"><div class="section-editor-words">
          <label class="field">{{ t('从哪句开始修改') }}<select :value="first" :disabled="locked" @change="pick(Number(($event.target as HTMLSelectElement).value),Number(($event.target as HTMLSelectElement).value))"><option value="-1" disabled>{{ t('请选择要修改的歌词') }}</option><option v-for="(line,index) in slices" :key="line.start" :value="index">{{ line.text }}</option></select></label>
          <label v-if="first>=0" class="field">{{ t('到哪句结束') }}<select :value="last" :disabled="locked" @change="pick(first,Number(($event.target as HTMLSelectElement).value))"><option v-for="(line,index) in slices" v-show="index>=first" :key="line.start" :value="index" :disabled="index<first">{{ line.text }}</option></select></label>
          <div v-if="original" class="section-original"><span>{{ t('原歌词') }}</span><pre>{{ original }}</pre></div>
          <p v-if="hint?.replacement&&first<0" class="notice" role="status">{{ t('请在原歌词中选中这一句，再填入你的修改。') }} <strong>{{ hint.replacement }}</strong></p>
          <label class="field">{{ t('想改成什么') }}<textarea v-model="replacement" :disabled="locked||first<0" maxlength="5000" rows="5" :placeholder="t('尽量保持原来的字数和断句，更容易贴合原曲。')"></textarea></label>
          <p class="save-info">{{ t('未选中的歌词会保留，原曲也会保留。') }}</p>
          <p v-if="full.length>5000" class="section-range-error">{{ t('修改后的完整歌词不能超过5000字，请缩短后重试。') }}</p>
        </div><aside class="section-editor-range">
          <h3>{{ t('确认重新演唱的范围') }}</h3><div class="section-range-label"><strong>{{ clock(start) }}</strong><span>—</span><strong>{{ clock(end) }}</strong></div>
          <div class="section-range-rail" aria-hidden="true"><span :style="{left:`${start/context.duration!*100}%`,width:`${Math.max(0,end-start)/context.duration!*100}%`}"></span></div>
          <div class="section-time-inputs"><label>{{ t('开始秒数') }}<input v-model.number="start" type="number" min="0" :max="context.duration!" step="0.01" :disabled="locked"></label><label>{{ t('结束秒数') }}<input v-model.number="end" type="number" min="0" :max="context.duration!" step="0.01" :disabled="locked"></label></div>
          <p class="save-info">{{ t('片段至少10秒，不能超过原曲时长的一半。') }}</p><p v-if="hint?.start===undefined" class="save-info">{{ t('没有同步时间轴，请先试听确认起止位置。') }}</p>
          <p class="save-info">{{ t('请试听确认时间范围覆盖上方全部歌词，保留不需要改动的句子。') }}</p>
          <p v-if="!validWindow(start,end,context.duration!)" class="section-range-error" role="status">{{ t('请调整时间范围：至少10秒，且不超过半首歌。') }}</p>
          <label class="section-loop"><input type="checkbox" v-model="loop">{{ t('循环试听片段') }}</label>
          <button class="button secondary" :disabled="!validWindow(start,end,context.duration!)" @click="preview(asset.id,start,end)"><PlaybackIcon/>{{ t('试听选中片段') }}</button><button v-if="previewing" class="text-button" @click="stopPreview">{{ t('停止片段试听') }}</button>
          <p v-if="player.error&&previewing" class="notice error">{{ t(player.error) }}</p>
        </aside></div>
        <p v-if="active" class="notice" role="status">{{ t('这首歌已有修改任务，请先查看下方进度。') }}</p>
        <div v-if="uncertain" class="notice" role="status"><p>{{ t('提交结果暂未确认，请先核对修改进度。') }}</p><div class="actions"><button class="button secondary" :disabled="sending" @click="emit('refresh');refresh()">{{ t('核对修改进度') }}</button><button class="text-button" :disabled="sending" @click="submit(true)">{{ t('使用原请求重试') }}</button></div></div>
      </template>
    </div>
    <footer class="section-editor-footer"><p>{{ t('生成修改版会调用音乐服务，完成后由你决定是否使用。') }}</p><div><button class="button secondary" @click="close">{{ t('先保留原版') }}</button><button class="button" :disabled="!canSubmit" @click="submit()">{{ sending?t('正在提交修改…'):t('生成修改版') }}</button></div></footer>
  </dialog>
</template>
