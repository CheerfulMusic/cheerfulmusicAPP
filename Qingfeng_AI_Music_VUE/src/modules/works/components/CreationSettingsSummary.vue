<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { t, locale } from '../../../shared/i18n'
import type { Work } from '../../studio'
import { vocalGenderLabel } from '../../studio/model/vocals'
import { loadMusicCatalog, loadMusicSettings, type MusicCatalog, type MusicDirection } from '../api/music-settings'
import type { Revision } from '../api/detail'

const props=defineProps<{work:Work;revision:Revision|null}>()
const direction=ref<MusicDirection|null>(null),catalog=ref<MusicCatalog>()
const state=ref<'loading'|'ready'|'failed'>('loading')
const compact=ref(false), expanded=ref(false)
let media:MediaQueryList|undefined
function updateViewport(){compact.value=media?.matches??false}
onMounted(()=>{media=window.matchMedia?.('(max-width: 760px)');updateViewport();media?.addEventListener('change',updateViewport)})
onBeforeUnmount(()=>media?.removeEventListener('change',updateViewport))
watch(()=>[props.work.id,props.work.version],async (_,__,onCleanup)=>{
 let current=true
 onCleanup(()=>{current=false})
 state.value='loading';direction.value=null
 try{
   const [settings,labels]=await Promise.all([loadMusicSettings(props.work.id),loadMusicCatalog().catch(()=>undefined)])
   if(!current)return
   direction.value=settings?.direction??null;catalog.value=labels;state.value='ready'
 }catch{if(current)state.value='failed'}
},{immediate:true})
function choices(group:string){
 const ids=direction.value?.preferences.choices[group]||[]
 const options=catalog.value?.groups.find(item=>item.id===group)?.options
 return ids.map(id=>options?.find(option=>option.id===id)).flatMap(option=>option?[locale.value==='en'?option.en:option.zh]:[]).join(' / ')
}
const rows=computed(()=>{
 const prefs=direction.value?.preferences
 const labels:Record<string,string>={'zh-CN':'中文',en:'English',mixed:'中英混合'}
 const template=props.revision?.templateId
 return [
  ['曲风',choices('genre')||t(props.work.style)],
  ['情绪',choices('mood')],
  ['节奏',choices('tempo')],
  ['演唱声音',t(vocalGenderLabel(prefs?.vocalGender||props.work.vocalGender))],
  ['歌词语言',t(labels[props.revision?.language||'']||'')],
  ['歌词模板',template?(template==='AUTO'?t('自由结构'):template)+(props.revision?.chorusFirst?' · '+t('副歌先行'):''):''],
 ]
})
</script>

<template>
  <details class="work-overview creation-settings-summary" :open="!compact||expanded">
    <summary @click.prevent="expanded=!expanded">{{ t('查看本次创作设置') }}</summary>
    <h2>{{ t('本次创作设置') }}</h2>
    <p>{{ t('沿用上一步的音乐设置') }}</p>
    <details v-if="work.story||work.message" class="setting-description">
      <summary>{{ t('创作描述') }}</summary>
      <p>{{ [work.story,work.message].filter(Boolean).join('\n\n') }}</p>
    </details>
    <p v-if="state==='failed'" role="status">{{ t('部分设置暂时无法读取。') }}</p>
    <dl :aria-busy="state==='loading'">
      <div v-for="[label,value] in rows" :key="label"><dt>{{ t(label!) }}</dt><dd>{{ value||t('未设置') }}</dd></div>
    </dl>
  </details>
</template>

<style scoped>
.creation-settings-summary { display:block; margin:0; align-self:start; }
.creation-settings-summary > summary { display:none; cursor:pointer; font-weight:650; }
.creation-settings-summary h2 { margin:0 0 5px; }
.creation-settings-summary > p { margin:0 0 14px; }
.setting-description { background:#fbf4f4; padding:10px 13px; border-radius:11px; }
.setting-description summary { cursor:pointer; font-weight:600; }
.setting-description p { max-height:160px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; margin:10px 0 0; }
dl { margin:13px 0 0; }
dl > div { display:grid; grid-template-columns:minmax(75px, .85fr) minmax(0, 1.15fr); gap:10px; align-items:baseline; padding:9px 0; border-bottom:1px solid #f0e4e5; font-size:14px; }
dl > div:last-child { border-bottom:0; }
dt { font-weight:600; }
dd { margin:0; color:var(--muted); overflow-wrap:anywhere; }
@media(max-width:760px) {
  .creation-settings-summary > summary { display:list-item; }
  .creation-settings-summary h2 { display:none; }
  .creation-settings-summary > summary + h2 + p { margin-top:12px; }
}
</style>
