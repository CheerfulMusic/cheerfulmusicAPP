<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { accountState } from '../../../shared/identity/account-state'
import { announceCreditsChanged } from '../../../shared/identity/credit-balance'
import { logtoState } from '../../../shared/identity/logto-state'
import { t, locale } from '../../../shared/i18n'
import { readPendingOrders, savePendingOrders } from '../model/payment-orders'
import { canCapturePaypal, paypalReturn } from '../model/paypal-return'
import { isPendingOrder, mergeOrderHistory, trustedCheckoutUrl, type Order, type Provider } from '../model/order-history'

const props = withDefaults(defineProps<{ preview?: boolean }>(), { preview: false })
// Preview is only reachable in a development build and must never call the payment API.
const isPreview = import.meta.env.DEV && props.preview

type Package = { id: string; title: string; amount: number; currency: string; credits: number }
type Catalog = { enabled: boolean; packages: Package[] }
type Credits = { enabled: boolean; balance: number; musicCost: number }
const catalogs = ref<Record<Provider, Catalog>>({airwallex:{enabled:false,packages:[]},paypal:{enabled:false,packages:[]}}), credits = ref<Credits>(), loading = ref(true), busy = ref(false), error = ref('')
const purchaseUncertain = ref(false)
const orderNotice = ref('')
const unresolvedOrders = ref<Order[]>([])
const recentOrders = ref<Order[]>([])
const paypalApproved = ref<string[]>([])
const catalogError = ref(false)
const selectedCurrency = 'USD'
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
const availableCurrencies = computed(() => Object.values(catalogs.value).some(c => c.enabled && c.packages.some(p => p.currency === selectedCurrency)) ? ['USD'] : [])
const visibleProviders = computed(() => (['paypal', 'airwallex'] as const).map(provider => ({
  provider, packages: catalogs.value[provider].enabled ? catalogs.value[provider].packages.filter(p => p.currency === selectedCurrency) : []
})).filter(group => group.packages.length > 0))
let loadEpoch = 0
async function load() {
  const epoch = ++loadEpoch
  loading.value = true; error.value = ''; orderNotice.value = ''; unresolvedOrders.value = []; recentOrders.value = []
  if (isPreview) {
    const packages = ['USD'].flatMap(currency => [
      { id: `starter-${currency}`, title: '100 Credits', amount: 9.9, currency, credits: 100 },
      { id: `standard-${currency}`, title: '300 Credits', amount: 25.9, currency, credits: 300 },
      { id: `plus-${currency}`, title: '700 Credits', amount: 49.9, currency, credits: 700 },
    ])
    credits.value = { enabled: true, balance: 0, musicCost: 5 }
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
      let recent: Order[] = []
      let paidAny = false
      let historyUnavailable = false
      for (const provider of ['airwallex','paypal'] as const) {
        const base = provider === 'paypal' ? 'app/paypal/orders' : 'app/payments/orders'
        let history: { orders: Omit<Order, 'provider'>[] }
        try { history = await requestJson<{orders:Omit<Order, 'provider'>[]}>(base) }
        catch { historyUnavailable = true; continue }
        recent = mergeOrderHistory(recent, history.orders.map(order => ({ ...order, provider })))
        const unresolved = history.orders.filter(order => isPendingOrder(order.status))
        const pending = [...new Set([...unresolved.map(order => order.orderId), ...readPendingOrders(localStorage, creatorId, provider)])]
        const returned = provider === 'paypal' ? paypalReturn(window.location.search) : null
        if (returned && !returned.approved && pending.includes(returned.orderId) && currentCreatorId() === creatorId) {
          try { await requestJson(`${base}/${encodeURIComponent(returned.orderId)}/close`, 'POST', undefined, 30000) } catch { /* Keep the order pending until the server confirms its state. */ }
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
            const updated: Order = {orderId:id, status:result.status as Order['status'], credits:result.credits, ...(existing?.createdAt ? {createdAt:existing.createdAt} : {}), provider}
            recent = mergeOrderHistory(recent, [updated])
            if (!isPendingOrder(updated.status)) { paid.push(id); if (result.status === 'PAID') paidAny = true }
            else stillPending.push(updated)
          } catch { const unknown: Order = {...existing || {orderId:id,status:'UNKNOWN' as const},provider}; stillPending.push(unknown); recent = mergeOrderHistory(recent, [unknown]) }
        }
        if (currentCreatorId() !== creatorId) return
        if (paid.length) savePendingOrders(localStorage, creatorId, readPendingOrders(localStorage, creatorId, provider).filter(id => !paid.includes(id)),provider)
        if (provider === 'paypal') {
          const approved = readPendingOrders(localStorage, creatorId, 'paypal-approved').filter(id => !paid.includes(id))
          if (paid.length) savePendingOrders(localStorage, creatorId, approved, 'paypal-approved')
          paypalApproved.value = approved
        }
      }
      if (epoch !== loadEpoch) return
      unresolvedOrders.value = stillPending
      recentOrders.value = recent
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
  try { await requestJson(`app/paypal/orders/${encodeURIComponent(order.orderId)}/capture`, 'POST', undefined, 30000); announceCreditsChanged() } catch { /* Keep the order pending for later verification. */ }
  finally { busy.value = false }
  if (currentCreatorId() === creatorId) await load()
}
async function resumePaypal(order: Order) {
  if (isPreview || busy.value || order.provider !== 'paypal' || order.status !== 'PENDING') return
  const creatorId = currentCreatorId()
  if (!creatorId) return
  busy.value = true; error.value = ''; orderNotice.value = ''
  try {
    const result = await requestJson<{orderId:string;status:'CHECKOUT'|'APPROVED'|'PAID';checkoutUrl?:string}>(`app/paypal/orders/${encodeURIComponent(order.orderId)}/resume`, 'POST', undefined, 60000)
    if (creatorId !== currentCreatorId() || result.orderId !== order.orderId) throw new Error('Identity or order changed')
    if (result.status === 'CHECKOUT' && result.checkoutUrl) { window.location.assign(trustedCheckoutUrl(result.checkoutUrl, 'paypal')); return }
    if (result.status === 'APPROVED') {
      const approved = [...readPendingOrders(localStorage, creatorId, 'paypal-approved'), order.orderId]
      savePendingOrders(localStorage, creatorId, approved, 'paypal-approved')
      paypalApproved.value = approved
      orderNotice.value = 'PayPal 已授权，请点击重新确认支付以完成付款。'
    } else if (result.status === 'PAID') {
      announceCreditsChanged()
      await load()
    } else { throw new Error('Unknown status') }
  } catch { orderNotice.value = '暂时无法恢复支付，请稍后刷新并核对订单状态。' }
  finally { busy.value = false }
}
async function cancelPaypal(order: Order) {
  if (isPreview || busy.value || order.provider !== 'paypal' || order.status !== 'PENDING') return
  const creatorId = currentCreatorId()
  busy.value = true
  let failed = false
  try { await requestJson(`app/paypal/orders/${encodeURIComponent(order.orderId)}/close`, 'POST', undefined, 30000) }
  catch { failed = true }
  finally { busy.value = false }
  if (currentCreatorId() === creatorId) await load()
  if (failed && currentCreatorId() === creatorId) orderNotice.value = '取消未成功，订单可能已授权或状态尚未确认；请刷新核对，勿重复付款。'
}
function price(item: Package) {
  try { return new Intl.NumberFormat(locale.value, { style: 'currency', currency: item.currency }).format(item.amount) }
  catch { return `${item.currency} ${item.amount}` }
}
function orderTime(value?: string) {
  if (!value || Number.isNaN(Date.parse(value))) return ''
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
function orderStatus(status: Order['status']) {
  return status === 'PAID' ? '支付已确认' : status === 'CANCELLED' ? '已取消支付' : status === 'FAILED' ? '支付失败' : status === 'PENDING' ? '订单等待支付确认' : status === 'CREATING' ? '订单创建状态待核对' : '订单状态暂无法确认'
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
    window.location.assign(trustedCheckoutUrl(order.checkoutUrl, provider))
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
  <section id="payment-orders" class="credits-section" :aria-label="t('创作积分')">
    <div class="credits-head"><div><span class="eyebrow">{{ t('为下一首歌准备') }}</span><h2>{{ t('创作积分') }}</h2><p>{{ t('购买积分，用于创作歌曲。') }}</p></div><button type="button" class="refresh" :disabled="loading || busy" @click="load">{{ t('刷新') }}</button></div>
    <p v-if="loading" class="subtle" role="status">{{ t('正在读取积分…') }}</p>
    <p v-else-if="error" class="error" role="alert">{{ t(error) }}</p>
    <template v-else>
      <div v-if="credits && (signedIn || credits.enabled)" class="balance"><span>{{ t('可用积分') }}</span><strong>{{ credits.balance }}</strong><small v-if="credits.enabled">{{ t('每次歌曲生成消耗 {count} 积分', {count: credits.musicCost}) }}</small></div>
      <p v-if="orderNotice" class="subtle" role="status">{{ t(orderNotice) }}</p>
      <p v-if="catalogError" class="subtle" role="status">{{ t('部分积分或支付信息暂时无法读取，请稍后刷新。') }}</p>
      <section v-if="signedIn" class="history" :aria-label="t('最近订单')">
        <h3>{{ t('我的订单') }}</h3><p class="history-help">{{ t('待付款订单可继续支付或申请取消；是否可取消由服务端核对付款状态。') }}</p>
        <p v-if="!recentOrders.length" class="subtle">{{ t(purchaseUncertain ? '订单暂时无法读取，请稍后刷新。' : '暂无订单。') }}</p>
        <ul v-else class="orders"><li v-for="order in recentOrders" :key="`${order.provider}:${order.orderId}`">
          <div><strong>{{ t(orderStatus(order.status)) }}</strong> · {{ order.provider === 'paypal' ? 'PayPal' : 'Airwallex' }}<template v-if="order.credits != null"> · {{ order.credits }} {{ t('积分') }}</template><template v-if="orderTime(order.createdAt)"> · {{ orderTime(order.createdAt) }}</template></div>
          <div class="order-number">{{ t('订单号') }}：<code>{{ order.orderId }}</code></div>
          <div v-if="isPendingOrder(order.status) && order.provider === 'paypal'" class="order-actions">
            <button v-if="paypalApproved.includes(order.orderId)" type="button" class="order-action primary" :disabled="busy" @click="retryPaypal(order)">{{ t('重新确认支付') }}</button>
            <button v-else-if="order.status === 'PENDING'" type="button" class="order-action primary" :disabled="busy" @click="resumePaypal(order)">{{ t('继续支付') }}</button>
            <button v-if="order.status === 'PENDING'" type="button" class="order-action cancel-order" :disabled="busy" @click="cancelPaypal(order)">{{ t('取消订单') }}</button>
            <p v-else class="subtle">{{ t('订单创建结果尚未确认，请刷新核对，暂不能直接取消。') }}</p>
          </div>
        </li></ul>
      </section>
      <div v-if="!availableCurrencies.length && !catalogError" class="empty-state"><span class="empty-icon" aria-hidden="true">♪</span><div><strong>{{ t('积分购买暂未开放。') }}</strong><p>{{ t('开放后可在这里选购积分。') }}</p></div></div>
      <template v-else-if="availableCurrencies.length">
        <p v-if="!isPreview && !signedIn" class="subtle">{{ t('请先登录账号，再购买积分。') }}</p>
        <div class="selection-head"><div><h3>{{ t('选择积分套餐') }}</h3><p>{{ t('选择适合你的创作计划。') }}</p></div><span class="currency-label">USD $</span></div>
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
#payment-orders{scroll-margin-top:110px}.history{padding:22px;border:2px solid #ee8069;border-radius:16px;background:#fff8f4}.history h3{font-size:21px!important}.history-help{font-size:13px;color:var(--muted);line-height:1.7}.history .orders li{padding:18px;background:var(--surface)}.order-number{margin-top:8px}.order-actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:14px}.order-action{min-height:44px;padding:10px 22px;border-radius:10px;border:1px solid #e27660;font-size:14px;font-weight:700;cursor:pointer}.order-action.primary{background:#ed614b;color:#fff}.order-action.cancel-order{background:var(--surface);color:#b94432}.order-action:disabled{opacity:.5;cursor:wait}

.credits-section { margin-top:34px; padding:26px; border:1px solid var(--line); border-radius:20px; background:var(--surface); }
.credits-head { display:flex; align-items:start; justify-content:space-between; gap:16px; }
.credits-head h2 { margin:6px 0; font-size:20px; }.credits-head p,.subtle { color:var(--muted); font-size:13px; line-height:1.7; }.credits-head p { margin:0 0 18px; }.eyebrow { color:var(--accent-text); font-size:11px; font-weight:700; letter-spacing:.08em; }
.refresh { color:var(--accent-text); font-size:12px; }.balance { display:flex; align-items:baseline; gap:12px; margin:8px 0 20px; }.balance span,.balance small { color:var(--muted); font-size:12px; }.balance strong { color:var(--accent-text); font-size:28px; }
.history { margin:20px 0; }.history h3 { font-size:15px; margin:0 0 10px; }.orders { list-style:none; padding:0; color:var(--muted); font-size:12px; line-height:1.8; overflow-wrap:anywhere; }.orders li { padding:10px 12px; margin:8px 0; border:1px solid var(--line); border-radius:10px; }.orders strong { color:var(--text); }
.empty-state { display:flex; gap:15px; align-items:center; padding:18px; background:var(--surface-raised); border:1px solid var(--line); border-radius:14px; }.empty-icon { display:grid; place-items:center; width:42px; height:42px; border-radius:14px; background:#f8e7dd; color:#aa6552; font-size:20px; }.empty-state strong { font-size:13px; }.empty-state p,.selection-head p { margin:5px 0 0; color:var(--muted); font-size:12px; }
.selection-head { display:flex; justify-content:space-between; align-items:center; gap:16px; margin:24px 0 16px; }.selection-head h3 { margin:0; font-size:15px; }.currency-switch { display:flex; padding:3px; border:1px solid var(--line); border-radius:999px; background:var(--surface-raised); }.currency-switch button { padding:7px 12px; border-radius:999px; font-size:12px; font-weight:700; color:var(--muted); }.currency-switch button[aria-pressed=true] { background:var(--surface); color:var(--accent-text); box-shadow:0 2px 8px #47352a16; }
.provider-label { margin:14px 0 9px; color:var(--muted); font-size:12px; font-weight:600; }.packages { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin-bottom:18px; }.package { padding:18px; border:1px solid var(--line); border-radius:15px; background:var(--surface-raised); }.package-top { display:flex; justify-content:space-between; gap:8px; color:var(--accent-text); margin-bottom:14px; }.package-caption { font-size:12px; }.package strong { font-size:25px; }.package small { font-size:12px; font-weight:400; }.package-price { margin:8px 0 18px; color:var(--muted); font-size:14px; }.buy-button { width:100%; padding:10px 12px; border:1px solid var(--line); border-radius:10px; background:var(--surface); font-size:12px; font-weight:700; }.buy-button:disabled,.refresh:disabled { opacity:.5; cursor:not-allowed; }.error { color:var(--danger); font-size:12px; }
@media(max-width:640px){.credits-section{padding:20px;margin-top:28px}.balance{flex-wrap:wrap}.balance small{width:100%}.selection-head{align-items:flex-start;flex-direction:column}.packages{grid-template-columns:1fr}.package{display:grid;grid-template-columns:1fr auto;align-items:center;gap:4px 16px}.package-top{grid-column:1/-1;margin-bottom:2px}.package-price{margin:0}.buy-button{grid-column:1/-1;margin-top:8px}}
</style>
