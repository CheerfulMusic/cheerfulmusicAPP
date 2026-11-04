<script setup lang="ts">
import {computed} from 'vue'
import {usePlayer,type PlayableTrack} from './store'
import {t} from '../i18n'
const props=defineProps<{track:PlayableTrack}>(),player=usePlayer()
const active=computed(()=>player.current?.id===props.track.id&&player.current?.audio===props.track.audio)
const waiting=computed(()=>active.value&&(player.starting||player.buffering))
const message=computed(()=>!active.value?'点击试听，边加载边播放。':player.error?player.error:waiting.value?'正在连接音频，马上开始…':player.playing?'正在播放，边听边加载。':'已暂停，点击继续试听。')
</script>
<template><div class="audio-preparation" :class="{error:active&&player.error}" role="status" :aria-busy="waiting"><div class="audio-preparation-label"><span>{{t(message)}}</span><button v-if="active&&player.error" class="text-button" @click="player.toggle(track)">{{t('重试播放')}}</button></div></div></template>
