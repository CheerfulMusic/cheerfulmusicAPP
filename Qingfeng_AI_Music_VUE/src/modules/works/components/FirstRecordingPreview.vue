<script setup lang="ts">
import { computed } from 'vue'
import { defaultCover, t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { audioUrl } from '../api/detail'

const props = defineProps<{ workId: string; assetId: string; title: string; cover?: string }>()
const player = usePlayer()
const playing = computed(() => player.current?.id === props.assetId && (player.playing || player.starting))
function toggle() {
  void player.toggle({ id: props.assetId, audio: audioUrl(props.workId, props.assetId), title: props.title, cover: props.cover || defaultCover(), provenance: 'GENERATED' })
}
</script>
<template>
  <button class="generation-primary" type="button" @click="toggle">{{ playing ? t('暂停试听') : t('试听首版') }}</button>
</template>
