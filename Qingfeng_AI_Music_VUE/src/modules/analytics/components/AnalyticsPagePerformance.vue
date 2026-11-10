<script setup lang="ts">
import { computed } from 'vue'
import type { AnalyticsPageRow, AnalyticsPageUserRow } from '../api/analytics'
import { dwellLabel, pageName, shareOf } from '../model/dwell'
import AnalyticsPageUsers from './AnalyticsPageUsers.vue'

const props = defineProps<{
  rows: AnalyticsPageRow[]
  openPath?: string
  detailRows?: AnalyticsPageUserRow[]
  detailLoading?: boolean
  detailError?: string
}>()
defineEmits<{ toggle: [pagePath: string] }>()

const totalPv = computed(() => props.rows.reduce((sum, row) => sum + row.pv, 0))

function percent(value: number): string {
  return `${Math.round(shareOf(value, totalPv.value) * 100)}%`
}
</script>

<template>
  <div class="perf">
    <div v-if="!rows.length" class="perf-empty">所选时间范围内还没有页面访问。</div>
    <template v-else>
      <div class="perf-head" aria-hidden="true">
        <span class="col-rank">#</span>
        <span class="col-page">页面</span>
        <span class="col-pv">访问量</span>
        <span class="col-share">占比</span>
        <span class="col-dwell">平均停留</span>
        <span class="col-samples">样本</span>
        <span class="col-toggle"></span>
      </div>
      <ul class="perf-list">
        <li v-for="(row, index) in rows" :key="row.pagePath" :class="{ open: openPath === row.pagePath }">
          <button type="button" class="perf-row" :title="`${pageName(row.pagePath)} ${row.pagePath || '/'} · PV ${row.pv.toLocaleString('zh-CN')} · 平均停留 ${dwellLabel(row.avgDurationMs, row.samples)} · 有效样本 ${row.samples}`" :aria-expanded="openPath === row.pagePath" :aria-controls="`perf-users-${index}`" @click="$emit('toggle', row.pagePath)">
            <span class="col-rank">{{ index + 1 }}</span>
            <span class="col-page">
              <strong>{{ pageName(row.pagePath) }}</strong>
              <code>{{ row.pagePath || '/' }}</code>
            </span>
            <span class="col-pv">{{ row.pv.toLocaleString('zh-CN') }}</span>
            <span class="col-share">
              <span class="share-track"><i :style="{ width: `${shareOf(row.pv, totalPv) * 100}%` }"></i></span>
              <small>{{ percent(row.pv) }}</small>
            </span>
            <span class="col-dwell" :title="row.samples ? `基于 ${row.samples} 次有效停留` : '还没有有效停留样本'">{{ dwellLabel(row.avgDurationMs, row.samples) }}</span>
            <span class="col-samples">{{ row.samples || '—' }}</span>
            <span class="col-toggle" aria-hidden="true"><i></i></span>
          </button>
          <AnalyticsPageUsers v-if="openPath === row.pagePath" :id="`perf-users-${index}`" :rows="detailRows ?? []" :loading="detailLoading" :error="detailError" />
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.perf { margin-top: 4px; }
.perf-empty { padding: 40px 0; text-align: center; color: #9a8b84; font-size: 12px; }
.perf-head, .perf-row {
  display: grid; align-items: center; gap: 14px;
  grid-template-columns: 28px minmax(150px, 2.1fr) 84px minmax(120px, 1.5fr) 104px 56px 20px;
}
.perf-head { padding: 0 12px 9px; color: #a2948e; font-size: 11px; border-bottom: 1px solid #f3e9e5; }
.perf-list { list-style: none; margin: 0; padding: 0; }
.perf-list > li { border-bottom: 1px solid #f7efec; }
.perf-list > li:last-child { border-bottom: 0; }
.perf-row {
  width: 100%; padding: 12px; border: 0; border-radius: 12px; background: none;
  color: inherit; font: inherit; text-align: left; cursor: pointer; transition: background .15s ease;
}
.perf-row:hover { background: #fff7f4; }
.perf-row:focus-visible { outline: 2px solid #d4483b; outline-offset: -2px; }
.perf-list > li.open .perf-row { background: #fff5f1; }
.col-rank { color: #b6a8a1; font-size: 12px; font-variant-numeric: tabular-nums; }
.col-page { min-width: 0; }
.col-page strong { display: block; font-size: 13.5px; font-weight: 600; color: #2c2522; }
.col-page code { display: block; margin-top: 2px; color: #a2948e; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col-pv { text-align: right; font-size: 14px; font-weight: 600; color: #2c2522; font-variant-numeric: tabular-nums; }
.col-share { display: flex; align-items: center; gap: 8px; }
.share-track { flex: 1; height: 7px; border-radius: 999px; background: #f6ebe6; overflow: hidden; }
.share-track i { display: block; height: 100%; border-radius: 999px; background: #d4483b; opacity: .85; }
.col-share small { flex: none; width: 34px; text-align: right; color: #8a7c76; font-size: 11px; font-variant-numeric: tabular-nums; }
.col-dwell { text-align: right; color: #5f534e; font-size: 12.5px; font-variant-numeric: tabular-nums; }
.col-samples { text-align: right; color: #a2948e; font-size: 11.5px; font-variant-numeric: tabular-nums; }
.col-toggle { display: grid; place-items: center; }
.col-toggle i { width: 0; height: 0; border-left: 5px solid #c3b4ad; border-top: 4px solid transparent; border-bottom: 4px solid transparent; transition: transform .15s ease; }
.perf-list > li.open .col-toggle i { transform: rotate(90deg); }
@media (max-width: 900px) {
  .perf-head, .perf-row { grid-template-columns: 24px minmax(120px, 2fr) 64px minmax(70px, 1fr) 82px 18px; }
  .col-samples { display: none; }
}
@media (max-width: 620px) {
  .perf-head { display: none; }
  .perf-row { grid-template-columns: 20px minmax(90px, 1fr) 52px 16px; gap: 10px; padding: 11px 6px; }
  .col-share, .col-dwell { display: none; }
  .col-page code { font-size: 10px; }
}
</style>
