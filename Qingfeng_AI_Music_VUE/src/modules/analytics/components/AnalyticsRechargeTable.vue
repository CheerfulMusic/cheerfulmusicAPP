<script setup lang="ts">
import { computed, ref } from 'vue'
import { formatDate, locale, t } from '../../../shared/i18n'
import AnalyticsPagination from './AnalyticsPagination.vue'
import { useAnalyticsPagination } from '../model/pagination'
import type { AnalyticsRechargeRow } from '../api/analytics'
import { accountDetail, accountName, accountTypeLabel, isGuest } from '../model/dwell'

const props = defineProps<{ rows: AnalyticsRechargeRow[] }>()

function amount(row: AnalyticsRechargeRow): string {
  if (typeof row.amount !== 'number' || !Number.isFinite(row.amount)) return '—'
  return row.amount.toLocaleString(locale.value, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
const statusClass = (status: string) =>
  ({ PAID: 'paid', PENDING: 'pending', CANCELLED: 'cancelled', FAILED: 'failed' } as Record<string, string>)[status] ?? 'other'
const statuses: Record<string, string> = {
  PAID: '已支付', PENDING: '待支付', CANCELLED: '已取消', FAILED: '失败',
  REFUNDED: '已退款', DISPUTED: '争议中', CREATED: '已创建', EXPIRED: '已过期',
}
const statusLabel = (status: string) => t(Object.hasOwn(statuses, status) ? statuses[status]! : '其他状态')
const currencyLabel = (currency: string) => /^[A-Z]{3}$/.test(currency)
  ? new Intl.DisplayNames(locale.value, { type: 'currency', fallback: 'none' }).of(currency) ?? t('未知币种')
  : t('未知币种')

const user = ref('')
const status = ref('')
const currency = ref('')
const statusOptions = computed(() => [...new Set(props.rows.map(row => row.status))])
const currencyOptions = computed(() => [...new Set(props.rows.map(row => row.currency))].sort())
const visible = computed(() => {
  const query = user.value.trim().toLowerCase()
  return props.rows.filter(row =>
    (!query || [accountName(row), accountDetail(row)].some(value => value.toLowerCase().includes(query)))
    && (!status.value || row.status === status.value)
    && (!currency.value || row.currency === currency.value))
})
const { page, displayed } = useAnalyticsPagination(visible)
</script>

<template>
  <div class="recharge">
    <div v-if="rows.length" class="analytics-filters">
      <label>{{ t('用户') }}
        <input v-model="user" type="search" :placeholder="t('搜索昵称或账号')">
      </label>
      <label>{{ t('状态') }}
        <select v-model="status">
          <option value="">{{ t('全部状态') }}</option>
          <option v-for="option in statusOptions" :key="option" :value="option">{{ statusLabel(option) }}</option>
        </select>
      </label>
      <label>{{ t('币种') }}
        <select v-model="currency">
          <option value="">{{ t('全部币种') }}</option>
          <option v-for="option in currencyOptions" :key="option" :value="option">{{ currencyLabel(option) }}</option>
        </select>
      </label>
    </div>
    <div v-if="!visible.length" class="recharge-empty">{{ t(rows.length ? '当前筛选下没有充值记录。' : '所选时间范围内暂无充值记录。') }}</div>
    <div v-else class="recharge-scroll">
      <table class="recharge-table">
        <thead>
          <tr>
            <th class="col-user">{{ t('用户') }}</th>
            <th class="col-type">{{ t('账号') }}</th>
            <th class="col-pkg">{{ t('套餐') }}</th>
            <th class="col-amount">{{ t('金额') }}</th>
            <th class="col-credits">{{ t('积分') }}</th>
            <th class="col-status">{{ t('状态') }}</th>
            <th class="col-time">{{ t('时间') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in displayed" :key="row.orderId ?? index">
            <td class="col-user">
              <div class="user-cell">
                <strong>{{ accountName(row) }}</strong>
                <small v-if="accountDetail(row)">{{ accountDetail(row) }}</small>
              </div>
            </td>
            <td class="col-type">
              <span class="type-badge" :class="{ guest: isGuest(row.accountType) }">{{ accountTypeLabel(row.accountType) }}</span>
            </td>
            <td class="col-pkg">{{ Number.isFinite(row.credits) ? t('{credits} 积分套餐', { credits: row.credits.toLocaleString(locale) }) : '—' }}</td>
            <td class="col-amount">{{ amount(row) }} <small>{{ currencyLabel(row.currency) }}</small></td>
            <td class="col-credits">{{ Number.isFinite(row.credits) ? row.credits.toLocaleString(locale) : '—' }}</td>
            <td class="col-status"><span class="status-badge" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span></td>
            <td class="col-time">{{ row.createdAt ? formatDate(row.createdAt) : '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <AnalyticsPagination v-model="page" :total="visible.length" :label="t('充值明细分页')" />
  </div>
</template>

<style scoped>
.recharge-scroll { overflow-x: auto; border: 1px solid var(--a-line); border-radius: 14px; background: #fff; }
.recharge-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.recharge-table thead th {
  position: sticky; top: 0; padding: 11px 14px; text-align: left; color: #8a7c76; font-weight: 600;
  background: #fbf6f3; border-bottom: 1px solid var(--a-line); white-space: nowrap;
}
.recharge-table tbody td { padding: 10px 14px; border-bottom: 1px solid #f7efec; color: #3c332f; vertical-align: middle; }
.recharge-table tbody tr:last-child td { border-bottom: 0; }
.recharge-table tbody tr:hover td { background: #fff7f4; }
.user-cell { display: grid; gap: 2px; min-width: 0; }
.user-cell strong { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.type-badge { padding: 2px 9px; border-radius: 999px; background: #eef1f6; color: #4b5a6b; font-size: 11px; white-space: nowrap; }
.type-badge.guest { background: #f6ece6; color: #92684a; }
.col-amount { font-variant-numeric: tabular-nums; white-space: nowrap; }
.col-amount small { color: #a2948e; font-size: 10px; margin-left: 2px; }
.col-credits { font-variant-numeric: tabular-nums; text-align: right; }
.col-time { color: #8a7c76; white-space: nowrap; font-variant-numeric: tabular-nums; }
.status-badge { padding: 2px 9px; border-radius: 999px; font-size: 11px; white-space: nowrap; }
.status-badge.paid { background: #eaf5ee; color: #2f7d5c; }
.status-badge.pending { background: #fdf4e5; color: #a9741f; }
.status-badge.cancelled { background: #f0eef0; color: #7a6f78; }
.status-badge.failed { background: #fbeae8; color: #b93b2c; }
.status-badge.other { background: #f0eef0; color: #7a6f78; }
.recharge-empty { padding: 40px 0; text-align: center; color: #9a8b84; font-size: 12px; border: 1px dashed #e3cfc8; border-radius: 14px; background: #fffaf8; }
@media (max-width: 720px) {
  .col-pkg, .col-credits { display: none; }
}
</style>
