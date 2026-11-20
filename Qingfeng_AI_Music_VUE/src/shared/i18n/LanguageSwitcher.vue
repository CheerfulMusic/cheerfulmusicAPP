<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { platform } from '../../platform'
import { locale, setLocale, t, type Locale } from './index'
import { homeSeo } from './home-seo'

const route = useRoute()
const router = useRouter()

function switchLocale(value: Locale) {
  if (platform.kind === 'web' && route?.name === 'home') {
    const path = homeSeo[value].path
    if (route.path !== path) {
      // Vue Router treats aliases of the same route as duplicate navigation.
      void router.push({ path, query: route.query, hash: route.hash, force: true })
    }
  } else {
    setLocale(value)
  }
}
</script>
<template>
  <div class="language-switch" role="group" :aria-label="t('选择语言')">
    <button type="button" lang="zh-CN" :class="{selected:locale==='zh-CN'}" :aria-pressed="locale==='zh-CN'" aria-label="切换到中文" @click="switchLocale('zh-CN')">中</button>
    <span aria-hidden="true">/</span>
    <button type="button" lang="en" :class="{selected:locale==='en'}" :aria-pressed="locale==='en'" aria-label="Switch to English" @click="switchLocale('en')">EN</button>
  </div>
</template>
