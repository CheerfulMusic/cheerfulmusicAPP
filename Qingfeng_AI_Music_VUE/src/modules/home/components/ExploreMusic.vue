<script setup lang="ts">
import {computed,ref,nextTick} from 'vue'
import {t,locale} from '../../../shared/i18n'
import {usePreviewTracks} from '../model/tracks'
import SongCard from './SongCard.vue'
const tracks=usePreviewTracks(),filter=ref('all'),more=ref(false),gallery=ref<HTMLElement>()
const visible=computed(()=>filter.value==='all'?tracks.value:tracks.value.filter(track=>track.id===filter.value))
const styles=['抒情流行','梦幻电子','独立摇滚','温暖民谣','R&B节奏蓝调','说唱嘻哈','轻柔爵士','国风古韵']
async function choose(id:string){filter.value=id;await nextTick();gallery.value?.scrollTo({left:0,behavior:'instant'})}
</script>
<template><section id="inspiration" class="p-width explore-music">
  <header class="explore-heading"><p class="explore-label">{{locale==='en'?'Explore Music':'试听作品 / Explore Music'}}</p><h2>{{t('听听这些歌，你也能创作。')}}</h2><p v-if="locale!=='en'" class="explore-heading-en" lang="en">Discover What You Can Create.</p><p class="explore-description">{{t('无需音乐基础，选择你喜欢的风格，下一首就由你创作。')}}</p><p v-if="locale!=='en'" class="explore-description-en" lang="en">Explore different music styles. Your next song is just a few clicks away.</p></header>
  <div class="explore-toolbar"><nav class="genre-tabs" :aria-label="t('筛选试听作品')"><button type="button" :class="{selected:filter==='all'}" :aria-pressed="filter==='all'" @click="choose('all')">{{t('全部')}}</button><button v-for="track in tracks" :key="track.id" type="button" :class="{selected:filter===track.id}" :aria-pressed="filter===track.id" @click="choose(track.id)">{{track.category}}</button><button type="button" :aria-expanded="more" aria-controls="explore-style-links" @click="more=!more">{{t(more?'收起风格':'更多风格')}} <span aria-hidden="true">{{more?'−':'+'}}</span></button></nav><p class="swipe-hint">{{t('左右滑动，探索更多作品')}} <span aria-hidden="true">↔</span></p></div>
  <div v-if="more" id="explore-style-links" class="explore-style-links"><p>{{t('选择一种风格，开始创作。')}}</p><nav :aria-label="t('选择创作风格')"><RouterLink v-for="style in styles" :key="style" :to="{path:'/create',query:{style}}">{{t(style)}} <span aria-hidden="true">↗</span></RouterLink></nav></div>
  <div ref="gallery" class="explore-gallery" tabindex="0" :aria-label="t('试听作品列表')"><SongCard v-for="track in visible" :key="track.id" :track="track"/></div>
  <footer class="explore-footer"><div><h3>{{t('下一首，听见你的创作。')}}</h3><p>{{t('从喜欢的风格开始，找到属于你的声音。')}}</p></div><RouterLink class="p-button" to="/create">{{t('立即生成我的歌')}} <span aria-hidden="true">↗</span></RouterLink></footer>
</section></template>
