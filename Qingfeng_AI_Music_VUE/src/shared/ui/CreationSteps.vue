<script setup lang="ts">
import { t } from '../i18n'
defineProps<{ current: number }>()
const stepCount = 5
</script>
<template>
  <ol class="creation-steps progress-dots" :aria-label="t('制作进度')">
    <li v-for="step in stepCount" :key="step" :class="{ current: current === step - 1, done: current > step - 1 }" :aria-current="current === step - 1 ? 'step' : undefined" :aria-label="t('第{step}步 共{total}步', { step, total: stepCount })">
      <span aria-hidden="true"></span>
    </li>
  </ol>
</template>

<style scoped>
.creation-steps.progress-dots { width:100%; max-width:none; justify-content:center; align-items:center; gap:16px; padding:10px 0; margin:0 0 28px; list-style:none; }
.creation-steps.progress-dots li { display:flex; flex:0 0 16px; align-items:center; justify-content:center; min-height:18px; }
.creation-steps.progress-dots li::after { content:none; }
.creation-steps.progress-dots li > span { display:block; width:10px; height:10px; border:0; border-radius:50%; background:#cdbbc4; }
.creation-steps.progress-dots li.done > span { background:#ff9587; opacity:1; }
.creation-steps.progress-dots li.current > span { width:14px; height:14px; background:#f93822; opacity:1; }
@media(max-width:600px) { .creation-steps.progress-dots { gap:16px; margin-bottom:22px; } }
</style>
