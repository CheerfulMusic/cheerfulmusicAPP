<script setup lang="ts">
import { t, formatDate, defaultCover } from '../../../shared/i18n/index'
import { onMounted, ref } from 'vue'
import type { Work } from '../../studio'
import { listAudio, audioUrl, type AudioAsset } from '../api/detail'
import { usePlayer } from '../../../shared/player/store'
import {getAudioSelection,selectAudio} from '../api/section-edits'
const props = defineProps<{ work: Work;cover?:string }>()
const emit = defineEmits<{ selected: [asset: AudioAsset | null];compare:[assetId:string] }>()
const assets = ref<AudioAsset[]>([]), selected = ref(''), error = ref(''), player = usePlayer()
let selectionVersion=0
function show(asset:AudioAsset){selected.value=asset.id;emit('selected',asset)}
async function select(asset: AudioAsset) {try{const saved=await selectAudio(props.work.id,asset.id,selectionVersion);selectionVersion=saved.version;show(asset)}catch(e){error.value=e instanceof Error?e.message:'当前歌曲版本已改变，请刷新后再选择。'}}
onMounted(async () => { try { const [list,current]=await Promise.all([listAudio(props.work.id),getAudioSelection(props.work.id)]);assets.value=list;selectionVersion=current.version;if(current.asset){if(!assets.value.some(a=>a.id===current.asset!.id))assets.value.push(current.asset);show(current.asset)}else{const first=assets.value.find(a=>!a.sectionEdit);if(first)show(first)} } catch (e) { error.value = e instanceof Error ? e.message : '音频读取失败' } })
async function play(asset: AudioAsset) {if(asset.sectionEdit&&asset.id!==selected.value){emit('compare',asset.id);return}await select(asset); player.setQueue(assets.value.filter(a=>!a.sectionEdit||a.id===selected.value).map(item => ({ id:item.id, title:props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient}), cover:props.cover||defaultCover(), audio:audioUrl(props.work.id,item.id), provenance:item.origin })),false); player.toggle({ id: asset.id, title: props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient}), cover: props.cover||defaultCover(), audio: audioUrl(props.work.id, asset.id), provenance: asset.origin }) }
</script>
<template><section class="audio-manager"><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><div v-if="assets.length" class="song-version-list"><h3>{{ t('对比歌曲版本') }}</h3><p class="save-info">{{ t('切换试听后，再选择你想送出的版本。') }}</p><article v-for="item in assets" :key="item.id" class="audio-row-item" :class="{selected:selected===item.id}"><label><input type="radio" :checked="selected===item.id" :value="item.id" :disabled="item.sectionEdit&&item.id!==selected" @change="select(item)"><span>{{ item.sectionEdit?t('局部修改版'):item.origin==='IMPORTED'?t('导入音频 · 非AI生成'):t('AI生成音频') }}<small>{{ (item.byteSize/1024/1024).toFixed(2) }} MB · {{ formatDate(item.createdAt) }}</small></span></label><div class="audio-actions"><button class="button secondary" @click="play(item)">{{ item.sectionEdit&&item.id!==selected?t('对比修改版'):player.current?.id===item.id&&player.playing?t('暂停'):t('试听') }}</button></div></article></div></section></template>
