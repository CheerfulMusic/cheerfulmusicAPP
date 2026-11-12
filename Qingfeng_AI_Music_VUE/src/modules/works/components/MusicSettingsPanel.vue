<script setup lang="ts">
import {ref,computed,onMounted,onBeforeUnmount,watch,nextTick} from 'vue'
import {t,locale} from '../../../shared/i18n'
import {currentCreatorId} from '../../../shared/http/api-client'
import type {Work} from '../../studio'
import {loadMusicCatalog,loadMusicSettings,loadTaskMusicSettings,previewWorkMusicSettings,musicBriefAvailable,suggestMusicBrief,type MusicPreferences,type MusicCatalog,type MusicDirection} from '../api/music-settings'
import type {GenerationTask,Revision} from '../api/detail'
import {useCredits} from '../../../shared/credits/useCredits'
import {legacyPreferences,applyRecipe,toggleMusicOption,validMusicPreferences} from '../model/music-preferences'
const props=defineProps<{work:Work;busy:boolean;initial?:boolean;remake?:{task:GenerationTask;revision:Revision};submitError?:string}>()
const emit=defineEmits<{apply:[value:{expectedVersion:number;preferences:MusicPreferences;lyrics?:string}];close:[]}>()
const dialog=ref<HTMLDialogElement>(),catalog=ref<MusicCatalog>(),prefs=ref<MusicPreferences>(legacyPreferences(props.work)),direction=ref<MusicDirection>(),loading=ref(true),previewing=ref(false),error=ref(''),hint=ref(''),advanced=ref(false),detail=ref(false),baseVersion=ref(props.work.version),oldDraft=ref<MusicPreferences>()
let importedLegacy=false,alive=true,previewTurn=0,timer:ReturnType<typeof setTimeout>|undefined,initialized=false
const aiAvailable=ref(false),simplifying=ref(false),suggestion=ref<{original:string;text:string}>()
const remakeLyrics=ref(props.remake?.revision.lyrics||'')
const {credits,refreshCredits}=useCredits()
onMounted(()=>{void musicBriefAvailable().then(value=>{if(alive)aiAvailable.value=value}).catch(()=>{})})
async function simplify(){
 if(simplifying.value||props.busy||!prefs.value.freeText.trim())return
 const original=prefs.value.freeText;simplifying.value=true;suggestion.value=undefined;hint.value=''
 try{const value=await suggestMusicBrief(original);if(alive&&prefs.value.freeText===original)suggestion.value=value}
 catch{if(alive)hint.value='AI整理暂不可用，原要求仍然保留，你可以继续调整或直接生成歌曲。'}
 finally{if(alive)simplifying.value=false}
}
function useSuggestion(){
 if(!suggestion.value||props.busy)return
 if(prefs.value.freeText!==suggestion.value.original){hint.value='要求已发生变化，请重新整理后确认。';suggestion.value=undefined;return}
 prefs.value.freeText=suggestion.value.text;suggestion.value=undefined;hint.value='已使用精简建议，请确认后保存音乐设置。'
}
const label=(value:{zh:string;en:string})=>locale.value==='en'?value.en:value.zh
const storageKey=()=>`qingfeng.music-preferences:${currentCreatorId()}:${props.work.id}`
const visibleGroups=computed(()=>catalog.value?.groups.filter(g=>!['exclude','arrangement','groove'].includes(g.id))||[])
const extras=computed(()=>catalog.value?.groups.filter(g=>['arrangement','groove'].includes(g.id))||[])
const excludes=computed(()=>catalog.value?.groups.find(g=>g.id==='exclude'))
const chips=computed(()=>catalog.value?.groups.flatMap(g=>(prefs.value.choices[g.id]||[]).map(id=>({group:g.id,option:g.options.find(o=>o.id===id)!}))).filter(c=>c.option)||[])
const summary=computed(()=>locale.value==='en'?direction.value?.summaryEn:direction.value?.summaryZh)
function remember(){if(!initialized||props.remake)return;try{localStorage.setItem(storageKey(),JSON.stringify({workVersion:baseVersion.value,preferences:prefs.value}))}catch{/* 当前页面仍保留编辑。 */}}
async function preview(){const turn=++previewTurn;previewing.value=true;direction.value=undefined;error.value='';try{const value=await previewWorkMusicSettings(props.work.id,prefs.value);if(alive&&turn===previewTurn){direction.value=value;prefs.value.recipeId=value.preferences.recipeId}}catch(e){if(alive&&turn===previewTurn)error.value=e instanceof Error?e.message:'音乐设置暂时无法读取，请重试。'}finally{if(alive&&turn===previewTurn)previewing.value=false}}
function schedule(){if(!initialized)return;remember();clearTimeout(timer);previewTurn++;direction.value=undefined;previewing.value=true;timer=setTimeout(()=>{void preview()},350)}
// 只监听用户偏好；预览返回的recipeId规范化不会导致重新请求。
watch(()=>JSON.stringify({choices:prefs.value.choices,voice:prefs.value.vocalGender,free:prefs.value.freeText,negative:prefs.value.negativeText}),schedule)
function choose(group:string,id:string){if(!catalog.value)return;const next=toggleMusicOption(prefs.value,catalog.value,group,id);if(!next){hint.value='这个类别已选满，请先取消一个标签。';return}hint.value='';prefs.value=next}
function recipe(id:string){const value=catalog.value?.recipes.find(r=>r.id===id);if(value){const current=importedLegacy&&prefs.value.freeText===props.work.style?{...prefs.value,freeText:''}:prefs.value;importedLegacy=false;prefs.value=applyRecipe(current,value);hint.value='配方已载入，补充要求和排除项已保留。'}}
function close(){if(props.busy)return;remember();emit('close')}
function save(){
 if(!direction.value||previewing.value||loading.value||props.busy||props.remake&&!remakeLyrics.value.trim())return
 remember()
 emit('apply',{expectedVersion:baseVersion.value,preferences:JSON.parse(JSON.stringify(direction.value.preferences)),lyrics:props.remake?remakeLyrics.value:undefined})
}
function recover(){if(oldDraft.value){prefs.value=oldDraft.value;oldDraft.value=undefined;hint.value='已恢复本机设置，请确认后保存。';void preview()}}
watch(()=>props.work.version,version=>{if(props.remake)baseVersion.value=version})
onMounted(async()=>{
 await nextTick();dialog.value?.showModal()
 const remake=props.remake
 if(remake)void refreshCredits().catch(()=>{})
 try{
  const [c,saved,original]=await Promise.all([
   loadMusicCatalog(),loadMusicSettings(props.work.id),
   remake?loadTaskMusicSettings(props.work.id,remake.task.id):Promise.resolve(null),
  ])
  if(!alive)return
  catalog.value=c;baseVersion.value=saved.work.version
  if(remake){
   const historical=original?.preferences
   // 旧版生成也可能有快照，但其中的 choices 是空对象，只记录了原曲风和人声。
   if(historical&&validMusicPreferences(historical,c)&&Object.keys(historical.choices).length){
    prefs.value=historical
   }else{
    const task=remake.task
    const voice=task.vocalGender==='MALE'||task.vocalGender==='FEMALE'?task.vocalGender:'AUTO'
    const legacy=legacyPreferences({...saved.work,style:task.style||'自动推荐',vocalGender:voice})
    prefs.value=validMusicPreferences(legacy,c)?legacy:legacyPreferences({...saved.work,style:'自动推荐',vocalGender:voice})
    hint.value='原版设置记录不完整，请确认新版的音乐偏好。'
   }
   advanced.value=extras.value.some(group=>!!prefs.value.choices[group.id]?.length)
  }else{
   importedLegacy=!saved.direction
   prefs.value=saved.direction?.preferences||legacyPreferences(saved.work)
   let local
   try{local=JSON.parse(localStorage.getItem(storageKey())||'null')}catch{}
   if(validMusicPreferences(local?.preferences,c)){
    if(local.workVersion===baseVersion.value)prefs.value=local.preferences
    else oldDraft.value=local.preferences
   }
  }
  initialized=true;await preview()
 }catch(e){if(alive)error.value=e instanceof Error?e.message:'音乐设置暂时无法读取，请重试。'}
 finally{if(alive)loading.value=false}
})
function markSaved(){try{localStorage.removeItem(storageKey())}catch{}initialized=false}
defineExpose({markSaved})
onBeforeUnmount(()=>{remember();alive=false;previewTurn++;clearTimeout(timer);dialog.value?.close()})
</script>
<template>
<dialog ref="dialog" id="music-settings" class="music-settings-dialog" aria-labelledby="music-settings-title" @cancel.prevent="close">
 <header class="music-settings-header"><div><span>{{ remake?remake.revision.title:initial?t('为这段歌词，选一种声音。'):t('再靠近你想要的声音。') }}</span><h2 id="music-settings-title">{{ remake?t('调整音乐'):t('调出你心里的那首歌') }}</h2></div><button class="music-settings-close" :disabled="busy" :aria-label="t('关闭音乐设置')" @click="close">×</button></header>
 <p v-if="loading" class="notice" role="status">{{ t('正在读取音乐选项…') }}</p>
 <div v-if="catalog" class="music-settings-layout" :class="{'music-settings-layout-remake':remake}"><div class="music-settings-controls">
  <template v-if="!remake">
  <section class="music-recipes"><div class="music-category-heading"><h3>{{ t('先选一种感觉') }}</h3><span>{{ t('配方可以自由修改') }}</span></div><div class="music-recipe-grid"><button v-for="item in catalog.recipes" :key="item.id" :disabled="busy" :aria-pressed="prefs.recipeId===item.id" @click="recipe(item.id)"><strong>{{ label(item) }}</strong><small>{{ (item.choices.genre||[]).concat(item.choices.instruments||[]).map(id=>catalog!.groups.flatMap(g=>g.options).find(o=>o.id===id)).filter(Boolean).map(o=>label(o!)).join(' / ') }}</small></button></div></section>
  <div class="music-refine-toggle"><button :aria-expanded="detail" @click="detail=!detail">{{ detail?t('收起精细调整'):t('展开精细调整') }} <span aria-hidden="true">{{ detail?'−':'+' }}</span></button><span v-if="!prefs.recipeId">{{ t('自定义组合') }}</span></div>
  </template>
  <div v-else class="music-category-heading music-refine-heading"><h3>{{ t('精细调整') }}</h3></div>
  <fieldset :disabled="busy" class="music-voice-group"><legend>{{ t('演唱声音') }}</legend><div class="music-tag-options"><button v-for="voice in [{id:'AUTO',label:'自动'},{id:'MALE',label:'男声'},{id:'FEMALE',label:'女声'}]" :key="voice.id" type="button" :aria-pressed="prefs.vocalGender===voice.id" @click="prefs.vocalGender=voice.id as MusicPreferences['vocalGender'];prefs.recipeId=null">{{ t(voice.label) }}</button></div></fieldset>
  <div v-if="remake||detail" class="music-dimensions"><fieldset v-for="group in visibleGroups" :key="group.id" :disabled="busy"><legend>{{ label(group) }} <small>{{ t('最多 {count} 项',{count:group.max}) }}</small></legend><p v-if="group.id==='genre'" class="save-info">{{ t('最先选中的风格为主风格，其余为融合风格。') }}</p><p v-if="group.id==='instruments'" class="save-info">{{ t('最先选中的乐器作为主乐器，其余作为伴奏。') }}</p><div class="music-tag-options"><button v-for="option in group.options" :key="option.id" type="button" :aria-pressed="(prefs.choices[group.id]||[]).includes(option.id)" @click="choose(group.id,option.id)">{{ label(option) }}<span v-if="['instruments','genre'].includes(group.id)&&prefs.choices[group.id]?.[0]===option.id" class="music-lead">{{ t('主') }}</span></button></div></fieldset></div>
  <details class="music-advanced" :open="advanced" @toggle="advanced=($event.target as HTMLDetailsElement).open"><summary>{{ t('高级：律动与编曲') }}</summary><fieldset v-for="group in extras" :key="group.id" :disabled="busy"><legend>{{ label(group) }} <small>{{ t('最多 {count} 项',{count:group.max}) }}</small></legend><div class="music-tag-options"><button v-for="option in group.options" :key="option.id" type="button" :aria-pressed="(prefs.choices[group.id]||[]).includes(option.id)" @click="choose(group.id,option.id)">{{ label(option) }}</button></div></fieldset></details>
  <fieldset v-if="excludes" :disabled="busy" class="music-exclude"><legend>{{ t('不想要的元素') }}</legend><div class="music-tag-options"><button v-for="option in excludes.options" :key="option.id" type="button" :aria-pressed="(prefs.choices.exclude||[]).includes(option.id)" @click="choose('exclude',option.id)">{{ label(option) }}</button></div><label class="field">{{ t('其他需要避开的元素') }}<input v-model="prefs.negativeText" maxlength="200" :placeholder="t('例如：不要刺耳的高音')"></label></fieldset>
  <label class="field music-free-text">{{ t('还有什么想告诉我们？') }}<textarea v-model="prefs.freeText" :disabled="busy" maxlength="300" rows="3" :placeholder="t('例如：像平时聊天一样自然，人声突出，伴奏简单。')"></textarea><small>{{ t('补充要求会参与音乐生成，不会自动修改已经写好的歌词。') }}</small></label>
  <div v-if="aiAvailable" class="music-brief-assist"><button class="text-button" :disabled="busy||simplifying||!prefs.freeText.trim()" @click="simplify">{{ simplifying?t('正在精简音乐要求…'):t('AI精简补充要求') }}</button><small>{{ t('先预览再采用，不会修改歌词或自动替换原要求。') }}</small></div>
  <section v-if="suggestion" class="notice" :aria-label="t('精简建议')"><strong>{{ t('精简建议') }}</strong><p>{{ suggestion.text }}</p><small>{{ t('请确认曲风、乐器和限制均已保留。') }}</small><div class="actions"><button class="button secondary" :disabled="busy" @click="useSuggestion">{{ t('使用精简版') }}</button><button class="text-button" @click="suggestion=undefined">{{ t('保留原要求') }}</button></div></section>
  <details v-if="remake" class="music-advanced remake-lyrics"><summary>{{ t('修改完整歌词') }}</summary><label class="field">{{ t('新版歌词') }}<textarea v-model="remakeLyrics" :disabled="busy" maxlength="5000" rows="7"/></label></details>
 </div><aside class="music-settings-summary"><div class="music-summary-heading"><h3>{{ t('你选择的声音') }}</h3><small>{{ t('偏好组合，不是固定旋律') }}</small></div><div class="music-selected-chips"><button v-for="chip in chips" :key="chip.group+chip.option.id" :disabled="busy" :aria-label="t('移除 {label}',{label:label(chip.option)})" @click="choose(chip.group,chip.option.id)">{{ label(chip.option) }} <span aria-hidden="true">×</span></button><span v-if="!chips.length">{{ remake?t('尚未选择标签'):t('选一个配方，或展开精细调整。') }}</span></div><p v-if="previewing" class="music-summary-copy" role="status">{{ t('正在整理你的音乐偏好…') }}</p><p v-else-if="summary" class="music-summary-copy">{{ summary }}</p><p class="music-generation-boundary">{{ t('这些选择用于引导生成，实际旋律与唱腔仍由模型创作。') }}</p><p v-if="oldDraft" class="notice">{{ t('有一份较早的本机设置，你可以恢复后重新确认。') }}<button class="text-button" @click="recover">{{ t('恢复本机设置') }}</button></p></aside></div>
 <p v-if="hint" class="music-settings-hint" role="status">{{ t(hint) }}</p><p v-if="direction?.compilation?.notice" class="music-settings-hint" role="status">{{ t(direction.compilation.notice) }}</p><p v-if="error||submitError" class="music-settings-error" role="alert">{{ t(error||submitError||'') }}</p>
 <footer class="music-settings-footer"><p>{{ remake?t('本次设置和歌词用于整首新版。'):t('只保存偏好，不会开始生成歌曲。') }}<template v-if="remake&&credits&&credits.musicCost>0"> {{ t('每次歌曲生成消耗 {count} 积分',{count:credits.musicCost}) }}</template></p><div><button class="button secondary" :disabled="busy" @click="close">{{ t('暂不修改') }}</button><button class="button" :disabled="busy||loading||previewing||!direction||!!remake&&!remakeLyrics.trim()" @click="save">{{ remake?(busy?t('正在准备歌曲…'):t('生成整首新版')):busy?t('正在保存…'):t('确认音乐设置') }}</button></div></footer>
</dialog>
</template>
