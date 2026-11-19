<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { locale } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { usePreviewTracks, type PreviewTrack } from '../../home/model/tracks'
import { loadHotSongs, sortByHeat, hotFailure, hotLoading, recordPlay } from '../../home/model/hot-songs'
const tr = (zh: string, en: string) => locale.value === 'en' ? en : zh
const player = usePlayer(), tracks = usePreviewTracks(), ranking = ref<string[]>([])
const songs = computed(() => [...tracks.value].sort((a,b) => ranking.value.indexOf(a.id)-ranking.value.indexOf(b.id)))
async function refresh() { await loadHotSongs(true); ranking.value = sortByHeat(tracks.value).map(track => track.id) }
onMounted(() => { void refresh() })
function active(track: PreviewTrack) { return player.current?.id === track.id && (player.playing || player.starting) }
async function play(track: PreviewTrack) { player.setQueue(songs.value); const wasPlaying = active(track); await player.toggle(track); if (!wasPlaying && player.current?.id === track.id && player.playing) recordPlay(track.id) }
function duration(seconds: number) { return `${Math.floor(seconds/60)}:${String(Math.floor(seconds%60)).padStart(2,'0')}` }
</script>
<template>
  <section class="agent-hot-songs" :aria-label="tr('热歌推荐榜','Popular songs')">
    <header><h2>{{ tr('热歌推荐','Recommended songs') }}</h2></header>
    <div v-if="songs[0]" class="hot-featured">
      <img :src="songs[0].cover" alt=""/><div class="hot-featured-shade"/>
      <span class="hot-featured-label">{{ tr('热歌推荐','RECOMMENDED') }} / 01</span>
      <div class="hot-featured-copy"><small>{{ songs[0].genre || songs[0].category }}</small><h3>{{ songs[0].title }}</h3></div>
      <button type="button" :aria-label="(active(songs[0])?tr('暂停','Pause '):tr('试听','Play '))+songs[0].title" :aria-pressed="active(songs[0])" @click="play(songs[0])"><span aria-hidden="true">{{ active(songs[0]) ? 'Ⅱ' : '▶' }}</span></button>
    </div>
    <ol><li v-for="(track,index) in songs" :key="track.id" :class="{'is-playing':active(track)}"><span class="hot-rank">{{ String(index+1).padStart(2,'0') }}</span><button class="hot-song-play" type="button" :aria-label="(active(track)?tr('暂停','Pause '):tr('试听','Play '))+track.title" :aria-pressed="active(track)" @click="play(track)"><img :src="track.cover" alt="" loading="lazy"/><span><strong>{{ track.title }}</strong><small>{{ track.genre || track.category }} · {{ duration(track.durationSeconds) }}</small></span><i aria-hidden="true">{{ active(track)?'Ⅱ':'▶' }}</i></button></li></ol>
    <p v-if="player.error && songs.some(track => track.id===player.current?.id)" class="hot-chart-error" role="status">{{ player.error }}</p>
    <p v-if="hotFailure" class="hot-chart-error" role="status">{{ tr('热度暂未更新，先为你展示精选试听。','Showing curated songs while rankings are unavailable.') }} <button type="button" :disabled="hotLoading" @click="refresh">{{ tr('重试','Retry') }}</button></p>
    <p v-else-if="hotLoading" class="hot-chart-note" role="status">{{ tr('正在更新热度…','Updating ranking…') }}</p>
  </section>
</template>
