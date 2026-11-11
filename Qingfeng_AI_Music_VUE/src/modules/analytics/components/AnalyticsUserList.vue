<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AnalyticsUserPageRow, AnalyticsUserRow } from '../api/analytics'
import { accountDetail, accountName, accountTypeLabel, avatarText, dwellLabel, filterUsers, isGuest, pageName, type UserFilter } from '../model/dwell'

const props = defineProps<{
  rows: AnalyticsUserRow[]
  openUser?: string
  detailRows?: AnalyticsUserPageRow[]
  detailLoading?: boolean
  detailError?: string
}>()
defineEmits<{ toggle: [creatorId: string] }>()

const filter = ref<UserFilter>('all')
const visible = computed(() => filterUsers(props.rows, filter.value))
const guests = computed(() => props.rows.filter(row => isGuest(row.accountType)).length)
const signed = computed(() => props.rows.length - guests.value)

function visits(row: AnalyticsUserRow): string { return Number.isFinite(row.pageviews) ? row.pageviews.toLocaleString('zh-CN') : '—' }
function works(row: AnalyticsUserRow): string { return Number.isFinite(row.works) ? row.works!.toLocaleString('zh-CN') : '—' }
function time(value: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
</script>

<template>
  <div class="users">
    <div class="users-toolbar">
      <p class="users-summary">{{ signed }} 位登录用户 · {{ guests }} 位游客</p>
      <div class="users-filter" role="group" aria-label="按身份筛选用户">
        <button v-for="option in [{ id: 'all', label: '全部' }, { id: 'signed', label: '仅登录' }, { id: 'guest', label: '仅游客' }]" :key="option.id" type="button" :class="{ active: filter === option.id }" :aria-pressed="filter === option.id" @click="filter = option.id as UserFilter">{{ option.label }}</button>
      </div>
    </div>

    <div v-if="!rows.length" class="users-empty">所选时间范围内暂无用户数据。</div>
    <div v-else-if="!visible.length" class="users-empty">当前筛选下没有用户。</div>
    <ul v-else class="users-list">
      <li v-for="row in visible" :key="row.creatorId" :class="{ open: openUser === row.creatorId }">
        <button type="button" class="users-row" :aria-expanded="openUser === row.creatorId" :aria-controls="`user-pages-${row.creatorId}`" @click="$emit('toggle', row.creatorId)">
          <span class="avatar" :class="{ guest: isGuest(row.accountType) }" aria-hidden="true">{{ avatarText(row) }}</span>
          <span class="user-main">
            <strong>{{ accountName(row) }}</strong>
            <span v-if="accountDetail(row)" class="user-sub">{{ accountDetail(row) }}</span>
            <code class="user-id" :title="row.creatorId">{{ row.creatorId }}</code>
          </span>
          <span class="type-badge" :class="{ guest: isGuest(row.accountType) }">{{ accountTypeLabel(row.accountType) }}</span>
          <span class="metric"><strong>{{ works(row) }}</strong><small>创作</small></span>
          <span class="metric"><strong>{{ visits(row) }}</strong><small>访问</small></span>
          <span class="metric"><strong>{{ dwellLabel(row.avgDurationMs, row.samples) }}</strong><small>{{ row.samples ? `${row.samples} 次停留` : '无有效样本' }}</small></span>
          <span class="last-seen">{{ time(row.lastSeen) }}</span>
          <span class="caret" aria-hidden="true"></span>
        </button>
        <div v-if="openUser === row.creatorId" :id="`user-pages-${row.creatorId}`" class="user-detail">
          <p v-if="detailLoading" class="user-detail-state">正在读取页面明细…</p>
          <p v-else-if="detailError" class="user-detail-state error" role="alert">{{ detailError }}</p>
          <p v-else-if="!(detailRows ?? []).length" class="user-detail-state">这位用户在当前时间范围内没有有效停留记录。</p>
          <ul v-else class="user-pages">
            <li v-for="page in detailRows" :key="page.pagePath">
              <span class="user-page-name">{{ pageName(page.pagePath) }} <code>{{ page.pagePath }}</code></span>
              <span class="user-page-metric">{{ dwellLabel(page.avgDurationMs, page.samples) }}</span>
              <span class="user-page-samples">{{ page.samples }} 次</span>
            </li>
          </ul>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.users-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.users-summary { margin: 0; color: #8a7c76; font-size: 12px; }
.users-filter { display: inline-flex; gap: 4px; padding: 3px; border: 1px solid #f0e1dc; border-radius: 999px; background: #fffaf8; }
.users-filter button { min-width: 54px; padding: 6px 12px; border: 0; border-radius: 999px; background: transparent; color: #8a7c76; font-size: 12px; cursor: pointer; }
.users-filter button.active { background: #d4483b; color: #fff; }
.users-empty { padding: 40px 0; text-align: center; color: #9a8b84; font-size: 12px; }
.users-list { list-style: none; margin: 0; padding: 0; }
.users-list > li { border-bottom: 1px solid #f7efec; }
.users-list > li:last-child { border-bottom: 0; }
.users-row {
  display: grid; align-items: center; gap: 14px; width: 100%; padding: 12px;
  grid-template-columns: 38px minmax(150px, 1.9fr) 82px 70px 84px 104px 108px 18px;
  border: 0; border-radius: 12px; background: none; color: inherit; font: inherit; text-align: left; cursor: pointer;
}
.users-row:hover { background: #fff7f4; }
.users-row:focus-visible { outline: 2px solid #d4483b; outline-offset: -2px; }
.users-list > li.open .users-row { background: #fff5f1; }
.avatar { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; background: #fdece8; color: #c0453a; font-size: 14px; font-weight: 600; }
.avatar.guest { background: #f4efe9; color: #96796a; }
.user-main { min-width: 0; display: grid; gap: 2px; }
.user-main strong { font-size: 13.5px; font-weight: 600; color: #2c2522; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-sub { color: #8a7c76; font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-id { color: #b3a59e; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.type-badge { justify-self: start; padding: 2px 9px; border-radius: 999px; background: #eef1f6; color: #4b5a6b; font-size: 11px; white-space: nowrap; }
.type-badge.guest { background: #f6ece6; color: #92684a; }
.metric { display: grid; gap: 1px; text-align: right; }
.metric strong { font-size: 12.5px; font-weight: 600; color: #3c332f; font-variant-numeric: tabular-nums; }
.metric small { color: #a2948e; font-size: 10.5px; }
.last-seen { text-align: right; color: #8a7c76; font-size: 11.5px; white-space: nowrap; }
.caret { display: grid; place-items: center; }
.caret::before { content: ''; width: 0; height: 0; border-left: 5px solid #c3b4ad; border-top: 4px solid transparent; border-bottom: 4px solid transparent; transition: transform .15s ease; }
.users-list > li.open .caret::before { transform: rotate(90deg); }
.user-detail { margin: 0 0 12px 50px; padding: 10px 14px; border-radius: 12px; background: #fbf6f3; border: 1px solid #f0e5e0; }
.user-detail-state { margin: 0; color: #8a7c76; font-size: 12px; }
.user-detail-state.error { color: #a0332a; }
.user-pages { list-style: none; margin: 0; padding: 0; display: grid; gap: 6px; }
.user-pages li { display: grid; grid-template-columns: minmax(120px, 1fr) 96px 56px; align-items: center; gap: 10px; font-size: 12px; }
.user-page-name { color: #3c332f; }
.user-page-name code { margin-left: 6px; color: #a2948e; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; }
.user-page-metric { text-align: right; color: #5f534e; font-variant-numeric: tabular-nums; }
.user-page-samples { text-align: right; color: #a2948e; font-variant-numeric: tabular-nums; }
@media (max-width: 900px) {
  .users-row { grid-template-columns: 34px minmax(120px, 1.8fr) 72px 58px 66px 84px 16px; gap: 10px; }
  .last-seen { display: none; }
}
@media (max-width: 620px) {
  .users-row { grid-template-columns: 32px minmax(90px, 1fr) 16px; }
  .type-badge, .metric { display: none; }
  .user-detail { margin-left: 0; }
}
</style>
