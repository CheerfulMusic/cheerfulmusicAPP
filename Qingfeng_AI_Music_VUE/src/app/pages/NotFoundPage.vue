<script setup lang="ts">
import { t } from '../../shared/i18n'
</script>
<template><h1>{{ t("这个页面还没有找到。") }}</h1><p>{{ t("请检查访问地址，或者返回首页。") }}</p><RouterLink class="button" to="/">{{ t("返回首页") }}</RouterLink></template>
