<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { platform } from '../../platform'
import { locale, setLocale, t, type Locale } from './index'
import { homeLocale, homeSeo } from './home-seo'

const route = useRoute()
const router = useRouter()
const languages = [
  { code: 'zh-CN', label: '中', action: '切换到中文' },
  { code: 'en', label: 'EN', action: 'Switch to English' },
] as const

async function switchLanguage(value: Locale) {
  if (platform.kind === 'web' && route?.name === 'home' && homeLocale(route.path) === 'en' && value === 'zh-CN') {
    // The English entry is an alias of the root, so force navigation between them.
    const failure = await router.push({ path: homeSeo[value].path, query: route.query, hash: route.hash, force: true })
    if (failure) return
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
