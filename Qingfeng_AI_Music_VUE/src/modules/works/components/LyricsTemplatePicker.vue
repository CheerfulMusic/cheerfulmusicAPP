<script setup lang="ts">
import { computed, ref, useId, watch, onBeforeUnmount, nextTick } from 'vue'
import { locale } from '../../../shared/i18n'
import { lyricTemplates, lyricSections, normalizeLyricsStructure, type LyricsStructure } from '../model/lyrics-structure'
const model=defineModel<LyricsStructure>({required:true})
const props=defineProps<{disabled?:boolean}>()
const en=computed(()=>locale.value==='en'), id=useId()
const trigger=ref<HTMLButtonElement>(), dialog=ref<HTMLDialogElement>()
const draft=ref<LyricsStructure>(normalizeLyricsStructure()), opened=ref(false)
const selectedLabel=computed(()=>model.value.templateId==='AUTO'?(en.value?'Flexible':'自由结构'):model.value.templateId)
const sectionNames:Record<string,string>={'Verse':'主歌','Verse 1':'主歌一','Verse 2':'主歌二','Pre-Chorus':'预副歌','Chorus':'副歌','Chorus 2':'第二副歌','Bridge':'桥段','Interlude':'间奏'}
const name=(section:string)=>en.value?section:sectionNames[section]||section
const sections=computed(()=>lyricSections(draft.value).map(name).join(' → '))
const captions:Record<string,[string,string]>={AAB:['双主歌铺陈','Two verses before the hook'],ABB:['双副歌递进','Two developing choruses'],ABC:['预副歌铺垫','Build into the chorus'],ABCD:['桥段带来转折','Add a contrasting bridge']}
async function open(){
 if(props.disabled)return
 draft.value=normalizeLyricsStructure(model.value)
 await nextTick()
 if(!dialog.value?.open){dialog.value?.showModal();opened.value=true}
}
function close(){dialog.value?.close();opened.value=false;trigger.value?.focus({preventScroll:true})}
function confirm(){if(props.disabled)return;model.value={...draft.value};close()}
function choose(templateId:LyricsStructure['templateId']){draft.value={templateId,chorusFirst:templateId==='AUTO'?false:draft.value.chorusFirst}}
watch(()=>props.disabled,value=>{if(value&&opened.value)close()})
onBeforeUnmount(()=>dialog.value?.close())
</script>
<template>
  <button ref="trigger" class="lyrics-template-trigger" type="button" :disabled="disabled" aria-haspopup="dialog" :aria-expanded="opened" :aria-controls="id+'-dialog'" @click="open">
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v14H5zM8 9h8M8 12h5M8 15h8"/></svg>
    <span>{{ en?'Lyric template':'歌词模板' }} · <strong>{{ selectedLabel }}</strong><span v-if="model.chorusFirst"> · {{ en?'Chorus first':'副歌先行' }}</span></span>
  </button>
  <Teleport to="body">
    <dialog ref="dialog" :id="id+'-dialog'" class="lyrics-template-dialog" :aria-labelledby="id+'-title'" :aria-describedby="id+'-description'" @cancel.prevent="close" @close="opened=false">
      <header class="template-dialog-header">
        <h2 :id="id+'-title'">{{ en?'Choose a lyric template':'选择歌词模板' }}</h2>
        <p :id="id+'-description'">{{ en?'Choose how your song unfolds Your words and melody stay original':'选择歌词的展开方式 不固定歌词内容或旋律' }}</p>
        <button class="template-dialog-close" type="button" :aria-label="en?'Cancel and close':'取消并关闭'" @click="close">×</button>
      </header>
      <div class="template-dialog-body">
        <fieldset class="template-options" :disabled="disabled">
          <legend class="template-sr-only">{{ en?'Song structure':'歌曲结构' }}</legend>
          <label class="template-option template-auto" :class="{selected:draft.templateId==='AUTO'}">
            <input type="radio" :name="id+'-template'" value="AUTO" :checked="draft.templateId==='AUTO'" @change="choose('AUTO')">
            <span><strong>{{ en?'Flexible':'自由结构' }}</strong><small>{{ en?'Let AI arrange the sections around your story':'由 AI 根据故事与风格安排段落' }}</small></span>
          </label>
          <label v-for="(sequence,key) in lyricTemplates" :key="key" class="template-option" :class="{selected:draft.templateId===key}">
            <input type="radio" :name="id+'-template'" :value="key" :checked="draft.templateId===key" @change="choose(key)">
            <span><strong>{{ key }} <span>{{ captions[key]![en?1:0] }}</span></strong><small>{{ sequence.map(name).join(' → ') }}</small></span>
          </label>
        </fieldset>
        <div class="template-chorus-row"><div><strong>{{ en?'Chorus first':'副歌先行' }}</strong><p>{{ en?'Move the first chorus to the start':'把第一个副歌移到开头' }}</p></div><button class="chorus-first" type="button" :aria-label="en?'Chorus first':'副歌先行'" :aria-pressed="draft.chorusFirst" :disabled="disabled||draft.templateId==='AUTO'" @click="draft={...draft,chorusFirst:!draft.chorusFirst}"><span></span></button></div>
        <p v-if="draft.templateId==='AUTO'" class="template-help">{{ en?'Choose a template to enable chorus first':'选择一种模板后可开启副歌先行' }}</p>
        <div v-else class="template-preview-box"><strong>{{ en?'Selected section order':'当前段落顺序' }}</strong><p class="template-preview" aria-live="polite">{{ sections }}</p><small>{{ en?'Interlude is instrumental Chorus 2 is a variation of the chorus':'间奏不填写歌词 第二副歌保留记忆点并有所变化' }}</small></div>
        <p class="template-help">{{ en?'Applies on your next generation only Existing lyrics stay unchanged':'确认后仅用于下次生成 不会直接改动已有歌词' }}</p>
      </div>
      <footer class="template-dialog-actions"><button class="template-cancel" type="button" @click="close">{{ en?'Cancel':'取消' }}</button><button class="template-confirm" type="button" :disabled="disabled" @click="confirm">{{ en?'Confirm selection':'确认选择' }}</button></footer>
    </dialog>
  </Teleport>
</template>
<style scoped>
.lyrics-template-trigger{display:inline-flex;align-items:center;justify-content:center;gap:6px;max-width:100%;min-height:38px;padding:8px 13px;border:1px solid #efcfc4;border-radius:24px;background:#fff8f3;color:#a8422d;font:inherit;font-size:12px;line-height:1.5;cursor:pointer;text-align:left}
.lyrics-template-trigger svg{width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:1.5;flex:none}.lyrics-template-trigger strong{font-weight:600}
.lyrics-template-trigger:hover:not(:disabled){background:#ffede5;border-color:#ef8067}button:disabled{opacity:.5;cursor:not-allowed}button:focus-visible{outline:2px solid #f93822;outline-offset:3px}
.lyrics-template-dialog{width:min(620px,calc(100% - 32px));max-width:none;max-height:calc(100dvh - 48px);padding:0;border:1px solid #f0d9d0;border-radius:24px;background:#fffaf8;color:#332824;box-shadow:0 24px 90px #48292a30;overflow:hidden}
.lyrics-template-dialog[open]{display:flex;flex-direction:column}.lyrics-template-dialog::backdrop{background:#39272e66;backdrop-filter:blur(6px)}
.template-dialog-header{position:relative;padding:26px 56px 18px 26px;flex:none}.template-dialog-header h2{margin:0 0 8px;font-size:24px;font-weight:650;line-height:1.4;color:#332824}.template-dialog-header p{margin:0;color:#8a7166;font-size:13px;line-height:1.8}
.template-dialog-close{position:absolute;right:15px;top:15px;width:40px;height:40px;border:0;border-radius:50%;background:transparent;color:#826e65;font-size:28px;cursor:pointer}
.template-dialog-body{padding:0 26px 18px;overflow:auto;overscroll-behavior:contain;min-height:0}.template-options{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:0;margin:0;border:0;min-width:0}
.template-option{display:flex;align-items:flex-start;gap:9px;min-width:0;padding:14px 12px;border:1px solid #ead9d0;border-radius:14px;background:#fff;cursor:pointer}.template-option.selected{border-color:#f93822;background:#fff0e9}.template-option:focus-within{outline:2px solid #f93822;outline-offset:2px}.template-option input{margin:5px 0 0;accent-color:#f93822;flex:none}.template-option>span{min-width:0}.template-option strong{font-size:14px;line-height:1.7;font-weight:650}.template-option strong span{font-size:12px;font-weight:400;margin-left:6px}.template-option small{display:block;font-size:11px;line-height:1.85;color:#886f64;margin-top:5px;overflow-wrap:anywhere}.template-auto{grid-column:1/-1}
.template-chorus-row{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:20px}.template-chorus-row strong{font-size:14px;font-weight:600}.template-chorus-row p{font-size:12px;color:#8a7166;margin:4px 0;line-height:1.6}.chorus-first{flex:none;position:relative;width:46px;height:27px;border:0;border-radius:20px;background:#d6c9c2;cursor:pointer;padding:3px}.chorus-first span{display:block;width:21px;height:21px;background:#fff;border-radius:50%;box-shadow:0 1px 4px #6d4b3526;transform:translateX(0)}.chorus-first[aria-pressed=true]{background:#f93822}.chorus-first[aria-pressed=true] span{transform:translateX(19px)}
.template-preview-box{padding:12px 14px;margin-top:14px;border-radius:12px;background:#ffede4}.template-preview-box>strong{font-size:12px;font-weight:600;color:#a34630}.template-preview{font-size:13px;line-height:1.85;color:#6c4f40;margin:6px 0;overflow-wrap:anywhere}.template-preview-box small,.template-help{font-size:11px;line-height:1.8;color:#8a7166}.template-help{margin:12px 0 0}
.template-dialog-actions{display:flex;gap:12px;padding:16px 26px;border-top:1px solid #efdfd6;background:#fffaf8;flex:none}.template-dialog-actions button{min-height:44px;border-radius:26px;font:inherit;font-size:14px;font-weight:600;cursor:pointer}.template-cancel{flex:1;border:1px solid #e6cfc4;background:transparent;color:#81675b}.template-confirm{flex:2;border:0;background:#f93822;color:#fff}.template-sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%)}
@media(max-width:600px){.lyrics-template-dialog{width:100%;max-height:88dvh;margin:0;inset:auto 0 0;border-radius:24px 24px 0 0;border-bottom:0}.template-dialog-header{padding:24px 52px 16px 20px}.template-dialog-header h2{font-size:22px}.template-dialog-body{padding:0 20px 16px}.template-options{grid-template-columns:1fr}.template-option{padding:11px 12px}.template-option small{margin-top:3px}.template-dialog-actions{padding:14px 20px calc(14px + env(safe-area-inset-bottom))}.lyrics-template-trigger{font-size:11px;padding:7px 10px}}
</style>
