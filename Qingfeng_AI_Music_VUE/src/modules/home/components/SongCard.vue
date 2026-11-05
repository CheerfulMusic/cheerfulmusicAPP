<script setup lang="ts">
import { computed } from 'vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import { t } from '../../../shared/i18n/index'
import type { PreviewTrack } from '../model/tracks'
import { usePlayer } from '../../../shared/player/store'

const props = defineProps<{ track: PreviewTrack }>()
const player = usePlayer()
const playing = computed(() => player.current?.id === props.track.id && (player.playing || player.starting))
const duration = computed(() => {
  const seconds = Math.floor(props.track.durationSeconds)
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`
})
</script>

<template>
  <article class="explore-card">
    <header class="explore-category-heading">
      <img class="explore-category-cover" :src="track.cover" :alt="t('{p0}，AI场景图', { p0: track.category })" width="84" height="84" loading="lazy">
      <div><h3>{{ track.category }}</h3><p>{{ track.description }}</p></div>
    </header>
    <ul class="explore-track-list">
      <li>
        <button type="button" class="explore-track-row listen-action" :aria-label="t('{action}{title}', { action: playing ? t('暂停') : t('试听'), title: track.title })" :aria-pressed="playing" @click="player.toggle(track)">
          <img :src="track.cover" alt="" width="48" height="48" loading="lazy">
          <span class="explore-track-copy"><strong :title="track.title">{{ track.title }}</strong><span>{{ t('示例歌曲试听') }} <span aria-hidden="true">·</span> {{ duration }}</span></span>
          <span class="explore-track-play"><PlaybackIcon :playing="playing"/></span>
        </button>
      </li>
    </ul>
    <RouterLink class="style-create" :to="{ path: '/create', query: { occasion: track.occasion, style: track.style } }">{{ t('用这个风格创作') }} <span aria-hidden="true">↗</span></RouterLink>
  </article>
</template>
