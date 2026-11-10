<script setup lang="ts">
import {computed} from 'vue'
import {t} from '../../../shared/i18n'
import {usePlayer} from '../../../shared/player/store'
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import type {PreviewTrack} from '../model/tracks'
import {formatHotCount,hotStat,isFavorited,isLiked,isPending,recordPlay,toggleReaction} from '../model/hot-songs'

const props=defineProps<{track:PreviewTrack;queue:PreviewTrack[]}>()
const player=usePlayer()
const playing=computed(()=>player.current?.id===props.track.id&&(player.playing||player.starting))
const stat=computed(()=>hotStat(props.track.id))
const liked=computed(()=>isLiked(props.track.id)),favorited=computed(()=>isFavorited(props.track.id))
const duration=computed(()=>{const seconds=Math.floor(props.track.durationSeconds);return `${Math.floor(seconds/60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')}`})
function play(){player.setQueue(props.queue);if(!playing.value)recordPlay(props.track.id);void player.toggle(props.track)}
</script>

<template>
  <div class="explore-track-item">
    <button type="button" class="explore-track-row listen-action" :class="{'is-playing':playing}" :aria-label="t('{action}{title}',{action:playing?t('暂停'):t('试听'),title:track.title})" :aria-pressed="playing" @click="play">
      <img :src="track.cover" alt="" width="52" height="52" loading="lazy">
      <span class="explore-track-copy"><strong :title="track.title">{{ track.title }}</strong><span>{{ track.genre||track.category }} <span aria-hidden="true">·</span> {{ duration }}</span></span>
      <span class="explore-track-play"><PlaybackIcon :playing="playing"/></span>
    </button>
    <div class="hot-song-meta">
      <span class="hot-metric"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M4 2.5 12.5 8 4 13.5Z"/></svg>{{ formatHotCount(stat.plays) }}<span class="sr-only">{{ t('播放量') }}</span></span>
      <button type="button" class="hot-metric hot-action" :class="{active:liked}" :aria-pressed="liked" :disabled="isPending(track.id,'like')" @click="toggleReaction(track.id,'like')">
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 13.2 3 8.4a2.9 2.9 0 1 1 5-3.4 2.9 2.9 0 1 1 5 3.4Z"/></svg>{{ formatHotCount(stat.likes) }}<span class="sr-only">{{ liked?t('已点赞'):t('点赞') }}</span>
      </button>
      <button type="button" class="hot-metric hot-action" :class="{active:favorited}" :aria-pressed="favorited" :disabled="isPending(track.id,'favorite')" @click="toggleReaction(track.id,'favorite')">
        <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m8 2 1.9 3.9 4.1.6-3 2.9.7 4.1L8 11.6 4.3 13.5l.7-4.1-3-2.9 4.1-.6Z"/></svg>{{ formatHotCount(stat.favorites) }}<span class="sr-only">{{ favorited?t('已收藏'):t('收藏') }}</span>
      </button>
      <span class="hot-metric"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2Z"/></svg>{{ formatHotCount(stat.comments) }}<span class="sr-only">{{ t('评论数') }}</span></span>
    </div>
  </div>
</template>
