<script setup lang="ts">
import PlaybackIcon from '../../../shared/ui/PlaybackIcon.vue'
import AudioPreparation from '../../../shared/player/AudioPreparation.vue'
import { computed } from 'vue'
import { t, defaultCover } from '../../../shared/i18n'
import { usePlayer } from '../../../shared/player/store'
import { audioUrl, type AudioAsset } from '../api/detail'
import type { Work } from '../../studio'
import SyncedLyrics from './SyncedLyrics.vue'
import type { EditHint } from '../model/section-edit'
const props=defineProps<{work:Work;asset:AudioAsset;title:string;lyrics?:string;language?:string|null;cover?:string;pending?:boolean;checking?:boolean;busy?:boolean;sectionOpen?:boolean}>()
defineEmits<{gift:[];publish:[];regenerate:[];cover:[];edit:[hint?:EditHint]}>()
const player=usePlayer()
const track=computed(()=>({id:props.asset.id,title:props.title,cover:props.cover||defaultCover(),audio:audioUrl(props.work.id,props.asset.id),provenance:props.asset.origin}))
function play(){player.setQueue([track.value],false);void player.toggle(track.value)}
async function playFrom(time:number){if(player.current?.id!==props.asset.id||!player.playing)await player.toggle({id:props.asset.id,title:props.title,cover:props.cover||defaultCover(),audio:audioUrl(props.work.id,props.asset.id),provenance:props.asset.origin});if(player.current?.id===props.asset.id)player.seek(time)}
</script>
<template><section id="song-result" class="song-result" tabindex="-1" :aria-label="t('歌曲试听')"><div class="song-result-art"><img :src="cover||defaultCover()" :alt="t('歌曲封面')"><button class="result-play" :aria-label="player.current?.id===asset.id&&(player.playing||player.starting)?t('暂停'):t('试听歌曲')" @click="play"><span aria-hidden="true"><PlaybackIcon :playing="player.current?.id===asset.id&&(player.playing||player.starting)"/></span></button><button class="cover-change" @click="$emit('cover')">{{ t('更换封面') }}</button></div><div class="song-result-copy"><span class="completion-label">{{ asset.origin==='GENERATED'?t('你的专属歌曲，完成了。'):t('你的音乐礼物，准备好了。') }}</span><h2>{{ title }}</h2><p>{{ work.recipient?t('送给 {recipient}',{recipient:work.recipient}):t('我的创作') }}</p><blockquote v-if="work.message">{{ work.message }}</blockquote><AudioPreparation :track="track"/><div class="actions"><button class="button" @click="$emit('gift')">{{ t('分享歌曲') }}</button><button class="button secondary" type="button" @click="$emit('publish')">{{ t('发布歌曲') }}</button><button class="button secondary" :disabled="busy" @click="$emit('regenerate')">{{ pending?t('查看生成进度'):checking?t('核对生成进度'):asset.origin==='GENERATED'?t('调整音乐'):t('重新制作一首') }}</button><a class="button secondary" :href="audioUrl(work.id,asset.id,true)">{{ t('下载歌曲') }}</a></div><p class="save-info">{{ work.recipient?t('先听一遍，再把这份心意送给 TA。'):t('先听一遍，继续打磨你的作品。') }}</p></div><SyncedLyrics :work-id="work.id" :asset-id="asset.id" :lyrics="lyrics" :language="language" :editable="asset.origin==='GENERATED'" :section-open="sectionOpen" @seek="playFrom" @edit="$emit('edit',$event)"/></section></template>
