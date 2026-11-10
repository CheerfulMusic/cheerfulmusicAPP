<script setup lang="ts">
import { t, formatDate, defaultCover } from '../../../shared/i18n/index'
import { onMounted, onBeforeUnmount, ref } from 'vue'
import type { Work } from '../../studio'
import { listAudio, audioUrl, type AudioAsset } from '../api/detail'
import { usePlayer } from '../../../shared/player/store'
import {getAudioSelection,selectAudio} from '../api/section-edits'
const props = defineProps<{ work: Work;cover?:string }>()
const emit = defineEmits<{ selected: [asset: AudioAsset | null];compare:[assetId:string];'load-state':[state:'loading'|'ready'|'failed'] }>()
const assets = ref<AudioAsset[]>([]), selected = ref(''), error = ref(''), player = usePlayer()
let selectionVersion=0,epoch=0,alive=true
const reading=ref(false)
function show(asset:AudioAsset){selected.value=asset.id;emit('selected',asset)}
async function select(asset: AudioAsset) {
 const turn=epoch
 try {
  const saved=await selectAudio(props.work.id,asset.id,selectionVersion)
  if(!alive||turn!==epoch)return
  selectionVersion=saved.version
  show(asset)
 } catch(e) {
  if(alive&&turn===epoch)error.value=e instanceof Error?e.message:'当前歌曲版本已改变，请刷新后再选择。'
 }
}
async function load(){
 if(reading.value||!alive)return
 const turn=++epoch,workId=props.work.id;reading.value=true;error.value='';emit('load-state','loading')
 const current=()=>alive&&turn===epoch&&workId===props.work.id
 let listOk=false,selectionOk=false,selectionDone=false,selectedAsset:AudioAsset|null=null
 function fallback(){
  if(!current()||!selectionDone||selectedAsset)return
  const first=assets.value.find(a=>!a.sectionEdit)
  if(first){show(first);emit('load-state','ready')}
 }
 // 已选成曲先到就先展示；版本列表慢或失败不能挡住试听。
 await Promise.allSettled([
  listAudio(workId).then(list=>{if(!current())return;listOk=true;assets.value=list;if(selectedAsset&&!list.some(a=>a.id===selectedAsset!.id))assets.value.push(selectedAsset);fallback()}),
  getAudioSelection(workId).then(value=>{
   if(!current())return
   selectionOk=true;selectionVersion=value.version;selectedAsset=value.asset
   if(value.asset){if(!assets.value.some(a=>a.id===value.asset!.id))assets.value.push(value.asset);show(value.asset);emit('load-state','ready')}
  }).finally(()=>{if(current()){selectionDone=true;fallback()}})
 ])
 if(!current())return
 reading.value=false
 if(selected.value){emit('load-state','ready');if(!listOk||!selectionOk)error.value='部分歌曲信息暂时无法读取，已有歌曲仍可试听。'}
 else if(listOk&&selectionOk)emit('load-state','ready')
 else{error.value='音频暂时无法读取，你的作品仍然保留。';emit('load-state','failed')}
}
onMounted(load)
onBeforeUnmount(()=>{alive=false;epoch++})
async function play(asset: AudioAsset) {
 if(asset.sectionEdit&&asset.id!==selected.value){emit('compare',asset.id);return}
 const turn=epoch
 await select(asset)
 if(!alive||turn!==epoch)return
 const track=(item:AudioAsset)=>({id:item.id,title:props.work.title||t('写给{recipient}的歌',{recipient:props.work.recipient}),cover:props.cover||defaultCover(),audio:audioUrl(props.work.id,item.id),provenance:item.origin})
 player.setQueue(assets.value.filter(item=>!item.sectionEdit||item.id===selected.value).map(track),false)
 void player.toggle(track(asset))
}
</script>
<template><section class="audio-manager"><p v-if="error" class="notice error" role="alert">{{ t(error) }} <button class="text-button" :disabled="reading" @click="load">{{ t('重新读取歌曲') }}</button></p><div v-if="assets.length" class="song-version-list"><h3>{{ t('对比歌曲版本') }}</h3><p class="save-info">{{ t('切换试听后，再选择你想送出的版本。') }}</p><article v-for="item in assets" :key="item.id" class="audio-row-item" :class="{selected:selected===item.id}"><label><input type="radio" :checked="selected===item.id" :value="item.id" :disabled="item.sectionEdit&&item.id!==selected" @change="select(item)"><span>{{ item.sectionEdit?t('局部修改版'):item.origin==='IMPORTED'?t('导入音频 · 非AI生成'):t('AI生成音频') }}<small>{{ (item.byteSize/1024/1024).toFixed(2) }} MB · {{ formatDate(item.createdAt) }}</small></span></label><div class="audio-actions"><button class="button secondary" @click="play(item)">{{ item.sectionEdit&&item.id!==selected?t('对比修改版'):player.current?.id===item.id&&player.playing?t('暂停'):t('试听') }}</button></div></article></div></section></template>
