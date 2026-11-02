<script setup lang="ts">
import { t, formatDate, defaultCover } from '../../../shared/i18n/index'
import { onMounted, ref } from 'vue'
import type { Work } from '../../studio'
import { listAudio, importAudio, audioUrl, type AudioAsset } from '../api/detail'
import { usePlayer } from '../../../shared/player/store'
const props = defineProps<{ work: Work }>()
const emit = defineEmits<{ selected: [asset: AudioAsset | null] }>()
const assets = ref<AudioAsset[]>([]), selected = ref(''), busy = ref(false), error = ref(''), notice = ref(''), player = usePlayer()
function select(asset: AudioAsset) { selected.value = asset.id; emit('selected', asset) }
onMounted(async () => { try { assets.value = await listAudio(props.work.id); if (assets.value.length) select(assets.value[0]!) } catch (e) { error.value = e instanceof Error ? e.message : '音频读取失败' } })
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return
  error.value = ''; notice.value = ''
  if (file.size > 20 * 1024 * 1024) { error.value = '音频不能超过20MB'; input.value = ''; return }
  busy.value = true
  try { const asset = await importAudio(props.work.id, file); assets.value = await listAudio(props.work.id); select(asset); notice.value = '已有音频已归档。它会标记为导入音频，不会标记成AI生成。' } catch (e) { error.value = e instanceof Error ? e.message : '导入失败' } finally { busy.value = false; input.value = '' }
}
function play(asset: AudioAsset) { select(asset); player.setQueue(assets.value.map(item => ({ id:item.id, title:props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient}), cover:defaultCover(), audio:audioUrl(props.work.id,item.id), provenance:item.origin }))); player.toggle({ id: asset.id, title: props.work.title || t('写给{recipient}的歌', {recipient:props.work.recipient}), cover: defaultCover(), audio: audioUrl(props.work.id, asset.id), provenance: asset.origin }) }
</script>
<template><section class="detail-panel"><div class="detail-heading"><h2>{{ t('给这份心意，一个声音。') }}</h2><span class="origin-tag">{{ t('已有音频导入') }}</span></div><p class="detail-muted">{{ t('可以导入你有权使用的 MP3／WAV，验证试听、下载和分享。音频是否与歌词相符，请在分享前确认。') }}</p><p v-if="error" class="notice error" role="alert">{{ t(error) }}</p><p v-if="notice" class="notice success" role="status">{{ t(notice) }}</p><label class="file-field">{{ busy ? t("正在归档音频…") : t("选择 MP3 / WAV（最大20MB）") }}<input type="file" accept=".mp3,.wav,audio/mpeg,audio/wav" :disabled="busy" @change="upload"></label><p v-if="!assets.length" class="detail-muted">{{ t('还没有音频。模型就绪后也可由生成结果归档到这里。') }}</p><article v-for="asset in assets" :key="asset.id" class="audio-row-item" :class="{selected:selected===asset.id}"><label><input type="radio" :checked="selected===asset.id" :value="asset.id" @change="select(asset)"><span>{{ asset.origin === 'IMPORTED' ? t("导入音频 · 非AI生成") : t("AI生成音频") }}<small>{{ (asset.byteSize / 1024 / 1024).toFixed(2) }} MB · {{ formatDate(asset.createdAt) }}</small></span></label><div class="audio-actions"><button class="button secondary" :aria-label="t('播放{p0}', { p0: asset.id })" @click="play(asset)">{{ player.current?.id === asset.id && player.playing ? t("暂停") : t("试听") }}</button><a class="button secondary" :href="audioUrl(work.id,asset.id,true)">{{ t('下载') }}</a></div></article></section></template>
