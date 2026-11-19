<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { locale, t } from '../../../shared/i18n'
import { ApiError } from '../../../shared/http/api-client'
import {
  analyticsAccess, analyticsPageUsers, analyticsSummary, analyticsUserPages,
  type AnalyticsPageUserRow, type AnalyticsSummary, type AnalyticsUserPageRow, type AnalyticsUserSort,
} from '../api/analytics'
import AnalyticsKpiCard from '../components/AnalyticsKpiCard.vue'
import AnalyticsTrendChart from '../components/AnalyticsTrendChart.vue'
import AnalyticsPagePerformance from '../components/AnalyticsPagePerformance.vue'
import AnalyticsUserList from '../components/AnalyticsUserList.vue'
import AnalyticsCountryDistribution from '../components/AnalyticsCountryDistribution.vue'
import AnalyticsRechargeTable from '../components/AnalyticsRechargeTable.vue'
import { buildTrend, formatDuration, pageUsersKey, type UserFilter } from '../model/dwell'

const loading = ref(true)
/** 只有服务端明确返回 allowed=false 才会置为 true；网络错误/500 一律走 error。 */
const accessDenied = ref(false)
const error = ref('')
/** 刷新失败但仍在展示上一次成功的数据。 */
const stale = ref(false)
/** 服务端明确返回 QUERY_FAILED：统计不可用，不能渲染成 0。 */
const unavailable = ref(false)
const viewer = ref('')
const days = ref(30)
const userSort = ref<AnalyticsUserSort>('active')
const usersLoading = ref(false)
const usersError = ref('')
const userFilter = ref<UserFilter>('all')
const summary = ref<AnalyticsSummary | null>(null)
const updatedAt = ref<Date | null>(null)

const openPage = ref<string | null>(null)
const pageUsers = ref<Record<string, AnalyticsPageUserRow[]>>({})
const pageUsersLoading = ref('')
const pageUsersError = ref('')

const openUser = ref('')
const userPages = ref<Record<string, AnalyticsUserPageRow[]>>({})
const userPagesLoading = ref('')
const userPagesError = ref('')

/** 请求序号：快速切换周期时，旧请求不得覆盖新周期的结果。 */
let loadTurn = 0

function denyAccess() { accessDenied.value = true; summary.value = null }

function describe(error: unknown): string {
  // 共享客户端对写操作优化的提示不适合读接口，这里换成人能看懂的说明。
  if (error instanceof ApiError && error.code === 'INVALID_RESPONSE') return '返回的数据格式不完整，暂时无法展示。'
  return '加载失败，请稍后重试。'
}

async function load(keepData = false) {
  const turn = ++loadTurn
  const hadData = summary.value !== null
  const sortingUnresolved = usersLoading.value || !!usersError.value
  usersLoading.value = false
  usersError.value = ''
  loading.value = true
  error.value = ''
  accessDenied.value = false
  if (!keepData) { summary.value = null; unavailable.value = false; stale.value = false }
  try {
    const access = await analyticsAccess()
    if (turn !== loadTurn) return
    if (access.allowed !== true) { accessDenied.value = true; summary.value = null; return }
    const data = await analyticsSummary(days.value, userSort.value)
    if (turn !== loadTurn) return
    if (data.allowed !== true) { accessDenied.value = true; summary.value = null; return }
    if (data.error) { unavailable.value = true; summary.value = null; stale.value = false; return }
    viewer.value = access.viewer ?? ''
    summary.value = data
    unavailable.value = false
    stale.value = false
    updatedAt.value = new Date()
  } catch (e) {
    if (turn !== loadTurn) return
    error.value = describe(e)
    accessDenied.value = false
    if (hadData && keepData) {
      stale.value = true
      if (sortingUnresolved) usersError.value = error.value
      return
    }
    summary.value = null
    unavailable.value = false
    stale.value = false
  } finally {
    if (turn === loadTurn) loading.value = false
  }
}
onMounted(() => { void load(false) })

watch(days, () => {
  openPage.value = null; pageUsers.value = {}; pageUsersError.value = ''
  openUser.value = ''; userPages.value = {}; userPagesError.value = ''
  void load(false)
})

async function loadUserSort() {
  const turn = ++loadTurn
  usersLoading.value = true
  usersError.value = ''
  try {
    const data = await analyticsSummary(days.value, userSort.value)
    if (turn !== loadTurn) return
    if (!data.allowed) { accessDenied.value = true; summary.value = null; return }
    if (data.error) throw new Error('QUERY_FAILED')
    if (summary.value) summary.value = { ...summary.value, users: data.users }
  } catch (e) {
    if (turn === loadTurn) usersError.value = describe(e)
  } finally {
    if (turn === loadTurn) usersLoading.value = false
  }
}

watch(userSort, () => {
  if (loading.value || !summary.value) { void load(true); return }
  void loadUserSort()
})

const totals = computed(() => summary.value?.totals)
const pages = computed(() => summary.value?.pages ?? [])
/** 空 creator_id 是埋点未解析出归属的脏行，不作为可下钻用户展示。 */
const users = computed(() => (summary.value?.users ?? []).filter(row => !!row.creatorId?.trim()))
const trend = computed(() => buildTrend(summary.value?.daily ?? [], days.value))
const totalSamples = computed(() => pages.value.reduce((sum, row) => sum + (row.samples || 0), 0))
const recharge = computed(() => summary.value?.recharge)
const recharges = computed(() => summary.value?.recharges ?? [])
const countries = computed(() => summary.value?.countries ?? [])
const refreshLabel = computed(() => (updatedAt.value ? t('更新于 {time}', { time: timeOnly(updatedAt.value) }) : t('尚未更新')))

async function togglePage(pagePath: string) {
  if (openPage.value === pagePath) { openPage.value = null; return }
  openPage.value = pagePath
  openUser.value = ''
  pageUsersError.value = ''
  const period = days.value
  const key = pageUsersKey(period, pagePath)
  if (pageUsers.value[key]) return
  pageUsersLoading.value = key
  try {
    const data = await analyticsPageUsers(pagePath || '/', period)
    if (period !== days.value) return
    if (data.allowed !== true) { accessDenied.value = true; summary.value = null; return }
    pageUsers.value = { ...pageUsers.value, [key]: data.users ?? [] }
    if (data.error) pageUsersError.value = '用户明细读取失败，请稍后重试。'
  } catch {
    if (period !== days.value) return
    pageUsersError.value = '用户明细读取失败，请稍后重试。'
  } finally {
    if (pageUsersLoading.value === key) pageUsersLoading.value = ''
  }
}

async function toggleUser(creatorId: string) {
  if (openUser.value === creatorId) { openUser.value = ''; return }
  openUser.value = creatorId
  openPage.value = null
  userPagesError.value = ''
  const period = days.value
  const key = pageUsersKey(period, creatorId)
  if (userPages.value[key]) return
  userPagesLoading.value = key
  try {
    const data = await analyticsUserPages(creatorId, period)
    if (period !== days.value) return
    if (data.allowed !== true) { accessDenied.value = true; summary.value = null; return }
    userPages.value = { ...userPages.value, [key]: data.pages ?? [] }
    if (data.error) userPagesError.value = '页面明细读取失败，请稍后重试。'
  } catch {
    if (period !== days.value) return
    userPagesError.value = '页面明细读取失败，请稍后重试。'
  } finally {
    if (userPagesLoading.value === key) userPagesLoading.value = ''
  }
}

/** 缺失字段一律显示「—」，不再把 undefined 当成真实的 0。 */
function number(value: number | undefined | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString(locale.value) : '—'
}
function durationValue(value: number | undefined | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? formatDuration(value) : '—'
}
function timeOnly(value: Date): string {
  return value.toLocaleTimeString(locale.value, { hour12: false, hour: '2-digit', minute: '2-digit' })
}

const detailRowsForPage = computed(() => pageUsers.value[pageUsersKey(days.value, openPage.value ?? '/')] ?? [])
const detailRowsForUser = computed(() => userPages.value[pageUsersKey(days.value, openUser.value)] ?? [])
</script>

<template>
  <div class="analytics">
    <header class="analytics-head">
      <div class="analytics-title">
        <div class="title-meta">
          <span class="badge">{{ t('运营数据中心') }}</span>
        </div>
        <h1>{{ t('站点数据看板') }}</h1>
        <p>{{ t('了解用户访问、页面表现与创作行为，持续优化 Cheerful Music。') }}</p>
      </div>
      <div v-if="!accessDenied" class="analytics-controls">
        <div class="analytics-range" role="group" :aria-label="t('统计时间范围')">
          <button v-for="option in [7, 30, 90]" :key="option" type="button" :class="{ active: days === option }" :aria-pressed="days === option" @click="days = option">{{ t('{days} 天', { days: option }) }}</button>
        </div>
        <span class="analytics-updated">{{ refreshLabel }}</span>
        <button type="button" class="analytics-refresh" :disabled="loading" :aria-label="t('刷新数据')" @click="load(true)">
          <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" :class="{ spinning: loading }"><path d="M16.3 9.2a6.4 6.4 0 1 1-1.9-3.6"/><path d="M16.6 3.4v3.6H13"/></svg>
          <span>{{ t('刷新') }}</span>
        </button>
      </div>
    </header>

    <p v-if="viewer && !accessDenied" class="analytics-viewer">{{ t('当前查看账号：{viewer} · 统计周期：最近 {days} 天', { viewer, days }) }}</p>

    <div v-if="loading && !summary && !error && !accessDenied" class="analytics-state">{{ t('正在读取数据…') }}</div>
    <div v-else-if="accessDenied" class="analytics-state denied">
      <strong>{{ t('无权访问') }}</strong>
      <span>{{ t('此看板仅对指定账号开放，请使用已授权的 Google 账号登录。') }}</span>
    </div>
    <div v-else-if="!summary && error" class="analytics-state failed" role="alert">
      <strong>{{ t('加载失败') }}</strong>
      <span>{{ t(error) }}</span>
      <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
    </div>
    <template v-else>
      <p v-if="stale" class="analytics-stale" role="status">
        {{ t('刷新失败，当前展示的是最近一次成功的数据（{error}），可能已过期。', { error: t(error) }) }}
        <button type="button" class="analytics-retry" @click="load(true)">{{ t('重试') }}</button>
      </p>

      <section class="block" aria-labelledby="sec-kpi">
        <div class="section-title"><h2 id="sec-kpi">{{ t('核心指标') }}</h2></div>
        <div v-if="unavailable" class="analytics-unavailable" role="alert">
          <strong>{{ t('数据不可用') }}</strong>
          <span>{{ t('统计查询失败，指标暂时无法计算，请重试。') }}</span>
          <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
        </div>
        <div v-else class="analytics-kpis">
          <AnalyticsKpiCard :label="t('访问量 PV')" :value="number(totals?.pageviews)" :hint="t('总访问次数 · 仅页面浏览')" icon="eye" tint="coral" :spark="trend.map(point => point.pageviews)" />
          <AnalyticsKpiCard :label="t('独立访客 UV')" :value="number(totals?.uv)" :hint="t('独立访问人数 · 按访客标识去重')" icon="users" tint="green" :spark="trend.map(point => point.uv)" />
          <AnalyticsKpiCard :label="t('平均访问时长')" :value="durationValue(totals?.avgDurationMs)" :hint="totalSamples ? t('基于 {count} 次有效停留', { count: number(totalSamples) }) : t('还没有有效停留样本')" icon="clock" tint="violet" />
          <AnalyticsKpiCard :label="t('累计停留时长')" :value="durationValue(totals?.totalDurationMs)" :hint="t('会话数 {sessions} · 有效停留 {samples} 次', { sessions: number(totals?.sessions), samples: number(totalSamples) })" icon="hourglass" tint="amber" />
          <AnalyticsKpiCard :label="t('创作次数')" :value="number(totals?.creations ?? summary?.creations)" :hint="t('周期内新增作品')" tint="coral" />
          <AnalyticsKpiCard :label="t('充值总额')" :value="number(recharge?.amount)" :hint="t('已支付 {orders} 笔 · 未做汇率换算', { orders: number(recharge?.orders) })" tint="green" />
          <AnalyticsKpiCard :label="t('已支付订单')" :value="number(recharge?.orders)" :hint="t('累计 {credits} 积分', { credits: number(recharge?.credits) })" tint="amber" />
        </div>
      </section>

      <section class="block" aria-labelledby="sec-trend">
        <div class="section-title"><h2 id="sec-trend">{{ t('访问趋势') }}</h2></div>
        <p class="block-hint">{{ t('按日统计，缺失日期按 0 补齐；悬停可查看当天具体数值。') }}</p>
        <div v-if="unavailable" class="analytics-unavailable" role="alert">
          <strong>{{ t('趋势数据不可用') }}</strong>
          <span>{{ t('查询失败时不会生成全零曲线。') }}</span>
          <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
        </div>
        <AnalyticsTrendChart v-else :points="trend" />
      </section>

      <section class="block" aria-labelledby="sec-pages">
        <div class="section-title"><h2 id="sec-pages">{{ t('页面表现') }}</h2></div>
        <p class="block-hint">{{ t('按访问量排序，展示访问量最高的前 20 个页面（占比按这 20 页合计，可能低于全站 100%）；平均停留只统计有效样本，没有样本显示「—」。点击任一页面可展开该页的用户停留明细。') }}</p>
        <div v-if="unavailable" class="analytics-unavailable" role="alert">
          <strong>{{ t('页面数据不可用') }}</strong>
          <span>{{ t('查询失败时不会显示成空榜单。') }}</span>
          <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
        </div>
        <AnalyticsPagePerformance
          v-else
          :rows="pages"
          :open-path="openPage ?? undefined"
          :detail-rows="detailRowsForPage"
          :detail-loading="pageUsersLoading === pageUsersKey(days, openPage ?? '/')"
          :detail-error="pageUsersError"
          @toggle="togglePage" />
      </section>

      <section class="block" aria-labelledby="sec-users">
        <div class="section-title"><h2 id="sec-users">{{ t('用户活跃') }}</h2></div>
        <p class="block-hint">{{ t('点击用户查看页面停留明细。所选周期内新注册且无访问记录的账号列在活跃用户之后。') }}</p>
        <div v-if="unavailable" class="analytics-unavailable" role="alert">
          <strong>{{ t('用户数据不可用') }}</strong>
          <span>{{ t('查询失败时不会显示成没有用户。') }}</span>
          <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
        </div>
        <AnalyticsUserList
          v-else
          v-model:sort="userSort"
          v-model:filter="userFilter"
          :rows="users"
          :sorting="usersLoading"
          :sort-error="usersError"
          :open-user="openUser"
          :detail-rows="detailRowsForUser"
          :detail-loading="userPagesLoading === pageUsersKey(days, openUser)"
          :detail-error="userPagesError"
          @toggle="toggleUser"
          @retry-sort="loadUserSort"
          @denied="denyAccess" />
      </section>

      <section class="block" aria-labelledby="sec-recharge">
        <div class="section-title"><h2 id="sec-recharge">{{ t('充值明细') }}</h2></div>
        <p class="block-hint">{{ t('周期内全部充值订单（含待支付 / 失败），按时间倒序；「已支付」为实际成交。金额可能含多币种，合计值未做汇率换算（不同币种金额相加仅供参考）。') }}</p>
        <div v-if="unavailable" class="analytics-unavailable" role="alert">
          <strong>{{ t('充值数据不可用') }}</strong>
          <span>{{ t('查询失败时不会显示成没有充值。') }}</span>
          <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
        </div>
        <AnalyticsRechargeTable v-else :rows="recharges" />
      </section>

      <section class="block" aria-labelledby="sec-geo">
        <div class="section-title"><h2 id="sec-geo">{{ t('国家分布') }}</h2></div>
        <p class="block-hint">{{ t('基于埋点请求派生的国家（离线 GeoIP 解析，仅记录国家、不存储原始 IP）。只统计周期内已解析出国家的真实访客；IPv6 与私有 / 保留地址不计入。') }}</p>
        <div v-if="unavailable" class="analytics-unavailable" role="alert">
          <strong>{{ t('国家数据不可用') }}</strong>
          <span>{{ t('查询失败时不会显示成没有访客。') }}</span>
          <button type="button" class="analytics-retry" @click="load(false)">{{ t('重试') }}</button>
        </div>
        <AnalyticsCountryDistribution v-else :rows="countries" :days="days" @denied="denyAccess" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.analytics {
  --a-ink: #231f1d;
  --a-muted: #7d6f69;
  --a-line: #f0e5e0;
  --a-coral: #d4483b;
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 32px 96px;
  color: var(--a-ink);
  background: transparent;
}
.analytics-head { display: flex; flex-wrap: wrap; align-items: flex-end; justify-content: space-between; gap: 18px; }
.title-meta { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.badge { padding: 3px 10px; border-radius: 999px; background: #eaf5ee; color: #2f7d5c; font-size: 10.5px; font-weight: 600; }
.analytics-title h1 { margin: 10px 0 0; font-size: 28px; font-weight: 700; letter-spacing: -.02em; }
.analytics-title p { margin: 8px 0 0; color: var(--a-muted); font-size: 13px; line-height: 1.7; }
.analytics-controls { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.analytics-range { display: inline-flex; gap: 3px; padding: 4px; border: 1px solid var(--a-line); border-radius: 999px; background: #fff; }
.analytics-range button { min-width: 58px; padding: 7px 12px; border: 0; border-radius: 999px; background: transparent; color: var(--a-muted); font-size: 12px; cursor: pointer; }
.analytics-range button.active { background: var(--a-coral); color: #fff; }
.analytics-updated { color: #a2948e; font-size: 11.5px; white-space: nowrap; }
.analytics-refresh { display: inline-flex; align-items: center; gap: 6px; padding: 7px 13px; border: 1px solid var(--a-line); border-radius: 999px; background: #fff; color: var(--a-ink); font-size: 12px; cursor: pointer; }
.analytics-refresh:disabled { opacity: .6; cursor: progress; }
.analytics-refresh svg { width: 15px; height: 15px; }
.analytics-refresh svg.spinning { animation: spin .9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.analytics-viewer { margin: 14px 0 0; color: #a2948e; font-size: 11.5px; }
.analytics-state { display: grid; gap: 8px; place-content: center; justify-items: center; min-height: 320px; margin-top: 22px; border: 1px solid var(--a-line); border-radius: 18px; background: #fff; text-align: center; color: var(--a-muted); font-size: 13px; }
.analytics-state.denied strong, .analytics-state.failed strong { color: var(--a-ink); font-size: 16px; }
.analytics-retry { padding: 7px 16px; border: 1px solid var(--a-line); border-radius: 999px; background: #fff; color: var(--a-coral); font-size: 12px; cursor: pointer; }
.analytics-stale { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 16px 0 0; padding: 10px 14px; border-radius: 12px; background: #fdf4e5; color: #8a6412; font-size: 12px; }
.analytics-unavailable { display: grid; gap: 6px; justify-items: start; padding: 22px 20px; border: 1px dashed #e3cfc8; border-radius: 14px; background: #fffaf8; }
.analytics-unavailable strong { font-size: 14px; color: var(--a-ink); }
.analytics-unavailable span { color: var(--a-muted); font-size: 12px; }
.block { margin-top: 30px; padding-top: 22px; border-top: 1px solid var(--a-line); }
.section-title { display: flex; align-items: baseline; gap: 10px; margin-bottom: 14px; }
.section-title h2 { margin: 0; font-size: 15px; font-weight: 650; letter-spacing: -.01em; }
.block-hint { margin: -4px 0 16px; max-width: 680px; color: #9a8b84; font-size: 11.5px; line-height: 1.7; }
.analytics-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; }
@media (max-width: 1080px) {
  .analytics-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .analytics { padding: 28px 18px 80px; }
  .analytics-title h1 { font-size: 24px; }
  .analytics-kpis { grid-template-columns: 1fr; gap: 14px; }
  .analytics-controls { width: 100%; justify-content: space-between; }
}
.analytics :deep(.analytics-filters) { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 12px; }
.analytics :deep(.analytics-filters label) { display: grid; gap: 4px; color: var(--a-muted); font-size: 12px; }
.analytics :deep(.analytics-filters input), .analytics :deep(.analytics-filters select) { min-width: 130px; max-width: 100%; padding: 6px 10px; border-radius: 8px; border: 1px solid var(--a-line); background: white; color: var(--a-ink); font: inherit; }
</style>
