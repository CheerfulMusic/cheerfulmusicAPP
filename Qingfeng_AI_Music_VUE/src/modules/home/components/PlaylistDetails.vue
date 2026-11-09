<script setup lang="ts">
import {ref,onMounted,onBeforeUnmount} from 'vue'
import {t} from '../../../shared/i18n'
import {usePlayer} from '../../../shared/player/store'
import type {PreviewPlaylist} from '../model/playlists'
import {recordPlay} from '../model/hot-songs'
import PreviewTrackRow from './PreviewTrackRow.vue'

const props=defineProps<{playlist:PreviewPlaylist}>(),emit=defineEmits<{close:[]}>()
const dialog=ref<HTMLDialogElement>(),player=usePlayer()
onMounted(()=>dialog.value?.showModal())
onBeforeUnmount(()=>dialog.value?.close())
function playAll(){const first=props.playlist.tracks[0];if(first){player.setQueue(props.playlist.tracks);if(player.current?.id!==first.id||!player.playing&&!player.starting){recordPlay(first.id);void player.toggle(first)}}}
function backdrop(event:MouseEvent){if(event.target!==dialog.value)return;const box=dialog.value.getBoundingClientRect();if(event.clientX<box.left||event.clientX>box.right||event.clientY<box.top||event.clientY>box.bottom)emit('close')}
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="playlist-dialog" aria-labelledby="playlist-detail-title" @cancel.prevent="emit('close')" @click="backdrop">
      <button class="playlist-close" type="button" autofocus :aria-label="t('关闭歌单')" @click="emit('close')">×</button>
      <header class="playlist-detail-heading"><img :src="playlist.cover" alt="" width="100" height="100"><div><h2 id="playlist-detail-title">{{ playlist.title }}</h2><p>{{ playlist.subtitle }}</p><button type="button" class="playlist-play-all" @click="playAll">{{ t('播放歌单') }}</button></div></header>
      <ul class="playlist-detail-tracks"><li v-for="track in playlist.tracks" :key="track.id">
        <PreviewTrackRow :track="track" :queue="playlist.tracks"/>
        <p class="playlist-track-description">{{ track.description }}</p>
        <div class="playlist-track-actions"><span class="playlist-track-labels"><span v-for="tag in track.tags" :key="tag">{{ tag }}</span></span><RouterLink :to="{path:'/create',query:{occasion:track.occasion,style:track.style}}" @click="emit('close')">{{ t('用这个风格创作') }} <span aria-hidden="true">↗</span></RouterLink></div>
      </li></ul>
    </dialog>
  </Teleport>
</template>
