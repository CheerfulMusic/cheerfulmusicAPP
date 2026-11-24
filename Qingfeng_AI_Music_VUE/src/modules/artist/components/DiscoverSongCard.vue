<script setup lang="ts">
import { t, locale } from '../../../shared/i18n'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import type { DiscoverSong } from '../api/artist'

withDefaults(defineProps<{
  song: DiscoverSong
  cover: string
  playing: boolean
  duration?: string
  genre?: string
}>(), { duration: '', genre: '' })
defineEmits<{ play: [] }>()
</script>

<template>
  <li class="discover-song">
    <div class="discover-art">
      <img :src="cover" alt="" loading="lazy" width="320" height="320">
      <span v-if="duration" class="discover-duration">{{ duration }}</span>
      <button class="discover-play" type="button" :aria-pressed="playing"
        :aria-label="t(playing ? '暂停 {title}' : '试听 {title}', { title: song.song.title })" @click="$emit('play')">
        <PlaybackIcon :playing="playing" />
      </button>
    </div>
    <div class="discover-song-heading">
      <h3>{{ song.song.title }}</h3>
      <span v-if="genre" class="discover-song-genre">{{ genre }}</span>
    </div>
    <div class="discover-song-meta">
      <RouterLink :to="{ name: 'artist-home', params: { id: song.artistId } }">{{ song.stageName }}</RouterLink>
      <span v-if="song.plays !== undefined" :aria-label="t('{count} 次播放', { count: new Intl.NumberFormat(locale).format(song.plays) })">
        <NavIcon name="play" />{{ new Intl.NumberFormat(locale, { notation: 'compact' }).format(song.plays) }}
      </span>
    </div>
  </li>
</template>

<style scoped>
.discover-song { min-width: 0; }
.discover-art { position: relative; overflow: hidden; aspect-ratio: 1; border-radius: 8px; background: #f1f2f4; }
.discover-art > img { display: block; width: 100%; height: 100%; object-fit: cover; }
.discover-play { position: absolute; inset: auto 10px 10px auto; padding: 0; }
@media (hover: hover) {
  .discover-song:not(:hover) .discover-play:not(:focus-visible) { opacity: 0; pointer-events: none; }
}
.discover-duration { position: absolute; inset: auto auto 12px 10px; padding: 2px 6px; border-radius: 4px; color: #fff; background: #18232bbf; font-size: .75rem; font-variant-numeric: tabular-nums; }
.discover-song-heading { display: flex; align-items: baseline; gap: 8px; margin-top: 10px; }
.discover-song-heading h3 { overflow: hidden; margin: 0; font: inherit; font-size: .98rem; font-weight: 650; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.discover-song-genre { flex: none; color: #8a8a93; font-size: .7rem; }
.discover-song-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 2px; color: #73737e; font-size: .8rem; line-height: 1.6; }
.discover-song-meta a { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.discover-song-meta a:hover { color: #ca3025; }
.discover-song-meta span { display: inline-flex; flex: none; align-items: center; gap: 2px; font-size: .73rem; }
.discover-song-meta svg { width: 12px; height: 12px; }
.discover-play:focus-visible, a:focus-visible { outline: 3px solid #b82c23; outline-offset: 3px; }
@media (max-width: 480px) {
  .discover-song-heading h3 { font-size: .89rem; }
  .discover-song-genre { display: none; }
  .discover-song-meta { flex-wrap: wrap; gap: 0 5px; font-size: .76rem; }
  .discover-play { inset: auto 8px 8px auto; }
}
</style>
