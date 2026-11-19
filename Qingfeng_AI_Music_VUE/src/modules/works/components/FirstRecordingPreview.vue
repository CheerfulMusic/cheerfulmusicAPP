<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { defaultCover, t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { audioUrl, listAudio, type AudioAsset } from '../api/detail'

const props = defineProps<{ workId: string; taskId: string; title: string; cover?: string }>()
const player = usePlayer()
const first = ref<AudioAsset>()
const playing = computed(() => !!first.value && player.current?.id === first.value.id && (player.playing || player.starting))
let closed = false
let timer: ReturnType<typeof setTimeout>
async function findRecording() {
  try {
    const recordings = await listAudio(props.workId)
    if (closed) return
    first.value = recordings.filter(item => item.workId === props.workId && item.generationTaskId === props.taskId && item.origin === 'GENERATED' && !item.sectionEdit)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))[0]
  } catch { /* The progress dialog keeps tracking the original task. */ }
  if (!closed && !first.value) timer = setTimeout(findRecording, 2000)
}
function toggle() {
  const asset = first.value
  if (asset) void player.toggle({ id: asset.id, audio: audioUrl(props.workId, asset.id), title: props.title, cover: props.cover || defaultCover(), provenance: asset.origin })
}
onMounted(findRecording)
onBeforeUnmount(() => { closed = true; clearTimeout(timer) })
</script>
<template>
  <button v-if="first" class="generation-primary" type="button" @click="toggle">{{ playing ? t('暂停试听') : t('试听第一个版本') }}</button>
</template>
