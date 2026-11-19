<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { formatDate, locale, t } from '../../../shared/i18n'
import { analyticsCountryUsers, type AnalyticsCountryRow, type AnalyticsCountryUser } from '../api/analytics'
import { accountDetail, accountName, accountTypeLabel } from '../model/dwell'
import { useAnalyticsPagination } from '../model/pagination'
import AnalyticsPagination from './AnalyticsPagination.vue'

const props = defineProps<{ rows: AnalyticsCountryRow[]; days: number }>()
const emit = defineEmits<{ denied: [] }>()
const filter = ref('')
const open = ref('')
const users = ref<AnalyticsCountryUser[]>([])
const loading = ref(false)
const failed = ref(false)
const detailId = useId()
let request = 0
const names = computed(() => new Intl.DisplayNames(locale.value, { type: 'region', fallback: 'none' }))
function countryName(row: AnalyticsCountryRow) {
  const code = row.countryCode || (/^[A-Z]{2}$/.test(row.country) ? row.country : '')
  if (code && /^[A-Z]{2}$/.test(code)) return names.value.of(code) ?? t('其他国家或地区')
  return locale.value === 'zh-CN' && /\p{Script=Han}/u.test(row.country) ? row.country : t('其他国家或地区')
}
const visible = computed(() => props.rows.filter(row => !filter.value || row.country === filter.value))
const { page, displayed } = useAnalyticsPagination(users)

function close() {
  request++
  open.value = ''
  users.value = []
  loading.value = false
  failed.value = false
}
watch([() => props.rows, () => props.days], () => {
  if (!props.rows.some(row => row.country === filter.value)) filter.value = ''
  close()
})
watch(filter, close)

async function toggle(country: string) {
  const wasOpen = open.value === country
  close()
  if (wasOpen) return
  const turn = request
  open.value = country
  loading.value = true
  try {
    const data = await analyticsCountryUsers(country, props.days)
    if (turn !== request) return
    if (!data.allowed) { emit('denied'); return }
    if (data.error) { failed.value = true; return }
    users.value = data.users ?? []
  } catch {
    if (turn === request) failed.value = true
  } finally {
    if (turn === request) loading.value = false
  }
}
</script>

<template>
  <div v-if="rows.length" class="analytics-filters">
    <label>{{ t('国家或地区') }}
      <select v-model="filter">
        <option value="">{{ t('全部国家或地区') }}</option>
        <option v-for="row in rows" :key="row.country" :value="row.country">{{ countryName(row) }}</option>
      </select>
    </label>
  </div>
  <ul v-if="visible.length" class="country-list">
    <li v-for="row in visible" :key="row.country">
      <button type="button" class="country-row" :aria-expanded="open === row.country"
              :aria-controls="open === row.country ? detailId : undefined" @click="toggle(row.country)">
        <strong>{{ countryName(row) }}</strong>
        <span>{{ t('{count} 位访客', { count: row.visitors.toLocaleString(locale) }) }}</span>
        <span>{{ t('{count} 次访问', { count: row.hits.toLocaleString(locale) }) }}</span>
        <span class="caret" aria-hidden="true"></span>
      </button>
      <div v-if="open === row.country" :id="detailId" class="country-detail">
        <p v-if="loading" role="status">{{ t('正在读取用户明细…') }}</p>
        <p v-else-if="failed" role="alert">{{ t('用户明细读取失败，请稍后重试。') }}</p>
        <p v-else-if="!users.length">{{ t('所选时间范围内暂无用户数据。') }}</p>
        <template v-else>
          <div class="country-scroll">
            <table>
              <thead><tr>
                <th scope="col">{{ t('用户') }}</th><th scope="col">{{ t('登录方式') }}</th>
                <th scope="col">{{ t('访问') }}</th><th scope="col">{{ t('最近访问') }}</th>
              </tr></thead>
              <tbody>
                <tr v-for="(user, index) in displayed" :key="user.creatorId || index">
                  <td>{{ accountName(user) }}<small v-if="accountDetail(user)">{{ accountDetail(user) }}</small></td>
                  <td>{{ accountTypeLabel(user.accountType) }}</td>
                  <td>{{ user.hits.toLocaleString(locale) }}</td>
                  <td>{{ formatDate(user.lastSeen) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <AnalyticsPagination v-model="page" :total="users.length" :label="t('国家用户分页')" />
        </template>
      </div>
    </li>
  </ul>
  <p v-else class="country-empty">{{ t('周期内暂无可解析国家的访客数据。') }}</p>
</template>

<style scoped>
.country-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.country-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto; align-items: center; gap: 14px; width: 100%; padding: 11px 14px; border: 1px solid var(--a-line); border-radius: 12px; background: #fff; color: inherit; font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.country-row:hover, .country-row[aria-expanded="true"] { background: #fff5f1; }
.country-row span { color: var(--a-muted); font-size: 12px; }
.caret { width: 0; height: 0; border-left: 5px solid #a2948e; border-top: 4px solid transparent; border-bottom: 4px solid transparent; }
.country-row[aria-expanded="true"] .caret { transform: rotate(90deg); }
.country-detail { margin-top: 4px; padding: 12px 14px; border-radius: 12px; background: #fbf6f3; border: 1px solid var(--a-line); }
.country-scroll { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 12px; text-align: left; }
th, td { padding: 7px 10px; border-bottom: 1px solid var(--a-line); }
td:first-child { overflow-wrap: anywhere; }
td small { display: block; color: var(--a-muted); }
p { color: var(--a-muted); font-size: 12px; }
</style>
