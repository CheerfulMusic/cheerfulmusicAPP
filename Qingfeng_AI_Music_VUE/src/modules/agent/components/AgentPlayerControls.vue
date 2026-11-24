<script setup lang="ts">
import { computed } from 'vue'
import { locale } from '../../../shared/i18n'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { usePlayer } from '../../../shared/player/store'

const props = defineProps<{
  position: number
  duration: number
  playing: boolean
  playable: boolean
  seekable: boolean
  active: boolean
  queueAvailable: boolean
  queueOpen: boolean
}>()
const emit = defineEmits<{ play: []; queue: [] }>()
const player = usePlayer()
const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
const percent = computed(() => props.duration > 0 ? Math.min(100, Math.max(0, props.position / props.duration * 100)) : 0)
const clock = (seconds: number) => Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0')
</script>

<template>
  <div class="agent-player">
    <input class="agent-preview-range" type="range" min="0" :max="duration || 1" :value="position"
           :style="{ '--played': percent + '%' }" :disabled="!seekable" :aria-label="tr('播放进度', 'Playback position')"
           :aria-valuetext="clock(position) + ' / ' + clock(duration)" @input="player.seek(Number(($event.target as HTMLInputElement).value))"/>
    <div class="agent-player-times"><span>{{ clock(position) }}</span><span>{{ clock(duration) }}</span></div>
    <div class="agent-player-controls">
      <button type="button" :disabled="!active" :aria-pressed="active && player.repeatOne" :aria-label="tr('单曲循环', 'Repeat one')" @click="player.toggleRepeat()"><NavIcon name="repeat"/></button>
      <button type="button" :disabled="!active || !player.hasQueue" :aria-label="tr('上一首', 'Previous track')" @click="player.next(-1)"><NavIcon name="previous"/></button>
      <button type="button" class="agent-preview-play" :disabled="!playable" :aria-label="playing ? tr('暂停', 'Pause') : tr('播放', 'Play')" @click="emit('play')"><PlaybackIcon :playing="playing"/></button>
      <button type="button" :disabled="!active || !player.hasQueue" :aria-label="tr('下一首', 'Next track')" @click="player.next()"><NavIcon name="next"/></button>
      <button type="button" :disabled="!queueAvailable" :aria-expanded="queueOpen" :aria-label="tr('播放列表', 'Playback queue')" @click="emit('queue')"><NavIcon name="queue"/></button>
    </div>
  </div>
</template>
