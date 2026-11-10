<script setup lang="ts">
import { computed } from 'vue'
import type { AnalyticsPageUserRow } from '../api/analytics'
import { accountDetail, accountName, accountTypeLabel, dwellLabel, isGuest } from '../model/dwell'

const props = defineProps<{ rows: AnalyticsPageUserRow[]; loading?: boolean; error?: string }>()
const totalSamples = computed(() => props.rows.reduce((sum, row) => sum + (Number(row.samples) || 0), 0))
const guests = computed(() => props.rows.filter(row => isGuest(row.accountType)).length)
</script>

<template>
  <div class="page-users">
    <p v-if="loading" class="page-users-state" role="status">正在读取用户明细…</p>
    <p v-else-if="error" class="page-users-state error" role="alert">{{ error }}</p>
    <p v-else-if="!rows.length" class="page-users-state">这个页面在所选时间范围内还没有用户停留记录。</p>
    <template v-else>
      <table class="page-users-table">
        <caption class="sr-only">该页面每个用户的平均停留时长与有效停留次数</caption>
        <thead><tr><th scope="col">用户</th><th scope="col">身份</th><th scope="col">平均停留</th><th scope="col">有效停留次数</th></tr></thead>
        <tbody>
          <tr v-for="row in rows" :key="row.creatorId">
            <td class="page-users-account">
              <span class="account-name">{{ accountName(row) }}</span>
              <span v-if="accountDetail(row)" class="account-detail">{{ accountDetail(row) }}</span>
              <code class="account-id" :title="row.creatorId">{{ row.creatorId }}</code>
            </td>
            <td><span class="account-type" :class="{ guest: isGuest(row.accountType) }">{{ accountTypeLabel(row.accountType) }}</span></td>
            <td>{{ dwellLabel(row.avgDurationMs, row.samples) }}</td>
            <td>{{ row.samples }}</td>
          </tr>
        </tbody>
      </table>
      <p class="page-users-note">共 {{ rows.length }} 位用户<template v-if="guests"> · 其中 {{ guests }} 位游客</template> · {{ totalSamples }} 次有效停留（时长取离开该页时的可见停留）</p>
    </template>
  </div>
</template>

<style scoped>
.page-users { margin: 4px 0 12px 0; padding: 12px 14px; border-radius: 12px; background: #fbf6f3; border: 1px solid #f0e5e0; }
.page-users-state { margin: 0; color: var(--muted, #6b615a); font-size: 12px; }
.page-users-state.error { color: #a0332a; }
.page-users-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.page-users-table th { padding: 6px 10px; color: var(--muted, #6b615a); font-weight: 500; text-align: left; white-space: nowrap; border-bottom: 1px solid #ece0da; }
.page-users-table td { padding: 7px 10px; border-bottom: 1px solid #f3eae6; font-variant-numeric: tabular-nums; vertical-align: top; }
.page-users-table tbody tr:last-child td { border-bottom: 0; }
.page-users-account { max-width: 320px; }
.account-name { display: block; color: var(--ink, #2c2522); overflow-wrap: anywhere; }
.account-detail { display: block; color: var(--muted, #6b615a); font-size: 11px; overflow-wrap: anywhere; }
.account-id { display: block; margin-top: 2px; color: #a3968f; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; overflow-wrap: anywhere; }
.account-type { display: inline-block; padding: 1px 8px; border-radius: 999px; background: #eef1f6; color: #4b5a6b; font-size: 11px; white-space: nowrap; }
.account-type.guest { background: #f6ece6; color: #92684a; }
.page-users-note { margin: 8px 0 0; color: #9a908b; font-size: 11px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0; }
@media (max-width: 620px) {
  .page-users { padding: 10px 10px; }
  .page-users-table th, .page-users-table td { padding: 6px; }
  .page-users-account { max-width: 150px; }
  .account-id { display: none; }
}
</style>
