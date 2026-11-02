<script setup lang="ts">
import { locale, setLocale, t } from './index'
</script>
<template>
  <div class="language-switch" role="group" :aria-label="t('选择语言')">
    <button type="button" lang="zh-CN" :class="{selected:locale==='zh-CN'}" :aria-pressed="locale==='zh-CN'" aria-label="切换到中文" @click="setLocale('zh-CN')">中</button>
    <span aria-hidden="true">/</span>
    <button type="button" lang="en" :class="{selected:locale==='en'}" :aria-pressed="locale==='en'" aria-label="Switch to English" @click="setLocale('en')">EN</button>
  </div>
</template>
