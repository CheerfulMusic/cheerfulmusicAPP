<script setup lang="ts">
import { t } from '../../shared/i18n/index'
import BrandLogo from '../../shared/ui/BrandLogo.vue'
import LanguageSwitcher from '../../shared/i18n/LanguageSwitcher.vue'
</script>
<template>
  <header class="app-header"><RouterLink class="brand" to="/"><BrandLogo compact/></RouterLink><LanguageSwitcher/></header>
  <main class="page app-page"><slot /></main>
  <nav class="app-tabbar" :aria-label="t('App导航')"><RouterLink to="/">{{ t('首页') }}</RouterLink><RouterLink to="/create">{{ t('创作') }}</RouterLink><RouterLink to="/discover">{{ t('发现') }}</RouterLink><RouterLink to="/works">{{ t('作品') }}</RouterLink><RouterLink to="/account">{{ t('我的') }}</RouterLink></nav>
</template>
