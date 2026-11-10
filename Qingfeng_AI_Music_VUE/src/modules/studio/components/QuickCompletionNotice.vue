<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { currentCreatorId } from '../../../shared/http/api-client'
import { t } from '../../../shared/i18n'
import { getQuickCreation, latestQuickCreation } from '../api/quick-creation'
import { backgroundQuick } from '../model/background-quick'

const route = useRoute()
const viewing = (workId: string) => route.query.work === workId || route.params.id === workId
const completed = computed(() => [...backgroundQuick.values()].find(task => task.owner === currentCreatorId() && task.status === 'SUCCEEDED' && !viewing(task.workId)))
let timer: number | undefined
let polling = false
let active = true

async function refresh() {
  if (polling || document.hidden) return
  const task = [...backgroundQuick.values()].find(item => item.status !== 'SUCCEEDED' && !viewing(item.workId))
  if (!task) return
  if (task.owner !== currentCreatorId()) { backgroundQuick.delete(task.workId); return }
  polling = true
  try {
    const result = task.flowId ? await getQuickCreation(task.workId, task.flowId) : await latestQuickCreation(task.workId)
    if (!active || currentCreatorId() !== task.owner || backgroundQuick.get(task.workId) !== task || !result) return
    if (result.status === 'FAILED' || result.status === 'CANCELLED' || result.errorCode === 'LYRICS_SUBMISSION_UNKNOWN') backgroundQuick.delete(task.workId)
    else backgroundQuick.set(task.workId, { ...task, flowId: result.id, status: result.status })
  } catch { /* The original job can still be checked in My Music. */ }
  finally { polling = false }
}

function clear() { backgroundQuick.clear() }
watch(() => route.params.id, id => { if (typeof id === 'string') backgroundQuick.delete(id) })
onMounted(() => {
  timer = window.setInterval(refresh, 8000)
  window.addEventListener('qingfeng:identity-changed', clear)
})
onBeforeUnmount(() => {
  active = false
  window.clearInterval(timer)
  window.removeEventListener('qingfeng:identity-changed', clear)
})
</script>

<template>
  <aside v-if="completed" class="quick-completion-notice" role="status">
    <span>{{ t('你的歌曲已完成') }}</span>
    <RouterLink :to="{ path: `/works/${completed.workId}`, query: { quick: completed.flowId, mode: 'quick', language: completed.language } }" @click="backgroundQuick.delete(completed.workId)">{{ t('试听歌曲') }}</RouterLink>
    <button type="button" :aria-label="t('关闭生成进度弹窗')" @click="backgroundQuick.delete(completed.workId)">×</button>
  </aside>
</template>

<style scoped>
.quick-completion-notice {
  position: fixed; inset-inline-end: max(12px, env(safe-area-inset-right));
  inset-block-start: calc(84px + env(safe-area-inset-top)); z-index: 60;
  display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px;
  max-width: calc(100% - 24px); padding: 10px 14px;
  border: 1px solid var(--line); border-radius: 16px;
  background: var(--surface); color: var(--ink); box-shadow: 0 8px 24px #0002;
}
.quick-completion-notice a { color: var(--accent-text); }
.quick-completion-notice button { min-width: 34px; min-height: 34px; color: var(--muted); }
</style>
