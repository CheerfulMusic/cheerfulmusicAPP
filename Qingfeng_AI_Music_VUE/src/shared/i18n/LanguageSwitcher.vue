<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import { platform } from '../../platform'
import { locale, setLocale, t, type Locale } from './index'
import { homeSeo } from './home-seo'

const route = useRoute()
const languages = [
  { code: 'zh-CN', label: '中', action: '切换到中文' },
  { code: 'en', label: 'EN', action: 'Switch to English' },
] as const

function languageHome(value: Locale) {
  const path = homeSeo[value].path
  // The localized URLs are aliases of the same route.
  return { path, query: route.query, hash: route.hash, force: route.path !== path }
}
</script>
<template>
  <div class="language-switch" role="group" :aria-label="t('选择语言')">
    <template v-for="(language, index) in languages" :key="language.code">
      <span v-if="index > 0" aria-hidden="true">/</span>
      <RouterLink v-if="platform.kind === 'web' && route?.name === 'home'" :to="languageHome(language.code)" custom v-slot="{ href, navigate }">
        <a
          :href="href"
          :lang="language.code"
          :hreflang="language.code"
          :class="{ selected: locale === language.code }"
          :aria-current="route.path === homeSeo[language.code].path ? 'page' : undefined"
          :aria-label="language.action"
          @click="navigate"
        >{{ language.label }}</a>
      </RouterLink>
      <button
        v-else
        type="button"
        :lang="language.code"
        :class="{ selected: locale === language.code }"
        :aria-pressed="locale === language.code"
        :aria-label="language.action"
        @click="setLocale(language.code)"
      >{{ language.label }}</button>
    </template>
  </div>
</template>
