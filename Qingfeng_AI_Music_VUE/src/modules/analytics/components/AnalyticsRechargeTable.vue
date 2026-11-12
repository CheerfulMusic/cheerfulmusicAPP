<script setup lang="ts">
import type { AnalyticsRechargeRow } from '../api/analytics'
import { accountName, accountTypeLabel, isGuest } from '../model/dwell'

const props = defineProps<{ rows: AnalyticsRechargeRow[] }>()

function amount(row: AnalyticsRechargeRow): string {
  if (typeof row.amount !== 'number' || !Number.isFinite(row.amount)) return '—'
  return row.amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function time(value: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
const statusClass = (status: string) =>
  ({ PAID: 'paid', PENDING: 'pending', CANCELLED: 'cancelled', FAILED: 'failed' } as Record<string, string>)[status] ?? 'other'
const statusLabel = (status: string) =>
  ({ PAID: '已支付', PENDING: '待支付', CANCELLED: '已取消', FAILED: '失败' } as Record<string, string>)[status] ?? status
</script>

<template>
  <div class="recharge">
    <div v-if="!rows.length" class="recharge-empty">所选时间范围内暂无充值记录。</div>
    <div v-else class="recharge-scroll">
      <table class="recharge-table">
        <thead>
          <tr>
            <th class="col-user">用户</th>
            <th class="col-type">账号</th>
            <th class="col-pkg">套餐</th>
            <th class="col-amount">金额</th>
            <th class="col-credits">积分</th>
            <th class="col-status">状态</th>
            <th class="col-time">时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.creatorId + row.createdAt + row.packageId">
            <td class="col-user">
              <div class="user-cell">
                <strong>{{ accountName(row) }}</strong>
                <code :title="row.creatorId">{{ row.creatorId }}</code>
              </div>
            </td>
            <td class="col-type">
              <span class="type-badge" :class="{ guest: isGuest(row.accountType) }">{{ accountTypeLabel(row.accountType) }}</span>
            </td>
            <td class="col-pkg"><code>{{ row.packageId }}</code></td>
            <td class="col-amount">{{ amount(row) }} <small>{{ row.currency }}</small></td>
            <td class="col-credits">{{ Number.isFinite(row.credits) ? row.credits.toLocaleString('zh-CN') : '—' }}</td>
            <td class="col-status"><span class="status-badge" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span></td>
            <td class="col-time">{{ time(row.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
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
.user-cell code { color: #b3a59e; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.type-badge { padding: 2px 9px; border-radius: 999px; background: #eef1f6; color: #4b5a6b; font-size: 11px; white-space: nowrap; }
.type-badge.guest { background: #f6ece6; color: #92684a; }
.col-pkg code { color: #5f534e; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
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
