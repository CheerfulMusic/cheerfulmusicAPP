<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { accountState } from '../../../shared/identity/account-state'
import { logtoState } from '../../../shared/identity/logto-state'
import { t, locale } from '../../../shared/i18n'
import { readPendingOrders, savePendingOrders } from '../model/payment-orders'
import { canCapturePaypal, paypalReturn } from '../model/paypal-return'

const props = withDefaults(defineProps<{ preview?: boolean }>(), { preview: false })
// Preview is only reachable in a development build and must never call the payment API.
const isPreview = import.meta.env.DEV && props.preview

type Package = { id: string; title: string; amount: number; currency: string; credits: number }
type Catalog = { enabled: boolean; packages: Package[] }
type Credits = { enabled: boolean; balance: number; musicCost: number }
type Provider = 'airwallex'|'paypal'
type Order = { orderId: string; status: 'CREATING'|'PENDING'|'PAID'; credits: number; createdAt: string; provider?: Provider }
const catalogs = ref<Record<Provider, Catalog>>({airwallex:{enabled:false,packages:[]},paypal:{enabled:false,packages:[]}}), credits = ref<Credits>(), loading = ref(true), busy = ref(false), error = ref('')
const purchaseUncertain = ref(false)
const orderNotice = ref('')
const unresolvedOrders = ref<Order[]>([])
const paypalApproved = ref<string[]>([])
const catalogError = ref(false)
const selectedCurrency = ref('GBP')
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
const availableCurrencies = computed(() => [...new Set(Object.values(catalogs.value).filter(c => c.enabled).flatMap(c => c.packages.map(p => p.currency)))].sort())
const visibleProviders = computed(() => (['paypal', 'airwallex'] as const).map(provider => ({
  provider, packages: catalogs.value[provider].enabled ? catalogs.value[provider].packages.filter(p => p.currency === selectedCurrency.value) : []
})).filter(group => group.packages.length > 0))
watch(availableCurrencies, currencies => { if (currencies.length && !currencies.includes(selectedCurrency.value)) selectedCurrency.value = currencies[0] })
let loadEpoch = 0
async function load() {
  const epoch = ++loadEpoch
  loading.value = true; error.value = ''; orderNotice.value = ''; unresolvedOrders.value = []
  if (isPreview) {
    const packages = ['GBP', 'USD'].flatMap(currency => [
      { id: `starter-${currency}`, title: '100 Credits', amount: 4.99, currency, credits: 100 },
      { id: `standard-${currency}`, title: '300 Credits', amount: 12.99, currency, credits: 300 },
      { id: `plus-${currency}`, title: '700 Credits', amount: 24.99, currency, credits: 700 },
    ])
    credits.value = { enabled: true, balance: 0, musicCost: 10 }
    catalogs.value = { airwallex: { enabled: false, packages: [] }, paypal: { enabled: true, packages } }
    catalogError.value = false; loading.value = false
    return
  }
  try {
    await ensureCreator()
    if (epoch !== loadEpoch) return
    const creatorId = currentCreatorId()
    if (signedIn.value) {
      const stillPending: Order[] = []
      let paidAny = false
      let historyUnavailable = false
      for (const provider of ['airwallex','paypal'] as const) {
        const base = provider === 'paypal' ? 'app/paypal/orders' : 'app/payments/orders'
        let history: { orders: Order[] }
        try { history = await requestJson<{orders:Order[]}>(base) }
        catch { historyUnavailable = true; continue }
        const unresolved = history.orders.filter(order => order.status !== 'PAID')
        const pending = [...new Set([...unresolved.map(order => order.orderId), ...readPendingOrders(localStorage, creatorId, provider)])]
        const returned = provider === 'paypal' ? paypalReturn(window.location.search) : null
        if (returned && !returned.approved && pending.includes(returned.orderId) && currentCreatorId() === creatorId) {
          savePendingOrders(localStorage, creatorId, readPendingOrders(localStorage, creatorId, 'paypal-approved').filter(id => id !== returned.orderId), 'paypal-approved')
          const clean = new URL(window.location.href); clean.searchParams.delete('paypal_cancel'); clean.searchParams.delete('token'); clean.searchParams.delete('PayerID'); window.history.replaceState(window.history.state, '', clean.pathname + clean.search + clean.hash)
        }
        if (returned && canCapturePaypal(returned, pending, currentCreatorId() === creatorId)) {
          savePendingOrders(localStorage, creatorId, [...readPendingOrders(localStorage, creatorId, 'paypal-approved'), returned.orderId], 'paypal-approved')
          // Remove the return hint before the POST: a network timeout must not trigger an automatic retry.
          const clean = new URL(window.location.href); clean.searchParams.delete('paypal_order'); clean.searchParams.delete('paypal_cancel'); clean.searchParams.delete('token'); clean.searchParams.delete('PayerID'); window.history.replaceState(window.history.state, '', clean.pathname + clean.search + clean.hash)
          try { await requestJson(`${base}/${encodeURIComponent(returned.orderId)}/capture`, 'POST', undefined, 30000) } catch { /* The subsequent status check is authoritative. */ }
        }
        const paid: string[] = []
        for (const id of pending) {
          const existing = unresolved.find(order => order.orderId === id)
          try {
            const result = await requestJson<{orderId:string;status:string;credits:number}>(`${base}/${encodeURIComponent(id)}`)
            if (currentCreatorId() !== creatorId || result.orderId !== id) throw new Error('Identity or order changed')
            if (result.status === 'PAID') { paid.push(id); paidAny = true }
            else stillPending.push({orderId:id, status:result.status as Order['status'], credits:result.credits, createdAt:existing?.createdAt || '',provider})
          } catch { stillPending.push({...existing || {orderId:id,status:'CREATING' as const,credits:0,createdAt:''},provider}) }
        }
        if (currentCreatorId() !== creatorId) return
        if (paid.length) savePendingOrders(localStorage, creatorId, readPendingOrders(localStorage, creatorId, provider).filter(id => !paid.includes(id)),provider)
        if (provider === 'paypal') {
          const approved = readPendingOrders(localStorage, creatorId, 'paypal-approved').filter(id => !paid.includes(id))
          if (paid.length) savePendingOrders(localStorage, creatorId, approved, 'paypal-approved')
          paypalApproved.value = approved
        }
      }
      unresolvedOrders.value = stillPending
      if (epoch !== loadEpoch) return
      purchaseUncertain.value = historyUnavailable
      if (historyUnavailable) orderNotice.value = '暂时无法核对全部订单，请稍后刷新再购买。'
      else if (stillPending.length) orderNotice.value = '有订单仍待确认，请稍后刷新；请先核对付款记录，避免重复支付。'
      else if (paidAny) orderNotice.value = '支付已确认，积分余额已更新。'
    }
    const [c, a, p] = await Promise.allSettled([
      requestJson<Credits>('app/credits'), requestJson<Catalog>('app/payments/catalog'), requestJson<Catalog>('app/paypal/catalog')
    ])
    if (epoch !== loadEpoch) return
    credits.value = c.status === 'fulfilled' ? c.value : undefined
    catalogs.value.airwallex = a.status === 'fulfilled' ? a.value : { enabled: false, packages: [] }
    catalogs.value.paypal = p.status === 'fulfilled' ? p.value : { enabled: false, packages: [] }
    catalogError.value = c.status === 'rejected' || a.status === 'rejected' || p.status === 'rejected'
  } catch { if (epoch === loadEpoch) error.value = '暂时无法读取积分信息，请稍后重试。' }
  finally { if (epoch === loadEpoch) loading.value = false }
}
async function retryPaypal(order: Order) {
  if (isPreview || busy.value || order.provider !== 'paypal') return
  const creatorId = currentCreatorId()
  if (!creatorId || !readPendingOrders(localStorage, creatorId, 'paypal-approved').includes(order.orderId)) return
  busy.value = true
  try { await requestJson(`app/paypal/orders/${encodeURIComponent(order.orderId)}/capture`, 'POST', undefined, 30000) } catch { /* Keep the order pending for later verification. */ }
  finally { busy.value = false }
  if (currentCreatorId() === creatorId) await load()
}
function price(item: Package) {
  try { return new Intl.NumberFormat(locale.value, { style: 'currency', currency: item.currency }).format(item.amount) }
  catch { return `${item.currency} ${item.amount}` }
}
async function buy(item: Package, provider: Provider) {
  if (isPreview) { orderNotice.value = '这是界面预览，不会产生订单或扣款。'; return }
  if (busy.value || purchaseUncertain.value || unresolvedOrders.value.length || !signedIn.value) return
  busy.value = true; error.value = ''
  try {
    await ensureCreator()
    const creatorId = currentCreatorId()
    const order = await requestJson<{orderId: string; checkoutUrl: string}>(provider === 'paypal' ? 'app/paypal/orders' : 'app/payments/orders', 'POST', {packageId: item.id}, 30000)
    if (currentCreatorId() !== creatorId) throw new Error('Identity changed')
    savePendingOrders(localStorage, creatorId, [...readPendingOrders(localStorage, creatorId, provider), order.orderId],provider)
    const url = new URL(order.checkoutUrl)
    const validHost = provider === 'paypal' ? ['www.paypal.com','www.sandbox.paypal.com'].includes(url.hostname) : url.hostname === 'airwallex.com' || url.hostname.endsWith('.airwallex.com')
    if (url.protocol !== 'https:' || url.username || url.password || !validHost || url.port) throw new Error('Invalid checkout URL')
    window.location.assign(url.href)
  } catch {
    purchaseUncertain.value = true
    error.value = '暂时无法确认订单状态，请刷新账户后核对，避免重复付款。'
    busy.value = false
  }
}
onMounted(load)
watch(signedIn, (current, previous) => { if (current !== previous) void load() })
</script>
<template>
  <section class="credits-section" :aria-label="t('创作积分')">
    <div class="credits-head"><div><span class="eyebrow">{{ t('为下一首歌准备') }}</span><h2>{{ t('创作积分') }}</h2><p>{{ t('购买积分，用于创作歌曲。') }}</p></div><button type="button" class="refresh" :disabled="loading || busy" @click="load">{{ t('刷新') }}</button></div>
    <p v-if="loading" class="subtle" role="status">{{ t('正在读取积分…') }}</p>
    <p v-else-if="error" class="error" role="alert">{{ t(error) }}</p>
    <template v-else>
      <div v-if="credits?.enabled" class="balance"><span>{{ t('可用积分') }}</span><strong>{{ credits.balance }}</strong><small>{{ t('每次歌曲生成消耗 {count} 积分', {count: credits.musicCost}) }}</small></div>
      <p v-if="orderNotice" class="subtle" role="status">{{ t(orderNotice) }}</p>
      <p v-if="catalogError" class="subtle" role="status">{{ t('部分积分或支付信息暂时无法读取，请稍后刷新。') }}</p>
      <ul v-if="unresolvedOrders.length" class="orders"><li v-for="order in unresolvedOrders" :key="order.orderId"><span>{{ t(order.status === 'CREATING' ? '订单创建状态待核对' : '订单等待支付确认') }}</span> <code>{{ order.orderId }}</code> <button v-if="order.provider === 'paypal' && paypalApproved.includes(order.orderId)" type="button" class="refresh" :disabled="busy" @click="retryPaypal(order)">{{ t('重新确认支付') }}</button></li></ul>
      <div v-if="!availableCurrencies.length && !catalogError" class="empty-state"><span class="empty-icon" aria-hidden="true">♪</span><div><strong>{{ t('积分购买暂未开放。') }}</strong><p>{{ t('开放后可在这里选购积分。') }}</p></div></div>
      <template v-else-if="availableCurrencies.length">
        <p v-if="!isPreview && !signedIn" class="subtle">{{ t('请先登录账号，再购买积分。') }}</p>
        <div class="selection-head"><div><h3>{{ t('选择积分套餐') }}</h3><p>{{ t('选择适合你的创作计划。') }}</p></div><div class="currency-switch" role="group" :aria-label="t('选择付款币种')"><button v-for="currency in availableCurrencies" :key="currency" type="button" :aria-pressed="selectedCurrency === currency" @click="selectedCurrency = currency">{{ currency }}</button></div></div>
        <section v-for="group in visibleProviders" :key="group.provider" class="provider-section" :aria-label="group.provider === 'paypal' ? 'PayPal' : 'Airwallex'">
          <div class="provider-label">{{ t('支付方式') }} · {{ group.provider === 'paypal' ? 'PayPal' : 'Airwallex' }}</div>
          <div class="packages"><article v-for="item in group.packages" :key="item.id" class="package"><div class="package-top"><span class="package-caption">{{ t('创作积分') }}</span><span aria-hidden="true">✦</span></div><strong>{{ item.credits }} <small>{{ t('积分') }}</small></strong><p class="package-price">{{ price(item) }}</p><button type="button" class="buy-button" :disabled="!isPreview && (busy || purchaseUncertain || unresolvedOrders.length > 0 || !signedIn)" @click="buy(item, group.provider)">{{ busy ? t('正在前往支付…') : t('选择此套餐') }}</button></article></div>
        </section>
        <p class="subtle">{{ t('支付完成后，积分将在确认到账后发放。') }}</p>
      </template>
    </template>
  </section>
</template>
<style scoped>
.credits-section { margin-top:34px; padding:26px; border:1px solid var(--line); border-radius:20px; background:var(--surface); }
.credits-head { display:flex; align-items:start; justify-content:space-between; gap:16px; }
.credits-head h2 { margin:6px 0; font-size:20px; }.credits-head p,.subtle { color:var(--muted); font-size:13px; line-height:1.7; }.credits-head p { margin:0 0 18px; }.eyebrow { color:var(--accent-text); font-size:11px; font-weight:700; letter-spacing:.08em; }
.refresh { color:var(--accent-text); font-size:12px; }.balance { display:flex; align-items:baseline; gap:12px; margin:8px 0 20px; }.balance span,.balance small { color:var(--muted); font-size:12px; }.balance strong { color:var(--accent-text); font-size:28px; }
.orders { padding-left:18px; color:var(--muted); font-size:12px; line-height:1.8; overflow-wrap:anywhere; }
.empty-state { display:flex; gap:15px; align-items:center; padding:18px; background:var(--surface-raised); border:1px solid var(--line); border-radius:14px; }.empty-icon { display:grid; place-items:center; width:42px; height:42px; border-radius:14px; background:#f8e7dd; color:#aa6552; font-size:20px; }.empty-state strong { font-size:13px; }.empty-state p,.selection-head p { margin:5px 0 0; color:var(--muted); font-size:12px; }
.selection-head { display:flex; justify-content:space-between; align-items:center; gap:16px; margin:24px 0 16px; }.selection-head h3 { margin:0; font-size:15px; }.currency-switch { display:flex; padding:3px; border:1px solid var(--line); border-radius:999px; background:var(--surface-raised); }.currency-switch button { padding:7px 12px; border-radius:999px; font-size:12px; font-weight:700; color:var(--muted); }.currency-switch button[aria-pressed=true] { background:var(--surface); color:var(--accent-text); box-shadow:0 2px 8px #47352a16; }
.provider-label { margin:14px 0 9px; color:var(--muted); font-size:12px; font-weight:600; }.packages { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin-bottom:18px; }.package { padding:18px; border:1px solid var(--line); border-radius:15px; background:var(--surface-raised); }.package-top { display:flex; justify-content:space-between; gap:8px; color:var(--accent-text); margin-bottom:14px; }.package-caption { font-size:12px; }.package strong { font-size:25px; }.package small { font-size:12px; font-weight:400; }.package-price { margin:8px 0 18px; color:var(--muted); font-size:14px; }.buy-button { width:100%; padding:10px 12px; border:1px solid var(--line); border-radius:10px; background:var(--surface); font-size:12px; font-weight:700; }.buy-button:disabled,.refresh:disabled { opacity:.5; cursor:not-allowed; }.error { color:var(--danger); font-size:12px; }
@media(max-width:640px){.credits-section{padding:20px;margin-top:28px}.balance{flex-wrap:wrap}.balance small{width:100%}.selection-head{align-items:flex-start;flex-direction:column}.packages{grid-template-columns:1fr}.package{display:grid;grid-template-columns:1fr auto;align-items:center;gap:4px 16px}.package-top{grid-column:1/-1;margin-bottom:2px}.package-price{margin:0}.buy-button{grid-column:1/-1;margin-top:8px}}
</style>
