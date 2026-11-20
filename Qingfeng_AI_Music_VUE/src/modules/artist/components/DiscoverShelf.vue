<script setup lang="ts">
import { computed } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import NavIcon from '../../../shared/ui/NavIcon.vue'
import type { DiscoverShelfTrack } from '../model/discover-shelves'

const props = defineProps<{
  rows: DiscoverShelfTrack[]
  variant: 'square' | 'compact' | 'wide'
  label: string
  loading?: boolean
  failed?: boolean
  empty?: string
  showPlays?: boolean
  expanded?: boolean
  upcoming?: { title: string; cover: string }
}>()
const emit = defineEmits<{ play: [row: DiscoverShelfTrack]; retry: [] }>()
const player = usePlayer()
const formatCount = computed(() => new Intl.NumberFormat(locale.value, {
  notation: 'compact', maximumFractionDigits: 2, useGrouping: true,
}))

function isCurrent(row: DiscoverShelfTrack) {
  return player.current?.audio === row.track.audio
}

function isPlaying(row: DiscoverShelfTrack) {
  return isCurrent(row) && (player.playing || player.starting)
}

function clock(seconds: number) {
  const value = Math.max(0, Math.floor(seconds))
  return `${Math.floor(value / 60)}:${String(value % 60).padStart(2, '0')}`
}
</script>

<template>
  <ul v-if="rows.length" class="shelf-tracks" :class="[`shelf-${variant}`, { 'is-expanded': expanded }]" :aria-label="label" tabindex="0">
    <li v-for="row in rows" :key="row.track.audio">
      <button type="button" class="shelf-track" :aria-pressed="isPlaying(row)"
        :aria-label="t(isPlaying(row) ? '暂停 {title}' : '试听 {title}', { title: row.track.title })"
        @click="emit('play', row)">
        <span class="track-art">
          <img :src="row.track.cover" alt="" loading="lazy" decoding="async">
          <span class="track-play" aria-hidden="true"><PlaybackIcon :playing="isPlaying(row)" /></span>
        </span>
        <span class="track-text">
          <span class="track-title" :title="row.track.title">{{ row.track.title }}</span>
          <span v-if="row.stageName || (showPlays && row.plays !== undefined)" class="track-meta">
            <span v-if="row.stageName" class="track-artist">{{ row.stageName }}</span>
            <span v-if="showPlays && row.plays !== undefined" class="track-count"><NavIcon name="play" />{{ formatCount.format(row.plays) }}</span>
          </span>
          <span v-if="props.variant === 'compact'" class="track-progress" aria-hidden="true">
            <progress :value="isCurrent(row) ? player.time : 0" :max="(isCurrent(row) && player.duration) || row.durationSeconds || 1" />
            {{ clock(isCurrent(row) ? player.time : 0) }} / {{ isCurrent(row) && player.duration ? clock(player.duration) : row.durationSeconds ? clock(row.durationSeconds) : '—' }}
          </span>
        </span>
      </button>
    </li>
    <li v-if="upcoming" key="upcoming">
      <div class="shelf-track shelf-track-upcoming">
        <span class="track-art">
          <img :src="upcoming.cover" alt="" loading="lazy" decoding="async">
        </span>
        <span class="track-text">
          <span class="track-title" :title="upcoming.title">{{ upcoming.title }}</span>
          <span class="track-meta">{{ t('待上线') }}</span>
        </span>
      </div>
    </li>
  </ul>
  <p v-else-if="loading" class="shelf-status" role="status">{{ t('正在加载歌曲…') }}</p>
  <div v-else-if="failed" class="shelf-status" role="alert">
    {{ t('暂时无法加载广场歌曲，请重试。') }}
    <button type="button" class="text-button" @click="emit('retry')">{{ t('重试') }}</button>
  </div>
  <p v-else class="shelf-status">{{ empty || t('暂时没有歌曲') }}</p>
</template>

<style scoped>
.shelf-tracks {
  display: grid;
  grid-auto-flow: column;
  gap: 24px;
  overflow-x: auto;
  margin: 0;
  padding: 0 0 10px;
  list-style: none;
  scrollbar-color: auto;
}
.shelf-tracks::-webkit-scrollbar { height: 4px; }
.shelf-tracks::-webkit-scrollbar-thumb { border-radius: 6px; background: #ced5ce; }
@supports not selector(::-webkit-scrollbar) {
  .shelf-tracks { scrollbar-color: #ced5ce transparent; scrollbar-width: thin; }
}
.shelf-tracks:focus-visible, .shelf-track:focus-visible { outline: 2px solid #c63b2a; outline-offset: -2px; }
.shelf-square { grid-auto-columns: 152px; }
.shelf-compact { grid-template-rows: repeat(4, 54px); grid-auto-columns: calc((100% - 72px) / 5); gap: 9px 18px; }
.shelf-wide { grid-auto-columns: calc((100% - 66px) / 3.42); gap: 22px; }
.shelf-tracks.is-expanded { grid-auto-flow: row; grid-template-columns: repeat(auto-fill, 152px); overflow-x: visible; }
.shelf-wide.is-expanded { grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); }
.shelf-tracks li, .track-text, .track-artist { min-width: 0; }
.shelf-track {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: start;
  font: inherit;
}
.track-art { position: relative; display: block; overflow: hidden; border-radius: 4px; background: #eef0ed; }
.track-art img { display: block; width: 100%; height: 100%; object-fit: cover; }
.shelf-square .track-art { aspect-ratio: 1; }
.track-play {
  position: absolute;
  inset: auto 8px 8px auto;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: #17231cc2;
  color: white;
  opacity: 0;
}
.track-play svg, .track-count svg { width: 12px; height: 12px; }
.shelf-track:hover .track-play, .shelf-track:focus-visible .track-play,
.shelf-track[aria-pressed="true"] .track-play, .shelf-wide .track-play { opacity: 1; }
.track-text { display: block; margin-top: 7px; }
.track-title, .track-artist { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.track-title { font-size: 14px; font-weight: 500; line-height: 1.4; }
.track-meta { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-top: 3px; color: var(--discover-muted); font-size: 12px; }
.track-count { display: flex; flex: none; align-items: center; gap: 3px; font-size: 11px; }
.shelf-compact .shelf-track { display: grid; grid-template-columns: 44px minmax(0, 1fr); align-items: center; gap: 12px; height: 54px; border-bottom: 1px solid #eef0ee; }
.shelf-compact .track-art { width: 44px; height: 44px; }
.shelf-compact .track-play { inset: 0; width: 100%; height: 100%; border-radius: inherit; }
.shelf-compact .track-text { margin: 0; padding-right: 4px; }
.shelf-compact .track-title { font-size: 13px; }
.shelf-compact .track-meta { margin-top: 2px; font-size: 11px; }
.shelf-compact .shelf-track:is(:hover, :focus-visible, [aria-pressed="true"]) { border-color: transparent; border-radius: 4px; background: #f1f6f2; }
.track-progress { display: none; align-items: center; gap: 5px; margin-top: 3px; color: #426957; font-size: 9px; line-height: 1; white-space: nowrap; }
.shelf-track:is(:hover, :focus-visible, [aria-pressed="true"]) .track-progress { display: flex; }
.track-progress progress { flex: 1; width: 0; height: 3px; accent-color: #547d64; }
.shelf-wide .shelf-track { display: flex; align-items: flex-end; height: 114px; padding: 12px 42px 12px 14px; overflow: hidden; isolation: isolate; border-radius: 6px; color: #fff; }
.shelf-wide .track-art { position: absolute; z-index: -1; inset: 0; }
.shelf-wide .track-art::after { position: absolute; inset: 0; background: linear-gradient(0deg, #111924d9, transparent 80%); content: ''; }
.shelf-wide .track-play { z-index: 1; }
.shelf-wide .track-title { font-size: 16px; font-weight: 600; }
.shelf-wide .track-meta { color: #f0f5ef; font-size: 11px; }
.shelf-track-upcoming .track-meta { width: fit-content; padding: 2px 7px; border-radius: 4px; background: #162824d9; }
.shelf-status { margin: 0; padding: 14px 0; color: var(--discover-muted); font-size: 13px; }
.shelf-status button { margin-inline-start: 6px; }
@media (max-width: 1100px) {
  .shelf-compact { grid-auto-columns: max(154px, (100% - 72px) / 5); }
  .shelf-wide { grid-auto-columns: max(200px, (100% - 66px) / 3.42); }
}
@media (max-width: 600px) {
  .shelf-square { grid-auto-columns: 122px; gap: 16px; }
  .shelf-square.is-expanded { grid-template-columns: repeat(auto-fill, 122px); }
  .shelf-compact { grid-auto-columns: 150px; column-gap: 14px; }
  .shelf-compact .shelf-track { gap: 8px; }
}
</style>
