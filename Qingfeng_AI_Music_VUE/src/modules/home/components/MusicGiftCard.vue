<script setup lang="ts">
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { t } from '../../../shared/i18n/index'
import type { PreviewTrack } from '../model/tracks'
import { usePlayer } from '../../../shared/player/store'
const props=defineProps<{ track: PreviewTrack }>()
const player = usePlayer()
const totalSeconds=()=>player.current?.id===props.track.id&&player.duration>0?player.duration:props.track.durationSeconds
const durationLabel=()=>`${Math.floor(totalSeconds()/60).toString().padStart(2,'0')}:${Math.floor(totalSeconds()%60).toString().padStart(2,'0')}`
</script>
<template><div class="p-gift-frame"><div class="p-photo p-hero-photo"><img :src="track.cover" :alt="t('{p0}，AI场景图', { p0: track.subtitle })" width="1024" height="1536"><div class="p-photo-shade"></div><div class="p-dedication"><span>{{ t('有一首歌，只为你而唱') }}</span><strong>{{ track.recipient }}</strong><i>{{ t('青风寄语') }}</i></div><div class="p-photo-copy"><p>{{ track.quote }}</p><small>{{ t('把故事写进歌里，把心意送到你身边。') }}</small></div></div><div class="p-card-player"><button class="p-gold-play" :aria-label="t('{action}{title}', {action:player.current?.id === track.id && player.playing ? t('暂停') : t('试听'), title:track.title})" @click="player.toggle(track)"><PlaybackIcon :playing="player.current?.id === track.id && player.playing"/></button><div class="p-card-track"><strong>{{ track.title }}</strong><div class="p-static-progress"><span :style="{ width: `${player.current?.id === track.id ? player.time / (totalSeconds()) * 100 : 0}%` }"></span></div><span class="p-track-kind">{{ t('完整歌曲试听') }}</span></div><span class="p-duration">{{ durationLabel() }}</span></div></div></template>
