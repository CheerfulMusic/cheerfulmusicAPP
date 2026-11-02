<script setup lang="ts">
import { t } from '../../../shared/i18n/index'
import type { PreviewTrack } from '../model/tracks'
import { usePlayer } from '../../../shared/player/store'
defineProps<{ track: PreviewTrack }>()
const player = usePlayer()
</script>
<template><article class="p-song-card"><div class="p-song-art"><img :src="track.cover" :alt="t('{p0}，AI场景图', { p0: track.subtitle })" width="1024" height="1536" loading="lazy"><div class="p-song-image-caption"><span>{{ track.subtitle }}</span><strong>{{ track.title }}</strong></div><button class="p-song-play" :aria-label="t('试听{p0}', { p0: track.title })" @click="player.toggle(track)"><span>{{ player.current?.id === track.id && player.playing ? 'Ⅱ' : '▶' }}</span><span>{{ player.current?.id === track.id && player.playing ? t("暂停") : t("试听") }}</span></button></div><div class="p-song-info"><p>{{ track.description }}</p><span>{{ track.style }}</span></div><RouterLink class="p-make-similar" :to="{ path: '/create', query: { occasion: track.occasion } }">{{ t('我也想送出这样的心意') }} <span>↗</span></RouterLink></article></template>
