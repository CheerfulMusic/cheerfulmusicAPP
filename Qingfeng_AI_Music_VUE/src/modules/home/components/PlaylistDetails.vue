<script setup lang="ts">
import {computed,ref,onMounted,onBeforeUnmount} from 'vue'
import {t} from '../../../shared/i18n'
import {usePlayer} from '../../../shared/player/store'
import type {PreviewPlaylist} from '../model/playlists'
import {recordPreviewPlay} from '../model/published-recommendations'
import PlaybackModeButton from '../../../shared/player/PlaybackModeButton.vue'
import PreviewTrackRow from './PreviewTrackRow.vue'
import {songCreationRoute} from '../../studio/model/song-reference'

const props=defineProps<{playlist:PreviewPlaylist}>(),emit=defineEmits<{close:[]}>()
const dialog=ref<HTMLDialogElement>(),player=usePlayer()
const isActivePlaylist=computed(()=>{
  const queue=player.tracks
  const active=player.current
  const listed=props.playlist.tracks
  return !!active&&queue.length===listed.length
    &&queue.some(track=>track.id===active.id&&track.audio===active.audio)
    &&queue.every(track=>listed.some(song=>song.id===track.id&&song.audio===track.audio))
})
onMounted(()=>dialog.value?.showModal())
onBeforeUnmount(()=>dialog.value?.close())
async function playAll(){const first=props.playlist.tracks[0];if(first){player.setQueue(props.playlist.tracks);if(player.current?.id!==first.id||!player.playing&&!player.starting){await player.toggle(first);if(player.current?.audio===first.audio&&player.playing)void recordPreviewPlay(first)}}}
function backdrop(event:MouseEvent){if(event.target!==dialog.value)return;const box=dialog.value.getBoundingClientRect();if(event.clientX<box.left||event.clientX>box.right||event.clientY<box.top||event.clientY>box.bottom)emit('close')}
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="playlist-dialog" aria-labelledby="playlist-detail-title" @cancel.prevent="emit('close')" @click="backdrop">
      <button class="playlist-close" type="button" autofocus :aria-label="t('关闭歌单')" @click="emit('close')">×</button>
      <header class="playlist-detail-heading">
        <img :src="playlist.cover" alt="" width="100" height="100">
        <div>
          <h2 id="playlist-detail-title">{{ playlist.title }}</h2>
          <p>{{ playlist.subtitle }}</p>
          <div v-if="playlist.tracks.length" class="playlist-playback-actions">
            <button type="button" class="playlist-play-all" @click="playAll">{{ t('播放歌单') }}</button>
            <PlaybackModeButton in-playlist :disabled="!isActivePlaylist" />
          </div>
        </div>
      </header>
      <ul v-if="playlist.tracks.length" class="playlist-detail-tracks"><li v-for="track in playlist.tracks" :key="track.id">
        <PreviewTrackRow :track="track" :queue="playlist.tracks"/>
        <p class="playlist-track-description">{{ track.description }}</p>
        <details v-if="track.lyrics" class="playlist-track-lyrics"><summary>{{ t('歌词') }}</summary><p>{{ track.lyrics }}</p></details>
        <div class="playlist-track-actions"><span class="playlist-track-labels"><span v-for="tag in track.tags" :key="tag">{{ tag }}</span></span><RouterLink :to="songCreationRoute(track)" @click="emit('close')">{{ t('用这个风格创作') }} <span aria-hidden="true">↗</span></RouterLink></div>
      </li></ul>
      <p v-else>{{ t('这里还没有公开作品。') }}</p>
    </dialog>
  </Teleport>
</template>

<style scoped>
.playlist-playback-actions { display:flex; flex-wrap:wrap; align-items:center; gap:6px; }
</style>
