<script setup lang="ts">
import { computed } from 'vue'
import { locale } from '../../../shared/i18n'
import type { AgentProgress } from '../model/progress'
const props = defineProps<{ progress: AgentProgress }>()
const label = computed(() => {
  const labels = { planning: ['正在准备', 'Preparing'], tool_started: ['开始执行', 'Started'], tool_finished: ['执行结果', 'Result'], error: ['执行未完成', 'Incomplete'] }
  return labels[props.progress.stage][locale.value === 'en' ? 1 : 0]
})
</script>

<template>
  <div class="agent-progress-card" :class="{ 'is-error': progress.stage === 'error' }">
    <small>{{ label }}</small><p>{{ progress.text }}</p>
  </div>
</template>

<style scoped>
.agent-progress-card{padding:10px 16px;border-left:3px solid #eaa68a;border-radius:8px;background:#fff7f0;color:#6d5549;overflow-wrap:anywhere;max-width:100%;box-sizing:border-box}.agent-progress-card small{color:#a45c40;font-weight:600}.agent-progress-card p{margin:4px 0 0;white-space:pre-wrap;line-height:1.6}.agent-progress-card.is-error{border-left-color:#c95345}
</style>
