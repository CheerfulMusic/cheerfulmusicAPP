import { computed, ref, watch, type Ref } from 'vue'

export const analyticsPageSize = 20

export function useAnalyticsPagination<T>(rows: Ref<readonly T[]>) {
  const page = ref(1)
  watch(rows, () => { page.value = 1 })
  const displayed = computed(() => {
    const start = (page.value - 1) * analyticsPageSize
    return rows.value.slice(start, start + analyticsPageSize)
  })
  return { page, displayed }
}
