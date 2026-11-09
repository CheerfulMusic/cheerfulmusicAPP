<script setup lang="ts">
import {computed} from 'vue'
import {t} from '../../../shared/i18n'
import {usePlayer} from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import type {PreviewTrack} from '../model/tracks'

const props=defineProps<{track:PreviewTrack;queue:PreviewTrack[]}>()
const player=usePlayer()
const playing=computed(()=>player.current?.id===props.track.id&&(player.playing||player.starting))
const duration=computed(()=>{const seconds=Math.floor(props.track.durationSeconds);return `${Math.floor(seconds/60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}`})
function play(){player.setQueue(props.queue);void player.toggle(props.track)}
</script>

<template>
  <button type="button" class="explore-track-row listen-action" :class="{'is-playing':playing}" :aria-label="t('{action}{title}',{action:playing?t('暂停'):t('试听'),title:track.title})" :aria-pressed="playing" @click="play">
    <img :src="track.cover" alt="" width="52" height="52" loading="lazy">
    <span class="explore-track-copy"><strong :title="track.title">{{ track.title }}</strong><span>{{ track.genre||track.category }} <span aria-hidden="true">·</span> {{ duration }}</span></span>
    <span class="explore-track-play"><PlaybackIcon :playing="playing"/></span>
  </button>
</template>
