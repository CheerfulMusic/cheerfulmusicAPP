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
const description=computed(()=>[props.work.story,props.work.message].filter(Boolean).join('\n\n'))
const icons={
 genre:'M9 18V5l11-2v13M9 9l11-2M9 18c0-3-6-2-6 1s6 2 6-1Zm11-2c0-3-6-2-6 1s6 2 6-1Z',
 mood:'M12 20 4 12a4.5 4.5 0 0 1 8-4 4.5 4.5 0 0 1 8 4Z',
 tempo:'M3 11v2m4-5v8m5-12v16m5-12v8m4-5v2',
 voice:'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0ZM6 11v1a6 6 0 0 0 12 0v-1m-6 7v4m-3 0h6',
 language:'M12 3a9 9 0 1 0 0 18 9 9 0 1 0 0-18Zm0 0c-5 5-5 13 0 18 5-5 5-13 0-18ZM3 12h18',
 template:'m3 8 9-5 9 5-9 5Zm0 4 9 5 9-5M3 16l9 5 9-5',
}
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
 const template=props.revision?.templateId, timbre=choices('timbre')
 const voice=t(vocalGenderLabel(prefs?.vocalGender||props.work.vocalGender))
 return [
  {label:'曲风',value:choices('genre')||t(props.work.style),icon:icons.genre},
  {label:'情绪',value:choices('mood'),icon:icons.mood},
  {label:'节奏',value:choices('tempo'),icon:icons.tempo},
  {label:'演唱声音',value:timbre?`${voice} (${timbre})`:voice,icon:icons.voice},
  {label:'歌词语言',value:t(labels[props.revision?.language||'']||''),icon:icons.language},
  {label:'歌词模板',value:template?(template==='AUTO'?t('自由结构'):template)+(props.revision?.chorusFirst?' · '+t('副歌先行'):''):'',icon:icons.template},
 ]
})
</script>

<template>
  <details class="work-overview creation-settings-summary" :open="!compact||expanded">
    <summary @click.prevent="expanded=!expanded">{{ t('查看本次创作设置') }}</summary>
    <h2>{{ t('你的个性化选择') }}</h2>
    <p>{{ t('以下内容继承自上一步的创作设置') }}</p>
    <details v-if="description" class="setting-description">
      <summary>
        <strong><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h14v16H5ZM8 8h1m3 0h4m-8 4h1m3 0h4m-8 4h1m3 0h4"/></svg>{{ t('原始描述') }}<span aria-hidden="true">⌄</span></strong>
        <span class="description-preview">{{ description }}</span>
      </summary>
      <p>{{ description }}</p>
    </details>
    <p v-if="state==='failed'" role="status">{{ t('部分设置暂时无法读取。') }}</p>
    <dl :aria-busy="state==='loading'">
      <div v-for="row in rows" :key="row.label"><dt><svg viewBox="0 0 24 24" aria-hidden="true"><path :d="row.icon"/></svg>{{ t(row.label) }}</dt><dd><span :class="{'empty-setting':!row.value}">{{ row.value||t('未设置') }}</span></dd></div>
    </dl>
  </details>
</template>

<style scoped>
.creation-settings-summary { display:block; margin:0; padding:18px clamp(16px,1.5vw,25px); border-radius:20px; background:#fffdfd; }
.creation-settings-summary > summary { display:none; cursor:pointer; font-weight:650; }
.creation-settings-summary h2 { margin:0 0 5px; color:#242020; font-size:clamp(18px,1.55vw,24px); line-height:1.4; }
.creation-settings-summary > p { margin:0 0 16px; font-size:14px; color:#796963; }
.setting-description { background:#faf6f6; border:1px solid #efe6e7; padding:12px 14px; border-radius:13px; }
.setting-description summary { cursor:pointer; list-style:none; }
.setting-description summary::-webkit-details-marker { display:none; }
.setting-description summary strong,dt { display:flex; align-items:center; gap:10px; color:#262122; font-weight:650; }
.setting-description summary strong span { margin-left:auto; color:#877574; }
.setting-description[open] summary strong span { rotate:180deg; }
.setting-description p,.description-preview { white-space:pre-wrap; overflow-wrap:anywhere; margin:7px 0 0; color:#695f61; font-size:14px; line-height:1.55; }
.description-preview { display:-webkit-box; -webkit-line-clamp:3; -webkit-box-orient:vertical; overflow:hidden; }
.setting-description[open] .description-preview { display:none; }
.setting-description p { max-height:160px; overflow:auto; }
svg { width:18px; height:18px; flex:none; fill:none; stroke:currentColor; stroke-width:1.7; stroke-linecap:round; stroke-linejoin:round; }
dl { margin:10px 0 0; }
dl > div { display:grid; grid-template-columns:minmax(96px,.86fr) minmax(0,1.14fr); gap:10px; align-items:center; min-height:42px; padding:6px 8px; border-bottom:1px solid #f1e9ea; font-size:14px; }
dl > div:last-child { border-bottom:0; }
dd { margin:0; overflow-wrap:anywhere; }
dd span { display:inline-block; padding:3px 10px; border:1px solid #eee4e6; border-radius:999px; background:#faf6f6; color:#392f31; }
dd .empty-setting { color:#74676b; }
@media(max-width:760px) {
  .creation-settings-summary > summary { display:list-item; }
  .creation-settings-summary h2 { display:none; }
  .creation-settings-summary > summary + h2 + p { margin-top:12px; }
}
</style>
