<script setup lang="ts">
import {t} from '../../../shared/i18n'
import type {PreviewPlaylist} from '../model/playlists'
import PreviewTrackRow from './PreviewTrackRow.vue'
defineProps<{playlist:PreviewPlaylist}>()
defineEmits<{more:[]}>()
</script>

<template>
  <article class="explore-card playlist-card">
    <header class="explore-category-heading">
      <img class="explore-category-cover" :src="playlist.cover" :alt="t('{title}歌单封面',{title:playlist.title})" width="100" height="100" loading="lazy">
      <div><h3>{{ playlist.title }}</h3><p>{{ playlist.subtitle }}</p></div>
    </header>
    <ul class="explore-track-list"><li v-for="track in playlist.tracks.slice(0,3)" :key="track.id"><PreviewTrackRow :track="track" :queue="playlist.tracks"/></li></ul>
    <button class="playlist-more" type="button" :aria-label="t('查看{title}歌单',{title:playlist.title})" @click="$emit('more')">{{ t('查看更多') }}</button>
  </article>
</template>
