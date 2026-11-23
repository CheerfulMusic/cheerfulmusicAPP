<script setup lang="ts">
import { ref, watch } from 'vue'
import { t } from '../../../shared/i18n'
import { albumCover, albumPath, artistAlbums, type Album } from '../api/albums'
const props=defineProps<{artist: string}>()
const albums=ref<Album[]>([]), error=ref(false)
let version=0
async function load() {
  const turn=++version
  albums.value=[]; error.value=false
  try { const result=await artistAlbums(props.artist); if (turn===version) albums.value=result }
  catch { if (turn===version) error.value=true }
}
watch(()=>props.artist,load,{immediate:true})
</script>
<template>
  <section v-if="albums.length || error" class="album-shelf" aria-labelledby="artist-albums">
    <h2 id="artist-albums">{{ t('专辑') }}</h2>
    <button v-if="error" class="text-button" type="button" @click="load">{{ t('重新载入专辑') }}</button>
    <div v-else class="album-grid"><RouterLink v-for="album in albums" :key="album.id" :to="albumPath(artist,album.id)">
      <img :src="albumCover(artist,album)" alt="" loading="lazy"><strong>{{ album.title }}</strong><small>{{ t('{count} 首歌曲',{count:album.tracks.length}) }}</small>
    </RouterLink></div>
  </section>
</template>
<style scoped>
.album-shelf { margin: 1.4rem auto; max-width: 84rem; padding: 1.2rem; }
.album-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(min(9.5rem,42%),1fr)); gap: 1rem; }
.album-grid a { display: grid; gap: .3rem; color: inherit; text-decoration: none; overflow-wrap: anywhere; }
.album-grid img { width: 100%; max-width: 14rem; aspect-ratio: 1; object-fit: cover; border-radius: .75rem; }
</style>
