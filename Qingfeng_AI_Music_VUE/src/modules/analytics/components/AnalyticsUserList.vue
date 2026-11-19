<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { locale, t } from '../../../shared/i18n'
import AnalyticsPagination from './AnalyticsPagination.vue'
import { useAnalyticsPagination } from '../model/pagination'
import { analyticsPhoneUsers, type AnalyticsPhoneUser, type AnalyticsUserPageRow, type AnalyticsUserRow, type AnalyticsUserSort } from '../api/analytics'
import { accountDetail, accountName, accountTypeLabel, avatarText, dwellLabel, filterUsers, isGuest, pageName, type UserFilter } from '../model/dwell'

const props = defineProps<{
  rows: AnalyticsUserRow[]
  sorting?: boolean
  sortError?: string
  openUser?: string
  detailRows?: AnalyticsUserPageRow[]
  detailLoading?: boolean
  detailError?: string
}>()
const emit = defineEmits<{ toggle: [creatorId: string]; retrySort: []; denied: [] }>()

const filter = defineModel<UserFilter>('filter', { default: 'all' })
const sort = defineModel<AnalyticsUserSort>('sort', { default: 'active' })
const guests = computed(() => props.rows.filter(row => isGuest(row.accountType)).length)
const registeredWithoutVisits = computed(() => props.rows.filter(row => row.noActivity).length)
const signed = computed(() => props.rows.length - guests.value - registeredWithoutVisits.value)

function visits(row: AnalyticsUserRow): string { return Number.isFinite(row.pageviews) ? row.pageviews.toLocaleString(locale.value) : '—' }
function works(row: AnalyticsUserRow): string { return Number.isFinite(row.works) ? row.works!.toLocaleString(locale.value) : '—' }
function time(value: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(locale.value, { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const columns = [
  { label: '用户', value: (row: AnalyticsUserRow) => [accountName(row), accountDetail(row), row.phoneMasked ?? '', row.creatorId].filter(Boolean) },
  { label: '登录方式', value: (row: AnalyticsUserRow) => [accountTypeLabel(row.accountType)] },
  { label: '创作', value: (row: AnalyticsUserRow) => [works(row)] },
  { label: '访问', value: (row: AnalyticsUserRow) => [visits(row)] },
  { label: '停留', value: (row: AnalyticsUserRow) => [dwellLabel(row.avgDurationMs, row.samples), row.samples ? t('{count} 次停留', { count: row.samples }) : t('无有效样本')] },
  { label: '最近访问', value: (row: AnalyticsUserRow) => [time(row.lastSeen)] },
]
const filterId = useId()
const queries = ref(columns.map(() => ''))
const suffix = computed(() => filter.value === 'guest' ? '' : /^(?:\*{4})?([0-9]{4})$/.exec(queries.value[0]?.trim() ?? '')?.[1] ?? '')
const phoneUsers = ref<AnalyticsPhoneUser[]>([])
const phoneState = ref<'idle' | 'loading' | 'failed'>('idle')
const retryPhone = ref(0)

watch([suffix, retryPhone], ([value], _, onCleanup) => {
  phoneUsers.value = []
  phoneState.value = value ? 'loading' : 'idle'
  if (!value) return
  let current = true
  const timer = setTimeout(async () => {
    try {
      const data = await analyticsPhoneUsers(value)
      if (!current) return
      if (!data.allowed) { emit('denied'); return }
      if (data.error) { phoneState.value = 'failed'; return }
      phoneUsers.value = data.users ?? []
      phoneState.value = 'idle'
    } catch {
      if (current) phoneState.value = 'failed'
    }
  }, 250)
  onCleanup(() => { clearTimeout(timer); current = false })
})

const identityRows = computed(() => {
  const rows = new Map<string, AnalyticsUserRow>(props.rows.map(row => [row.creatorId, row]))
  for (const match of phoneUsers.value) {
    const active = rows.get(match.creatorId)
    rows.set(match.creatorId, active ? { ...active, phoneMasked: match.phoneMasked } : {
      ...match, email: null, pv: 0, pageviews: 0, avgDurationMs: 0, samples: 0,
      firstSeen: '', lastSeen: '', noActivity: true,
    })
  }
  return filterUsers([...rows.values()], filter.value)
})
const suggestions = computed(() => columns.map(column => [...new Set(identityRows.value.flatMap(column.value))]))
const normalize = (value: string) => value.trim().toLowerCase().replaceAll(',', '')
const visible = computed(() => identityRows.value.filter(row => columns.every((column, index) => {
  const query = normalize(queries.value[index] ?? '')
  return !query || column.value(row).some(value => normalize(value).includes(query))
})))
const { page, displayed } = useAnalyticsPagination(visible)
watch(sort, () => { page.value = 1 })
</script>

<template>
  <div class="users">
    <div class="users-toolbar">
      <p class="users-summary">{{ t('{count} 位登录用户 · {guests} 位游客', { count: signed, guests }) }}<template v-if="registeredWithoutVisits"> · {{ t('{count} 位新注册账号暂无访问记录', { count: registeredWithoutVisits }) }}</template></p>
      <div class="users-controls">
        <div class="users-sort users-filter" role="group" :aria-label="t('用户排序')">
          <button v-for="option in [{ id: 'active', label: '活跃' }, { id: 'latest', label: '最新' }]" :key="option.id" type="button" :class="{ active: sort === option.id }" :aria-pressed="sort === option.id" @click="sort = option.id as AnalyticsUserSort">{{ t(option.label) }}</button>
        </div>
        <div class="users-filter" role="group" :aria-label="t('按身份筛选用户')">
          <button v-for="option in [{ id: 'all', label: '全部' }, { id: 'signed', label: '仅登录' }, { id: 'guest', label: '仅游客' }]" :key="option.id" type="button" :class="{ active: filter === option.id }" :aria-pressed="filter === option.id" @click="filter = option.id as UserFilter">{{ t(option.label) }}</button>
        </div>
      </div>
    </div>
    <p v-if="sorting" class="users-sort-hint" role="status">{{ t('正在加载用户列表…') }}</p>
    <p v-else-if="!sortError" class="users-sort-hint">{{ t(sort === 'latest' ? '按最近访问时间从新到旧排序' : '按所选周期内的访问量从高到低排序') }}</p>

    <div v-if="!sortError" class="column-filters">
      <label v-for="(column, index) in columns" :key="column.label">
        {{ t(column.label) }}
        <input v-model="queries[index]" type="search" :list="`${filterId}-${index}`" :placeholder="t(index === 0 ? '昵称、账号或手机号后四位' : '输入或选择')">
        <datalist :id="`${filterId}-${index}`">
          <option v-for="value in suggestions[index]" :key="value" :value="value" />
        </datalist>
      </label>
    </div>
    <p v-if="phoneState === 'loading'" class="users-sort-hint" role="status">{{ t('正在按手机号尾号查找账号…') }}</p>
    <p v-else-if="phoneState === 'failed'" class="users-sort-hint" role="alert">
      {{ t('手机号尾号查询失败。') }} <button type="button" class="analytics-retry" @click="retryPhone++">{{ t('重试') }}</button>
    </p>
    <div v-if="sortError" class="users-empty" role="alert">
      {{ t(sortError) }}
      <button type="button" class="analytics-retry" @click="$emit('retrySort')">{{ t('重试') }}</button>
    </div>
    <div v-else-if="!rows.length && !suffix" class="users-empty">{{ t('所选时间范围内暂无用户数据。') }}</div>
    <div v-else-if="!visible.length && phoneState === 'idle'" class="users-empty">{{ t('当前筛选下没有用户。') }}</div>
    <ul v-else-if="visible.length" class="users-list">
      <li v-for="row in displayed" :key="row.creatorId" :class="{ open: openUser === row.creatorId }">
        <button type="button" class="users-row" :aria-expanded="openUser === row.creatorId" :aria-controls="`user-pages-${row.creatorId}`" @click="$emit('toggle', row.creatorId)">
          <span class="avatar" :class="{ guest: isGuest(row.accountType) }" aria-hidden="true">{{ avatarText(row) }}</span>
          <span class="user-main">
            <strong>{{ accountName(row) }}</strong>
            <span v-if="accountDetail(row)" class="user-sub">{{ accountDetail(row) }}</span>
            <span v-if="row.noActivity" class="user-sub">{{ t('所选周期内无访问记录') }}</span>
            <code class="user-id" :title="row.creatorId">{{ row.creatorId }}</code>
          </span>
          <span class="type-badge" :class="{ guest: isGuest(row.accountType) }">{{ accountTypeLabel(row.accountType) }}</span>
          <span class="metric"><strong>{{ works(row) }}</strong><small>{{ t('创作') }}</small></span>
          <span class="metric"><strong>{{ visits(row) }}</strong><small>{{ t('访问') }}</small></span>
          <span class="metric"><strong>{{ dwellLabel(row.avgDurationMs, row.samples) }}</strong><small>{{ row.samples ? t('{count} 次停留', { count: row.samples }) : t('无有效样本') }}</small></span>
          <span class="last-seen">{{ time(row.lastSeen) }}</span>
          <span class="caret" aria-hidden="true"></span>
        </button>
        <div v-if="openUser === row.creatorId" :id="`user-pages-${row.creatorId}`" class="user-detail">
          <p v-if="detailLoading" class="user-detail-state">{{ t('正在读取页面明细…') }}</p>
          <p v-else-if="detailError" class="user-detail-state error" role="alert">{{ t(detailError) }}</p>
          <p v-else-if="!(detailRows ?? []).length" class="user-detail-state">{{ t('这位用户在当前时间范围内没有有效停留记录。') }}</p>
          <ul v-else class="user-pages">
            <li v-for="page in detailRows" :key="page.pagePath">
              <span class="user-page-name">{{ pageName(page.pagePath) }}</span>
              <span class="user-page-metric">{{ dwellLabel(page.avgDurationMs, page.samples) }}</span>
              <span class="user-page-samples">{{ t('{count} 次', { count: page.samples }) }}</span>
            </li>
          </ul>
        </div>
      </li>
    </ul>
    <AnalyticsPagination v-if="!sortError" v-model="page" :total="visible.length" :label="t('用户活跃分页')" />
  </div>
</template>

<style scoped>
.column-filters { display: grid; grid-template-columns: repeat(auto-fit, minmax(125px, 1fr)); gap: 10px; margin: 12px 0; }
.column-filters label { display: grid; gap: 5px; color: #665650; font-size: 12px; }
.column-filters input, .users-expand { min-width: 0; min-height: 36px; padding: 6px 10px; border: 1px solid #f0e1dc; border-radius: 8px; background: #fffaf8; color: #493a35; font: inherit; }
.users-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.users-summary { margin: 0; color: #8a7c76; font-size: 12px; }
.users-controls { display: flex; flex-wrap: wrap; gap: 8px; }
.users-sort-hint { margin: -4px 0 12px; color: #9a8b84; font-size: 11.5px; line-height: 1.7; }
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
