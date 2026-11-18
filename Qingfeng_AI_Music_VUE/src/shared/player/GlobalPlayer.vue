<script setup lang="ts">
import PlaybackIcon from '../ui/PlaybackIcon.vue'
import { t, localizedCover } from '../i18n/index'
import { usePlayer } from './store'
import { useCurrentLyrics } from './current-lyrics'
import { ref, watch, onBeforeUnmount } from 'vue'
const player = usePlayer()
const { line } = useCurrentLyrics()
const element=ref<HTMLElement>()
let observer:ResizeObserver|undefined
watch(element,value=>{
  observer?.disconnect()
  const resize=()=>document.documentElement.style.setProperty('--player-clearance',value?`${Math.ceil(value.getBoundingClientRect().height)+24}px`:'0px')
  resize()
  if(value&&typeof ResizeObserver!=='undefined'){observer=new ResizeObserver(resize);observer.observe(value)}
},{flush:'post'})
onBeforeUnmount(()=>{observer?.disconnect();document.documentElement.style.removeProperty('--player-clearance')})
const label = (value: number) => `${Math.floor((value || 0) / 60).toString().padStart(2, '0')}:${Math.floor((value || 0) % 60).toString().padStart(2, '0')}`
function status(){
  if(player.error)return t(player.error)
  if(player.starting)return t('正在连接音频，马上开始…')
  if(player.buffering)return t('正在缓冲…')
  return line.value.literal ? line.value.text : t(line.value.text)
}
</script>
<template>
  <aside ref="element" v-if="player.current" class="q-player" :aria-label="t('试听播放器')">
    <img :src="localizedCover(player.current.cover)" :alt="t('歌曲封面')">
    <div class="q-player-info"><strong>{{ player.current.titleKey ? t(player.current.titleKey) : player.current.title }}</strong><span class="q-player-lyric" :title="status()">{{ status() }}</span></div>
    <button class="q-player-toggle" :aria-label="(player.playing || player.starting) ? t('暂停') : t('播放')" @click="player.toggle(player.current)"><PlaybackIcon :playing="player.playing||player.starting"/></button>
    <button v-if="player.hasQueue" class="q-player-step" :aria-label="t('上一首')" @click="player.next(-1)">‹</button>
    <button v-if="player.hasQueue" class="q-player-step" :aria-label="t('下一首')" @click="player.next()">›</button>
    <button v-if="player.error" class="q-player-retry" @click="player.toggle(player.current)">{{ t('重试') }}</button>
    <span>{{ label(player.time) }}</span>
    <input class="q-player-progress" type="range" min="0" :max="player.duration || 1" :disabled="!player.duration" step="0.1" :value="player.time" :aria-label="t('播放进度')" @input="player.seek(Number(($event.target as HTMLInputElement).value))">
    <span>{{ label(player.duration) }}</span>
    <div class="q-player-volume"><button type="button" class="q-player-mute" :aria-label="t(player.muted||player.volume===0?'取消静音':'静音')" :aria-pressed="player.muted||player.volume===0" @click="player.toggleMute"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><path d="M11 4 5 9H2v6h3l6 5V4Z"/><path v-if="!player.muted&&player.volume>0" d="M15 8a6 6 0 0 1 0 8m3-11a10 10 0 0 1 0 14"/><path v-else d="m16 9 6 6m0-6-6 6"/></svg></button><input type="range" min="0" max="1" step="0.01" :value="player.muted?0:player.volume" :aria-label="t('音量')" :aria-valuetext="`${Math.round((player.muted?0:player.volume)*100)}%`" @input="player.setVolume(Number(($event.target as HTMLInputElement).value))"></div>
    <button class="q-player-close" :aria-label="t('关闭播放器')" @click="player.close">×</button></aside>
</template>
