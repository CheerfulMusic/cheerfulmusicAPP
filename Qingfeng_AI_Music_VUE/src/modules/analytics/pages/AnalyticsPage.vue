<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { analyticsAccess, analyticsPageUsers, analyticsSummary, type AnalyticsPageUserRow, type AnalyticsSummary } from '../api/analytics'
import AnalyticsPageUsers from '../components/AnalyticsPageUsers.vue'
import { accountDetail, accountName, accountTypeLabel, dwellLabel, filterUsers, formatDuration, isGuest, pageUsersKey, type UserFilter } from '../model/dwell'

const loading = ref(true)
const allowed = ref(false)
const viewer = ref('')
const error = ref('')
const days = ref(30)
const summary = ref<AnalyticsSummary | null>(null)
const expandedPage = ref('')
const pageUsers = ref<Record<string, AnalyticsPageUserRow[]>>({})
const pageUsersLoading = ref('')
const pageUsersError = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const access = await analyticsAccess()
    allowed.value = access.allowed === true
    viewer.value = access.viewer ?? ''
    if (!allowed.value) { summary.value = null; return }
    const data = await analyticsSummary(days.value)
    if (data.allowed !== true) { allowed.value = false; summary.value = null; return }
    summary.value = data
    if (data.error) error.value = '部分统计数据读取失败，请稍后重试。'
  } catch {
    allowed.value = false
    summary.value = null
    error.value = '暂时无法读取数据，请稍后重试。'
  } finally {
    loading.value = false
  }
}
onMounted(load)
// 时间范围变化后，已展开的下钻必须重新取数，不能拿旧窗口的明细冒充。
watch(days, () => { expandedPage.value = ''; pageUsers.value = {}; pageUsersError.value = ''; void load() })

const totals = computed(() => summary.value?.totals)
const daily = computed(() => summary.value?.daily ?? [])
const pages = computed(() => summary.value?.pages ?? [])
const users = computed(() => summary.value?.users ?? [])
const maxPagePv = computed(() => Math.max(1, ...pages.value.map(row => row.pv)))
const guestCount = computed(() => users.value.filter(row => isGuest(row.accountType)).length)
const signedInCount = computed(() => users.value.length - guestCount.value)
const userFilter = ref<UserFilter>('all')
const visibleUsers = computed(() => filterUsers(users.value, userFilter.value))

async function togglePage(pagePath: string) {
  const key = pageUsersKey(days.value, pagePath)
  if (expandedPage.value === key) { expandedPage.value = ''; return }
  expandedPage.value = key
  pageUsersError.value = ''
  if (pageUsers.value[key]) return
  pageUsersLoading.value = key
  try {
    const data = await analyticsPageUsers(pagePath || '/', days.value)
    if (data.allowed !== true) { allowed.value = false; summary.value = null; return }
    pageUsers.value = { ...pageUsers.value, [key]: data.users ?? [] }
    if (data.error) pageUsersError.value = '用户明细读取失败，请稍后重试。'
  } catch {
    pageUsersError.value = '用户明细读取失败，请稍后重试。'
  } finally {
    if (pageUsersLoading.value === key) pageUsersLoading.value = ''
  }
}

const chart = computed(() => {
  const rows = daily.value
  const width = 620, height = 190, pad = 26
  const max = Math.max(1, ...rows.map(row => Math.max(row.pv, row.uv)))
  const x = (i: number) => pad + (i * (width - 2 * pad)) / Math.max(1, rows.length - 1)
  const y = (v: number) => height - pad - (v / max) * (height - 2 * pad)
  const line = (key: 'pv' | 'uv') => rows.map((row, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(row[key]).toFixed(1)}`).join(' ')
  return {
    pvPath: line('pv'), uvPath: line('uv'), width, height, baseY: height - pad, max,
    ticks: [0, 0.5, 1].map(ratio => ({ y: y(max * ratio), label: Math.round(max * ratio) })),
  }
})

function number(value: number | undefined): string { return Number(value ?? 0).toLocaleString('zh-CN') }
function time(value: string | undefined | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <main class="analytics">
    <header class="analytics-head">
      <div>
        <h1>站内数据看板</h1>
        <p>访问量、停留时长与用户身份明细 · 仅授权账号可见</p>
      </div>
      <div v-if="allowed" class="analytics-range" role="group" aria-label="统计时间范围">
        <button v-for="option in [7, 30, 90]" :key="option" type="button" :class="{ active: days === option }" :aria-pressed="days === option" @click="days = option">{{ option }} 天</button>
      </div>
    </header>

    <p v-if="allowed && viewer" class="analytics-viewer">当前查看账号：{{ viewer }}</p>
    <div v-if="loading" class="analytics-state">正在读取数据…</div>
    <div v-else-if="!allowed" class="analytics-state denied">
      <strong>无权访问</strong>
      <span>此看板仅对指定的 Google 账号开放。请使用授权账号登录后再访问。</span>
    </div>
    <template v-else>
      <p v-if="error" class="analytics-error" role="alert">{{ error }}</p>

      <section class="analytics-cards" aria-label="核心指标">
        <div class="analytics-card"><span>访问量 PV</span><strong>{{ number(totals?.pv) }}</strong><small>页面浏览 + 离开事件</small></div>
        <div class="analytics-card"><span>独立访客 UV</span><strong>{{ number(totals?.uv) }}</strong><small>按匿名访客标识去重</small></div>
        <div class="analytics-card"><span>平均访问时长</span><strong>{{ formatDuration(totals?.avgDurationMs ?? 0) }}</strong><small>单次页面停留</small></div>
        <div class="analytics-card"><span>累计停留</span><strong>{{ formatDuration(totals?.totalDurationMs ?? 0) }}</strong><small>会话数 {{ number(totals?.sessions) }}</small></div>
      </section>

      <section class="analytics-panel">
        <h2>访问趋势</h2>
        <p class="analytics-hint">按日统计 PV / UV</p>
        <div v-if="!daily.length" class="analytics-empty">所选时间范围内暂无数据。</div>
        <svg v-else class="analytics-chart" :viewBox="`0 0 ${chart.width} ${chart.height}`" role="img" aria-label="按日访问量趋势">
          <g v-for="(tick, index) in chart.ticks" :key="index">
            <line :x1="26" :x2="chart.width - 26" :y1="tick.y" :y2="tick.y" stroke="#ece6e3" stroke-width="1" />
            <text :x="4" :y="tick.y - 3" fill="#8b817d" font-size="10">{{ tick.label }}</text>
          </g>
          <path :d="chart.pvPath" fill="none" stroke="#d4483b" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          <path :d="chart.uvPath" fill="none" stroke="#3b7fb5" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="5 4" />
        </svg>
        <div class="analytics-legend"><span><i class="dot pv"></i>PV 访问量</span><span><i class="dot uv"></i>UV 独立访客</span><span v-if="daily.length">首日 {{ daily[0].d }} · 末日 {{ daily[daily.length - 1].d }}</span></div>
      </section>

      <section class="analytics-panel">
        <h2>页面分布与停留</h2>
        <p class="analytics-hint">时长取每次离开该页时的可见停留，切到后台不计时；「—」表示还没有样本。</p>
        <div v-if="!pages.length" class="analytics-empty">暂无页面浏览数据。</div>
        <ul v-else class="analytics-bars">
          <li v-for="(row, index) in pages" :key="row.pagePath">
            <button type="button" class="bar-row" :class="{ open: expandedPage === pageUsersKey(days, row.pagePath) }" :aria-expanded="expandedPage === pageUsersKey(days, row.pagePath)" :aria-controls="`page-users-${index}`" @click="togglePage(row.pagePath)">
              <span class="bar-label" :title="row.pagePath"><i class="bar-caret" aria-hidden="true"></i>{{ row.pagePath || '/' }}</span>
              <span class="bar-track"><i :style="{ width: `${(row.pv / maxPagePv) * 100}%` }"></i></span>
              <span class="bar-value">{{ number(row.pv) }}</span>
              <span class="bar-duration" :title="row.samples ? `基于 ${number(row.samples)} 次停留` : '还没有停留数据'">{{ dwellLabel(row.avgDurationMs, row.samples) }}</span>
            </button>
            <AnalyticsPageUsers v-if="expandedPage === pageUsersKey(days, row.pagePath)" :id="`page-users-${index}`" :rows="pageUsers[pageUsersKey(days, row.pagePath)] ?? []" :loading="pageUsersLoading === pageUsersKey(days, row.pagePath)" :error="pageUsersError" />
          </li>
        </ul>
      </section>

      <section class="analytics-panel">
        <h2>用户明细</h2>
        <p class="analytics-hint">账号由服务端关联身份表取得；「游客」只创建了浏览器会话，未绑定 Google / 邮箱 / 手机身份，只能显示会话标识。手机号在登录时按脱敏保存，只能显示掩码。</p>
        <div v-if="users.length" class="analytics-user-filter" role="group" aria-label="按身份筛选用户">
          <p class="analytics-hint">{{ signedInCount }} 位登录用户 · {{ guestCount }} 位游客</p>
          <div class="analytics-range">
            <button v-for="option in [{ id: 'all', label: '全部' }, { id: 'signed', label: '仅登录' }, { id: 'guest', label: '仅游客' }]" :key="option.id" type="button" :class="{ active: userFilter === option.id }" :aria-pressed="userFilter === option.id" @click="userFilter = option.id as UserFilter">{{ option.label }}</button>
          </div>
        </div>
        <div v-if="!users.length" class="analytics-empty">所选时间范围内暂无用户数据。</div>
        <div v-else-if="!visibleUsers.length" class="analytics-empty">当前筛选下没有用户。</div>
        <div v-else class="analytics-table-wrap">
          <table class="analytics-table">
            <thead><tr><th>用户</th><th>身份</th><th>访问量</th><th>平均停留</th><th>有效停留次数</th><th>首次访问</th><th>最近访问</th></tr></thead>
            <tbody>
              <tr v-for="row in visibleUsers" :key="row.creatorId">
                <td class="user-cell">
                  <span class="user-name">{{ accountName(row) }}</span>
                  <span v-if="accountDetail(row)" class="user-detail">{{ accountDetail(row) }}</span>
                  <code class="user-id" :title="row.creatorId">{{ row.creatorId }}</code>
                </td>
                <td>{{ accountTypeLabel(row.accountType) }}</td>
                <td>{{ number(row.pv) }}</td>
                <td>{{ dwellLabel(row.avgDurationMs, row.samples) }}</td>
                <td>{{ number(row.samples) }}</td>
                <td>{{ time(row.firstSeen) }}</td>
                <td>{{ time(row.lastSeen) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.analytics { max-width: 1080px; margin: 0 auto; padding: 44px 24px 90px; color: var(--ink, #2c2522); }
.analytics-head { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 8px; }
.analytics-head h1 { margin: 0; font-size: 26px; font-weight: 600; letter-spacing: -.5px; }
.analytics-head p { margin: 8px 0 0; color: var(--muted, #6b615a); font-size: 13px; }
.analytics-range { display: inline-flex; gap: 4px; padding: 4px; border: 1px solid var(--line, #e8dedb); border-radius: 999px; background: var(--surface, #fff); }
.analytics-range button { min-width: 58px; padding: 7px 12px; border: 0; border-radius: 999px; background: transparent; color: var(--muted, #6b615a); font-size: 12px; cursor: pointer; }
.analytics-range button.active { background: #d4483b; color: #fff; }
.analytics-viewer { margin: 0 0 18px; color: var(--muted, #6b615a); font-size: 12px; }
.analytics-state { display: grid; gap: 8px; place-content: center; min-height: 260px; padding: 30px; border: 1px solid var(--line, #e8dedb); border-radius: 18px; background: var(--surface, #fff); text-align: center; color: var(--muted, #6b615a); font-size: 13px; }
.analytics-state.denied strong { color: var(--ink, #2c2522); font-size: 16px; }
.analytics-error { margin: 0 0 14px; padding: 10px 14px; border-radius: 10px; background: #fff1ef; color: #a0332a; font-size: 12px; }
.analytics-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 14px; margin-bottom: 20px; }
.analytics-card { display: grid; gap: 6px; padding: 20px; border: 1px solid var(--line, #e8dedb); border-radius: 16px; background: var(--surface, #fff); }
.analytics-card span { color: var(--muted, #6b615a); font-size: 12px; }
.analytics-card strong { font-size: 27px; font-weight: 600; letter-spacing: -.6px; }
.analytics-card small { color: #9a908b; font-size: 11px; }
.analytics-panel { margin-bottom: 20px; padding: 22px; border: 1px solid var(--line, #e8dedb); border-radius: 16px; background: var(--surface, #fff); }
.analytics-panel h2 { margin: 0; font-size: 15px; font-weight: 600; }
.analytics-hint { margin: 6px 0 16px; color: #9a908b; font-size: 11px; }
.analytics-empty { padding: 34px 0; color: var(--muted, #6b615a); font-size: 12px; text-align: center; }
.analytics-chart { display: block; width: 100%; height: auto; }
.analytics-legend { display: flex; flex-wrap: wrap; gap: 18px; margin-top: 10px; color: var(--muted, #6b615a); font-size: 11px; }
.analytics-legend span { display: inline-flex; align-items: center; gap: 6px; }
.dot { width: 9px; height: 9px; border-radius: 50%; }
.dot.pv { background: #d4483b; }
.dot.uv { background: #3b7fb5; }
.analytics-bars { display: grid; gap: 4px; margin: 16px 0 0; padding: 0; list-style: none; }
.analytics-bars li { display: block; font-size: 12px; }
.bar-row { display: grid; grid-template-columns: minmax(110px, 1.1fr) minmax(90px, 2fr) 54px 96px; align-items: center; gap: 12px; width: 100%; padding: 7px 8px; border: 0; border-radius: 10px; background: none; color: inherit; font: inherit; font-size: 12px; text-align: left; cursor: pointer; }
.bar-row:hover { background: #faf3f0; }
.bar-row.open { background: #fbf1ee; }
.bar-row:focus-visible { outline: 2px solid #d4483b; outline-offset: 2px; }
.bar-caret { display: inline-block; width: 0; height: 0; margin-right: 7px; border-left: 5px solid #b8a9a2; border-top: 4px solid transparent; border-bottom: 4px solid transparent; vertical-align: middle; transition: transform .15s ease; }
.bar-row.open .bar-caret { transform: rotate(90deg); }
.bar-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink, #2c2522); }
.bar-track { height: 9px; border-radius: 999px; background: #f3ece9; overflow: hidden; }
.bar-track i { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #f0a08f, #d4483b); }
.bar-value { text-align: right; font-variant-numeric: tabular-nums; }
.bar-duration { color: var(--muted, #6b615a); text-align: right; }
.analytics-table-wrap { overflow-x: auto; margin-top: 14px; }
.analytics-user-filter { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; }
.analytics-user-filter .analytics-hint { margin: 0; }
.analytics-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.analytics-table th { padding: 10px 12px; border-bottom: 1px solid var(--line, #e8dedb); color: var(--muted, #6b615a); font-weight: 500; text-align: left; white-space: nowrap; }
.analytics-table td { padding: 11px 12px; border-bottom: 1px solid #f4eeec; font-variant-numeric: tabular-nums; }
.analytics-table tbody tr:last-child td { border-bottom: 0; }
.user-cell { max-width: 340px; }
.user-name { display: block; color: var(--ink, #2c2522); overflow-wrap: anywhere; }
.user-detail { display: block; color: var(--muted, #6b615a); font-size: 11px; overflow-wrap: anywhere; }
.user-id { display: block; margin-top: 2px; color: #a3968f; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 10px; overflow-wrap: anywhere; }
@media (max-width: 620px) {
  .analytics { padding: 28px 16px 70px; }
  .bar-row { grid-template-columns: minmax(90px, 1fr) minmax(60px, 1.2fr) 44px; }
  .bar-duration { display: none; }
}
</style>
