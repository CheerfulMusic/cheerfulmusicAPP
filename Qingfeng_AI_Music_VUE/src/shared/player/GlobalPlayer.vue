<script setup lang="ts">
import PlaybackIcon from '../ui/PlaybackIcon.vue'
import { t, localizedCover } from '../i18n/index'
import { usePlayer } from './store'
import { ref, watch, onBeforeUnmount } from 'vue'
const player = usePlayer()
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
const provenance = () => player.current?.provenance === 'IMPORTED' ? '导入音频 · 非AI生成' : player.current?.provenance === 'GENERATED' ? 'AI生成作品' : '示例歌曲试听'
function status(){
  if(player.error)return t(player.error)
  return t(player.starting?'正在连接音频，马上开始…':player.buffering?'正在缓冲…':provenance())
}
</script>
<template><aside ref="element" v-if="player.current" class="q-player" :aria-label="t('试听播放器')"><img :src="localizedCover(player.current.cover)" :alt="t('歌曲封面')"><div class="q-player-info"><strong>{{ player.current.titleKey ? t(player.current.titleKey) : player.current.title }}</strong><span>{{ status() }}</span></div><button class="q-player-toggle" :aria-label="(player.playing || player.starting) ? t('暂停') : t('播放')" @click="player.toggle(player.current)"><PlaybackIcon :playing="player.playing||player.starting"/></button><button v-if="player.hasQueue" class="q-player-step" :aria-label="t('上一首')" @click="player.next(-1)">‹</button><button v-if="player.hasQueue" class="q-player-step" :aria-label="t('下一首')" @click="player.next()">›</button><button v-if="player.error" class="q-player-retry" @click="player.toggle(player.current)">{{ t('重试') }}</button><span>{{ label(player.time) }}</span><input type="range" min="0" :max="player.duration || 1" :disabled="!player.duration" step="0.1" :value="player.time" :aria-label="t('播放进度')" @input="player.seek(Number(($event.target as HTMLInputElement).value))"><span>{{ label(player.duration) }}</span><button class="q-player-close" :aria-label="t('关闭播放器')" @click="player.close">×</button></aside></template>
