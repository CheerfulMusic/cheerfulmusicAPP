<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { locale } from '../../../shared/i18n'
import { homePublicInfo } from '../../../shared/i18n/home-seo'
import { listDiscoverSongs, type DiscoverSong } from '../../artist/api/artist'
import { discoverSongHref } from '../../artist/api/discover-song'

defineProps<{ showSteps?: boolean }>()
const copy = computed(() => homePublicInfo[locale.value])
const songs = ref<DiscoverSong[]>([])
onMounted(async () => {
  try {
    const page = await listDiscoverSongs('featured', 0, '', '')
    songs.value = page.songs.slice(0, 3)
  } catch {
    // The instructions and FAQ stay available when the public catalog is offline.
  }
})
</script>

<template>
  <div class="home-public-info">
    <section v-if="showSteps">
      <h2>{{ copy.how }}</h2>
      <ol><li v-for="step in copy.steps" :key="step">{{ step }}</li></ol>
      <a :href="copy.guidePath">{{ copy.guide }}</a>
    </section>
    <section v-if="songs.length">
      <h2>{{ copy.examples }}</h2>
      <ul>
        <li v-for="track in songs" :key="track.song.id">
          <a :href="discoverSongHref(track.song)">{{ track.song.title }}</a>
          <span v-if="track.stageName"> · {{ track.stageName }}</span>
        </li>
      </ul>
    </section>
    <section>
      <h2>{{ copy.faq }}</h2>
      <details v-for="[question, answer] in copy.answers" :key="question">
        <summary>{{ question }}</summary>
        <p>{{ answer }}</p>
      </details>
    </section>
  </div>
</template>

<style>
.home-public-info { font-size:14px; line-height:1.6; color:#584940; }
.home-public-info section { margin-block:18px; }
.home-public-info h2 { margin:0 0 6px; font-size:17px; color:#302822; }
.home-public-info ol,.home-public-info ul { margin:0 0 6px; padding-inline-start:1.5em; }
.home-public-info summary { cursor:pointer; padding-block:5px; }
.home-public-info details p { margin:0 0 6px; }
.home-public-info a { color:#914030; text-underline-offset:3px; }
</style>
