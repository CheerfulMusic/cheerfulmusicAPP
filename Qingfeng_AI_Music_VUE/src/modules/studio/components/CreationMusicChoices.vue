<script setup lang="ts">
import {computed,ref,useId,watch} from 'vue'
import {locale,t} from '../../../shared/i18n'
import type {MusicCatalog,MusicPreferences,MusicOption} from '../../works/api/music-settings'
import {toggleMusicOption} from '../../works/model/music-preferences'
const props=defineProps<{modelValue:MusicPreferences;catalog:MusicCatalog;disabled:boolean}>(),emit=defineEmits<{'update:modelValue':[value:MusicPreferences]}>()
const moreGenres=ref(false)
const dialogId=useId()
const dialog=ref<HTMLDialogElement>(), trigger=ref<HTMLButtonElement>()
const advancedDraft=ref<MusicPreferences>()
const label=(value:{zh:string;en:string})=>locale.value==='en'?value.en:value.zh
const rows=computed(()=>['genre','emotion','tempo'].map(id=>props.catalog.groups.find(g=>g.id===id)).filter(g=>!!g))
const extras=computed(()=>props.catalog.groups.filter(g=>!['genre','emotion','tempo'].includes(g.id)))
const names:Record<string,string>={genre:'曲风',emotion:'情绪',tempo:'节奏'}
function options(id:string,values:MusicOption[]){return id==='genre'&&!moreGenres.value?values.filter(v=>['pop','folk','rap','chinese'].includes(v.id)||props.modelValue.choices.genre?.includes(v.id)):values}
function choose(group:string,id:string){const value=toggleMusicOption(props.modelValue,props.catalog,group,id);if(value)emit('update:modelValue',value)}
function auto(group:string){emit('update:modelValue',{...props.modelValue,recipeId:null,choices:{...props.modelValue.choices,[group]:[]}})}
function field(key:'vocalGender'|'freeText'|'negativeText',value:string){emit('update:modelValue',{...props.modelValue,recipeId:null,[key]:value} as MusicPreferences)}

function openAdvanced(){
 if(props.disabled||!dialog.value||dialog.value.open)return
 advancedDraft.value=props.modelValue
 dialog.value.showModal()
}
function closeAdvanced(){
 if(!dialog.value?.open)return
 dialog.value.close()
 advancedDraft.value=undefined
 trigger.value?.focus({preventScroll:true})
}
function toggleAdvanced(group:string,id:string){
 if(!advancedDraft.value)return
 const next=toggleMusicOption(advancedDraft.value,props.catalog,group,id)
 if(next)advancedDraft.value=next
}
function changeAdvancedText(key:'freeText'|'negativeText',event:Event){
 if(advancedDraft.value)advancedDraft.value={...advancedDraft.value,recipeId:null,[key]:(event.target as HTMLTextAreaElement).value}
}
function applyAdvanced(){
 if(props.disabled||!advancedDraft.value)return
 const {choices,freeText,negativeText,recipeId}=advancedDraft.value
 if(advancedDraft.value!==props.modelValue)emit('update:modelValue',{...props.modelValue,choices,freeText,negativeText,recipeId})
 closeAdvanced()
}
watch(()=>props.disabled,disabled=>{if(disabled)closeAdvanced()})
</script>
<template><section class="creation-music-choices" :aria-label="t('选定音乐方向')"><div class="creation-direction-heading"><h2><span aria-hidden="true">♫</span> {{ t('选定音乐方向') }}</h2><span>{{ t('音乐方向会影响歌词的表达和节奏') }}</span></div><fieldset :disabled="disabled"><div v-for="group in rows" :key="group.id" class="creation-choice-row"><strong>{{ t(names[group.id]||group.zh) }}</strong><div class="creation-chips"><button type="button" :aria-pressed="!(modelValue.choices[group.id]?.length)" @click="auto(group.id)">{{ t('AI推荐') }}</button><button v-for="option in options(group.id,group.options)" :key="option.id" type="button" :aria-pressed="modelValue.choices[group.id]?.includes(option.id)||false" @click="choose(group.id,option.id)">{{ label(option) }}<span v-if="modelValue.choices[group.id]?.includes(option.id)" aria-hidden="true"> ✓</span></button><button v-if="group.id==='genre'" type="button" :aria-expanded="moreGenres" @click="moreGenres=!moreGenres">{{ t(moreGenres?'收起':'更多') }} <span aria-hidden="true">⌄</span></button></div></div><div class="creation-voice-row"><label>{{ t('演唱声音') }}<select :value="modelValue.vocalGender" @change="field('vocalGender',($event.target as HTMLSelectElement).value)"><option value="AUTO">{{ t('自动') }}</option><option value="MALE">{{ t('男声') }}</option><option value="FEMALE">{{ t('女声') }}</option></select></label><slot name="structure"/></div><button ref="trigger" type="button" class="creation-more-toggle" aria-haspopup="dialog" :aria-controls="dialogId" @click="openAdvanced">
    <strong>+ {{ t('更多音乐设置') }}</strong><span>{{ t('乐器 唱法 编排与排除项') }}</span>
  </button></fieldset></section>
  <Teleport to="body">
    <dialog ref="dialog" :id="dialogId" class="advanced-music-dialog" :aria-labelledby="dialogId+'-title'" :aria-describedby="dialogId+'-description'" @cancel.prevent="closeAdvanced">
      <header class="advanced-header">
        <h2 :id="dialogId+'-title'">{{ t('更多音乐设置') }}</h2>
        <p :id="dialogId+'-description'">{{ t('乐器 唱法 编排与排除项') }}</p>
        <button class="advanced-close" type="button" :aria-label="t('关闭')" autofocus @click="closeAdvanced">×</button>
      </header>
      <div v-if="advancedDraft" class="advanced-body">
        <div v-for="group in extras" :key="group.id" class="creation-choice-row">
          <strong>{{ label(group) }}</strong>
          <div class="creation-chips" role="group" :aria-label="label(group)">
            <button v-for="option in group.options" :key="option.id" type="button" :aria-pressed="advancedDraft.choices[group.id]?.includes(option.id)||false" @click="toggleAdvanced(group.id,option.id)">{{ label(option) }}</button>
          </div>
        </div>
        <div class="advanced-notes">
          <label>{{ t('补充音乐要求') }}<textarea :value="advancedDraft.freeText" maxlength="300" @input="changeAdvancedText('freeText',$event)"/></label>
          <label>{{ t('需要避开的内容') }}<textarea :value="advancedDraft.negativeText" maxlength="200" @input="changeAdvancedText('negativeText',$event)"/></label>
        </div>
      </div>
      <footer class="advanced-actions">
        <button class="advanced-cancel" type="button" @click="closeAdvanced">{{ t('取消') }}</button>
        <button class="advanced-apply" type="button" :disabled="disabled" @click="applyAdvanced">{{ t('应用设置') }}</button>
      </footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.advanced-music-dialog {
  width: min(680px, calc(100% - 32px));
  max-height: calc(100dvh - 40px);
  padding: 0;
  border: 1px solid #f0d9d0;
  border-radius: 24px;
  background: #fffaf8;
  color: #332824;
  box-shadow: 0 24px 90px #48292a30;
  overflow: hidden;
}
.advanced-music-dialog[open] { display: flex; flex-direction: column; }
.advanced-music-dialog::backdrop { background: #39272e66; backdrop-filter: blur(6px); }
.advanced-header { flex: none; position: relative; padding: 24px 64px 16px 26px; }
.advanced-header h2 { margin: 0; font-size: 24px; line-height: 1.4; }
.advanced-header p { margin: 6px 0 0; color: #806967; font-size: 13px; }
.advanced-close {
  position: absolute; top: 15px; right: 16px; width: 40px; height: 40px;
  border: 0; border-radius: 50%; background: transparent; color: #806967;
  font: inherit; font-size: 26px; cursor: pointer;
}
.advanced-close:hover { background: #ffede5; }
.advanced-music-dialog :is(button, textarea):focus-visible { outline: 2px solid #a82e31; outline-offset: 2px; }
.advanced-body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 4px 26px 16px; }
.advanced-body .creation-choice-row > strong { flex-basis: 82px; overflow-wrap: anywhere; }
.advanced-notes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 18px; }
.advanced-notes label { display: grid; gap: 7px; font-size: 13px; font-weight: 600; }
.advanced-notes textarea {
  box-sizing: border-box; width: 100%; min-height: 72px; padding: 9px 11px;
  border: 1px solid #e9dce2; border-radius: 12px; background: #fff; color: #332824;
  font: inherit; font-size: 14px; font-weight: 400; line-height: 1.5; resize: vertical;
}
.advanced-actions {
  display: flex; flex: none; justify-content: flex-end; gap: 10px;
  padding: 14px 26px; border-top: 1px solid #efdfd6;
}
.advanced-actions button { min-height: 42px; padding: 8px 22px; border: 1px solid transparent; border-radius: 999px; font: inherit; font-size: 14px; font-weight: 650; cursor: pointer; }
.advanced-actions .advanced-cancel { border-color: #e9d3cd; background: transparent; color: #745652; }
.advanced-actions .advanced-apply { background: #df3528; color: #fff; }
.advanced-cancel:hover { background: #fff0eb; }
.advanced-apply:hover:not(:disabled) { background: #c92e23; }
.advanced-apply:disabled { opacity: .55; cursor: not-allowed; }
@media (max-width: 600px) {
  .advanced-music-dialog { inset: auto 0 0; width: 100%; max-height: 88dvh; max-width: none; margin: 0; border-radius: 24px 24px 0 0; }
  .advanced-header { padding-left: 20px; }
  .advanced-header h2 { font-size: 21px; }
  .advanced-body { padding-inline: 20px; }
  .advanced-body .creation-choice-row { flex-direction: column; }
  .advanced-body .creation-choice-row > strong { flex-basis: auto; padding-top: 0; }
  .advanced-notes { grid-template-columns: 1fr; }
  .advanced-actions { padding: 12px 20px calc(12px + env(safe-area-inset-bottom)); }
  .advanced-actions button { flex: 1; }
}
</style>
