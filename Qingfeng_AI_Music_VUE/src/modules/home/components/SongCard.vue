<script setup lang="ts">
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { t } from '../../../shared/i18n/index'
import type { PreviewTrack } from '../model/tracks'
import { usePlayer } from '../../../shared/player/store'
defineProps<{ track: PreviewTrack }>()
const player = usePlayer()
</script>
<template><article class="p-song-card"><div class="p-song-art"><img :src="track.cover" :alt="t('{p0}，AI场景图', { p0: track.subtitle })" width="1024" height="1536" loading="lazy"><div class="p-song-image-caption"><span>{{ track.subtitle }}</span><strong>{{ track.title }}</strong></div><button class="p-song-play" :aria-label="t('{action}{title}', {action:player.current?.id === track.id && player.playing?t('暂停'):t('试听'),title:track.title})" @click="player.toggle(track)"><span><PlaybackIcon :playing="player.current?.id === track.id && player.playing"/></span><span>{{ player.current?.id === track.id && player.playing ? t("暂停") : t("试听") }}</span></button></div><div class="p-song-info"><p>{{ track.description }}</p><span>{{ track.style }}</span></div><RouterLink class="p-make-similar" :to="{ path: '/create', query: { occasion: track.occasion } }">{{ t('我也想制作这样的歌') }} <span>↗</span></RouterLink></article></template>
