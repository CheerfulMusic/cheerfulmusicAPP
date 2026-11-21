<script setup lang="ts">
import {t} from '../../../shared/i18n'
import type {PreviewPlaylist} from '../model/playlists'
import type {PreviewTrack} from '../model/tracks'
import PreviewTrackRow from './PreviewTrackRow.vue'
defineProps<{playlist:PreviewPlaylist;queue:PreviewTrack[]}>()
defineEmits<{more:[]}>()
</script>

<template>
  <article class="explore-card playlist-card">
    <header class="explore-category-heading">
      <img class="playlist-genre-banner" :src="playlist.cover" :alt="t('{title}歌单封面',{title:playlist.title})" loading="lazy" width="840" height="343">
      <div><p lang="en">{{ playlist.subtitle }}</p><h3>{{ playlist.title }}</h3></div>
    </header>
    <ul v-if="playlist.tracks.length" class="explore-track-list"><li v-for="track in playlist.tracks.slice(0,6)" :key="track.id"><PreviewTrackRow :track="track" :queue="queue" hide-metrics/></li></ul>
    <p v-if="!playlist.tracks.length" class="playlist-empty">{{ t('歌曲即将上新') }}</p>
    <button class="playlist-more" type="button" :disabled="!playlist.tracks.length" :aria-label="t('查看{title}歌单',{title:playlist.title})" @click="$emit('more')">{{ t('查看全部') }} <span aria-hidden="true">→</span></button>
  </article>
</template>
