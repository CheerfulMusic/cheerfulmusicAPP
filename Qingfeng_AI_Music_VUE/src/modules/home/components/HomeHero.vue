<script setup lang="ts">
import { t, locale } from '../../../shared/i18n'
import HomeBrandScene from './HomeBrandScene.vue'
</script>

<template>
  <section class="p-hero p-width home-creator-hero" aria-labelledby="home-hero-title">
    <div class="p-intro">
      <h1 id="home-hero-title" class="bilingual-headline" :class="{ 'chinese-creator-headline': locale !== 'en' }">
        <template v-if="locale !== 'en'">
          <span class="creator-main-title" lang="zh-CN"><img class="creator-title-art" src="/brand/headline-own-song-regular.svg" alt="" aria-hidden="true"><span class="creator-title-text">做自己的歌</span></span>
          <span class="creator-singer-tagline" lang="zh-CN"><span>人人</span><strong>都是歌手</strong></span>
          <span class="creator-singer-english" lang="en">Everyone Can Be a Singer</span>
        </template>
        <span v-else lang="en">Everyone Can Be a Singer</span>
      </h1>
      <p class="p-intro-description bilingual-description">
        <span v-if="locale !== 'en'" lang="zh-CN">无需音乐基础 简单几步 让你的想法变成一首完整的歌</span>
        <span v-else lang="en">Create your own song in just a few clicks No music skills needed</span>
        <span v-if="locale !== 'en'" class="description-translation" lang="en"><span>Create your own song in just a few clicks</span> <span>No music skills needed</span></span>
      </p>
      <div class="p-hero-actions">
        <RouterLink class="p-button" to="/create">{{ t('立即生成我的歌') }} <span aria-hidden="true">↗</span></RouterLink>
      </div>
      <p class="p-reassurance">{{ t('写下灵感 选择风格 唱出属于你的声音') }}</p>
    </div>
    <HomeBrandScene/>
    <ul class="hero-capabilities">
      <li><span class="capability-icon"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="m15 2-11 14h8l-1 12 12-16h-8z"/></svg></span><span>{{ t('写下灵感') }}</span></li>
      <li><span class="capability-icon"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M10 23V6l16-4v17M10 10l16-4"/><ellipse cx="6" cy="23" rx="4" ry="3"/><ellipse cx="22" cy="19" rx="4" ry="3"/></svg></span><span>{{ t('选择风格') }}</span></li>
      <li><span class="capability-icon"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="m5 23 2-7L21 2l7 7-14 14-9 2zM18 5l7 7M4 29h24"/></svg></span><span>{{ t('歌词可修改') }}</span></li>
      <li><span class="capability-icon"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M8 15v13h19V15M18 22V2m-6 6 6-6 6 6"/></svg></span><span>{{ t('保存分享') }}</span></li>
    </ul>
  </section>
</template>
