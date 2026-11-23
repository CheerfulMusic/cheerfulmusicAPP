<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { platform } from '../../platform'
import { locale, setLocale, t, type Locale } from './index'
import { homeLocale, homeSeo } from './home-seo'
import { discoverPaths } from './site-seo'

const route = useRoute()
const router = useRouter()
const languages = [
  { code: 'en', label: 'EN', action: 'Switch to English' },
  { code: 'zh-CN', label: '中', action: '切换到中文' },
] as const

async function switchLanguage(value: Locale) {
  if (platform.kind === 'web' && route) {
    let target: string | undefined
    if (route.name === 'discover') target = discoverPaths[value]
    else if (route.name === 'home' && homeLocale(route.path) === 'en' && value === 'zh-CN') target = homeSeo[value].path
    if (target && target !== route.path) {
      const failure = await router.push({ path: target, query: route.query, hash: route.hash, force: true })
      if (failure) return
    }
  }
  setLocale(value)
}
</script>
<template>
  <div class="language-switch" role="group" :aria-label="t('选择语言')">
    <template v-for="(language, index) in languages" :key="language.code">
      <span v-if="index > 0" aria-hidden="true">/</span>
      <button
        type="button"
        :lang="language.code"
        :class="{ selected: locale === language.code }"
        :aria-pressed="locale === language.code"
        :aria-label="language.action"
        @click="switchLanguage(language.code)"
      >{{ language.label }}</button>
    </template>
  </div>
</template>
