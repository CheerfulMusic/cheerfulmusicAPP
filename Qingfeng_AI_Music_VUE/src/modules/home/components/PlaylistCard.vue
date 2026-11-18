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
      <span class="playlist-genre-banner" role="img" :aria-label="t('{title}歌单封面',{title:playlist.title})" :style="{backgroundPosition: `${playlist.bannerPosition || '0%'} 25%`}"/>
      <div><p lang="en">{{ playlist.subtitle }}</p><h3>{{ playlist.title }}</h3></div>
    </header>
    <ul v-if="playlist.tracks.length" class="explore-track-list"><li v-for="track in playlist.tracks.slice(0,3)" :key="track.id"><PreviewTrackRow :track="track" :queue="playlist.tracks"/></li></ul>
    <p v-if="!playlist.tracks.length" class="playlist-empty">{{ t('歌曲即将上新') }}</p>
    <button class="playlist-more" type="button" :disabled="!playlist.tracks.length" :aria-label="t('查看{title}歌单',{title:playlist.title})" @click="$emit('more')">{{ t('查看全部') }} <span aria-hidden="true">→</span></button>
  </article>
</template>
