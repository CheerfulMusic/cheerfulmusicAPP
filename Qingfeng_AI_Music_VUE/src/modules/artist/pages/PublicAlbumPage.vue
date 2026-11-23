<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
import { usePlayer, type PlayableTrack } from '../../../shared/player/store'
import { albumCover, publicAlbum, type Album } from '../api/albums'
import { getPublicArtist, publicTrack } from '../api/artist'
import ArtistShareButton from '../components/ArtistShareButton.vue'
const route=useRoute(), player=usePlayer(), artist=computed(()=>String(route.params.id)), id=computed(()=>String(route.params.album))
const album=ref<Album>(), stage=ref(''), loading=ref(true), error=ref(''), missing=ref(false)
const tracks=computed(()=>album.value?.tracks.map(song=>publicTrack(artist.value,song)) || [])
const link=computed(()=>new URL(route.path,location.origin).href)
let version=0
async function load() {
  const turn=++version; album.value=undefined; stage.value=''; loading.value=true; error.value=''; missing.value=false
  try {
    const [record,profile]=await Promise.all([publicAlbum(artist.value,id.value),getPublicArtist(artist.value)])
    if (turn===version) { album.value=record; stage.value=profile.stageName }
  } catch (cause) { if (turn===version) { missing.value=cause instanceof ApiError && cause.status===404; error.value=missing.value?'这张专辑暂未公开。':'专辑暂时无法读取。' } }
  finally { if (turn===version) loading.value=false }
}
function play(track: PlayableTrack) { player.setQueue(tracks.value,true); void player.toggle(track) }
function playAll() {
  const first=tracks.value[0]
  if (!first) return
  player.setQueue(tracks.value,true)
  if (player.current?.audio!==first.audio || !(player.playing || player.starting)) void player.toggle(first)
}
watch([artist,id],load,{immediate:true})
</script>
<template>
  <article v-if="album" class="public-album">
    <div class="album-hero"><img :src="albumCover(artist,album)" :alt="album.title"><div>
      <RouterLink :to="'/artist/'+artist">‹ {{ stage }}</RouterLink><h1>{{ album.title }}</h1>
      <p>{{ t('{count} 首歌曲',{count:tracks.length}) }}</p><p v-if="album.description" class="album-description">{{ album.description }}</p>
      <div class="album-actions"><button v-if="tracks[0]" type="button" @click="playAll">{{ t('播放全部') }}</button>
        <ArtistShareButton :url="link" :name="album.title" :album="true" /></div>
    </div></div>
    <ol><li v-for="(track,index) in tracks" :key="track.id">
      <button type="button" :aria-label="t(player.playing && player.current?.audio===track.audio?'暂停 {title}':'试听 {title}',{title:track.title})" @click="play(track)">
        <span>{{ String(index+1).padStart(2,'0') }}</span><img :src="track.cover" alt=""><strong>{{ track.title }}</strong><span>{{ player.playing && player.current?.audio===track.audio?'Ⅱ':'▷' }}</span>
      </button>
    </li></ol>
  </article>
  <p v-else-if="loading" role="status">{{ t('正在读取专辑…') }}</p>
  <section v-else class="module-card"><p role="status">{{ t(error) }}</p><button v-if="!missing" type="button" @click="load">{{ t('重试') }}</button><RouterLink :to="'/artist/'+artist">{{ t('返回音乐人主页') }}</RouterLink></section>
</template>
<style scoped>
.public-album { max-width: 53rem; margin: 0 auto; color: #21233e; background: #f6f5ff; border-radius: 1.15rem; overflow: hidden; }
.album-hero { display: grid; grid-template-columns: minmax(8rem,14rem) 1fr; align-items: center; gap: 1.3rem; padding: clamp(1.2rem,4vw,2.5rem); color: white; background: radial-gradient(ellipse at right bottom,#7e69af,transparent 70%),linear-gradient(120deg,#282c64,#383872); }
.album-hero>img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: .7rem; box-shadow: 0 .6rem 1.3rem #18143555; }
.album-hero h1 { margin: .65rem 0; overflow-wrap: anywhere; }.album-hero a { color: inherit; }
.album-description { white-space: pre-wrap; overflow-wrap: anywhere; }.album-actions { display: flex; align-items: center; flex-wrap: wrap; gap: .6rem; }
.album-actions>button { padding: .55rem 1.1rem; border: 0; border-radius: 999px; color: #332653; background: #e9deff; font: inherit; }
.public-album ol { list-style: none; margin: 0; padding: 1rem; }.public-album li>button { display: flex; align-items: center; gap: .7rem; width: 100%; background: transparent; text-align: left; color: inherit; font: inherit; border: 0; border-bottom: 1px solid #dfdeec; padding: .65rem .25rem; }
.public-album li img { width: 2.75rem; height: 2.75rem; object-fit: cover; border-radius: .45rem; }.public-album li strong { flex: 1; }
@media(max-width:540px) { .album-hero { grid-template-columns: 1fr; }.album-hero>img { width: min(62vw,13rem); margin: auto; } }
</style>
