<script setup lang="ts">
import { computed } from 'vue'
import { t } from '../../../shared/i18n'
import { analyticsPageSize } from '../model/pagination'

const props = defineProps<{ total: number; label: string }>()
const page = defineModel<number>({ required: true })
const pages = computed(() => Math.ceil(props.total / analyticsPageSize))
</script>

<template>
  <nav v-if="pages > 1" class="pagination" :aria-label="label">
    <button type="button" :disabled="page === 1" @click="page--">{{ t('上一页') }}</button>
    <span aria-live="polite">{{ t('第 {page} / {pages} 页 · 共 {total} 条', { page, pages, total }) }}</span>
    <button type="button" :disabled="page >= pages" @click="page++">{{ t('下一页') }}</button>
  </nav>
</template>

<style scoped>
.pagination { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 10px; margin-top: 12px; color: var(--a-muted); font-size: 12px; }
button { padding: 6px 12px; border: 1px solid var(--a-line); border-radius: 999px; background: white; color: inherit; cursor: pointer; }
button:disabled { opacity: .45; cursor: default; }
</style>
