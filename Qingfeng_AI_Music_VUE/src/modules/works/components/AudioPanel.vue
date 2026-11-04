<script setup lang="ts">
import { t, formatDate, defaultCover } from '../../../shared/i18n/index'
import { onMounted, ref } from 'vue'
import type { Work } from '../../studio'
import { listAudio, importAudio, audioUrl, type AudioAsset } from '../api/detail'
import { usePlayer } from '../../../shared/player/store'
import {getAudioSelection,selectAudio} from '../api/section-edits'
const props = defineProps<{ work: Work;cover?:string }>()
const emit = defineEmits<{ selected: [asset: AudioAsset | null];compare:[assetId:string] }>()
const assets = ref<AudioAsset[]>([]), selected = ref(''), busy = ref(false), error = ref(''), notice = ref(''), player = usePlayer()
let selectionVersion=0
function show(asset:AudioAsset){selected.value=asset.id;emit('selected',asset)}
async function select(asset: AudioAsset) {try{const saved=await selectAudio(props.work.id,asset.id,selectionVersion);selectionVersion=saved.version;show(asset)}catch(e){error.value=e instanceof Error?e.message:'当前歌曲版本已改变，请刷新后再选择。'}}
onMounted(async () => { try { const [list,current]=await Promise.all([listAudio(props.work.id),getAudioSelection(props.work.id)]);assets.value=list;selectionVersion=current.version;if(current.asset){if(!assets.value.some(a=>a.id===current.asset!.id))assets.value.push(current.asset);show(current.asset)}else{const first=assets.value.find(a=>!a.sectionEdit);if(first)show(first)} } catch (e) { error.value = e instanceof Error ? e.message : '音频读取失败' } })
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return
  error.value = ''; notice.value = ''
  if (file.size > 20 * 1024 * 1024) { error.value = '音频不能超过20MB'; input.value = ''; return }
  busy.value = true
  try { const asset = await importAudio(props.work.id, file); assets.value = await listAudio(props.work.id);const choice=await getAudioSelection(props.work.id);selectionVersion=choice.version;show(asset); notice.value = '已有音频已归档。它会标记为导入音频，不会标记成AI生成。' } catch (e) { error.value = e instanceof Error ? e.message : '导入失败' } finally { busy.value = false; input.value = '' }
}
async function play(asset: AudioAsset) {if(asset.sectionEdit&&asset.id!==selected.value){emit('compare',asset.id);return}await select(asset); player.setQueue(assets.value.filter(a=>!a.sectionEdit||a.id===selected.value).map(item => ({ id:item.id, title:props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient}), cover:props.cover||defaultCover(), audio:audioUrl(props.work.id,item.id), provenance:item.origin })),false); player.toggle({ id: asset.id, title: props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient}), cover: props.cover||defaultCover(), audio: audioUrl(props.work.id, asset.id), provenance: asset.origin }) }
</script>
<template><section class="audio-manager"><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><div v-if="assets.length" class="song-version-list"><h3>{{ t('对比歌曲版本') }}</h3><p class="save-info">{{ t('切换试听后，再选择你想送出的版本。') }}</p><article v-for="item in assets" :key="item.id" class="audio-row-item" :class="{selected:selected===item.id}"><label><input type="radio" :checked="selected===item.id" :value="item.id" :disabled="item.sectionEdit&&item.id!==selected" @change="select(item)"><span>{{ item.sectionEdit?t('局部修改版'):item.origin==='IMPORTED'?t('导入音频 · 非AI生成'):t('AI生成音频') }}<small>{{ (item.byteSize/1024/1024).toFixed(2) }} MB · {{ formatDate(item.createdAt) }}</small></span></label><div class="audio-actions"><button class="button secondary" @click="play(item)">{{ item.sectionEdit&&item.id!==selected?t('对比修改版'):player.current?.id===item.id&&player.playing?t('暂停'):t('试听') }}</button></div></article></div><details class="creation-fold"><summary>{{ t('其他音频与导入') }}<span v-if="assets.length"> ({{ assets.length }})</span></summary><p class="detail-muted">{{ t('可以导入你有权使用的 MP3／WAV，验证试听、下载和分享。音频是否与歌词相符，请在分享前确认。') }}</p><p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p><label class="file-field">{{ busy?t('正在归档音频…'):t('选择 MP3 / WAV（最大20MB）') }}<input type="file" accept=".mp3,.wav,audio/mpeg,audio/wav" :disabled="busy" @change="upload"></label></details></section></template>
