<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { accountState } from '../../../shared/identity/account-state'
import { announceCreditsChanged } from '../../../shared/identity/credit-balance'
import { logtoState } from '../../../shared/identity/logto-state'
import { t, locale } from '../../../shared/i18n'
import { readPendingOrders, savePendingOrders } from '../model/payment-orders'
import { canCapturePaypal, paypalReturn } from '../model/paypal-return'
import { isPendingOrder, mergeOrderHistory, orderAmount, trustedCheckoutUrl, type Order } from '../model/order-history'

const props = withDefaults(defineProps<{ preview?: boolean }>(), { preview: false })
// Preview is only reachable in a development build and must never call the payment API.
const isPreview = import.meta.env.DEV && props.preview

type Credits = { enabled: boolean; balance: number; musicCost: number }
const previewPackages = [
  { credits: 100, amount: 9.9 },
  { credits: 300, amount: 25.9 },
  { credits: 700, amount: 49.9 },
]
const credits = ref<Credits>(), loading = ref(true), busy = ref(false), error = ref('')
const historyUnavailable = ref(false)
const orderNotice = ref('')
const recentOrders = ref<Order[]>([])
const orderPeriod = ref<number>(0)
const visibleOrders = computed(() => {
  if (!orderPeriod.value) return recentOrders.value
  const now = Date.now()
  const cutoff = now - orderPeriod.value * 24 * 60 * 60 * 1000
  return recentOrders.value.filter(order => {
    const created = Date.parse(order.createdAt ?? '')
    return created >= cutoff && created <= now
  })
})
const paypalApproved = ref<string[]>([])
const balanceUnavailable = ref(false)
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
let loadEpoch = 0
async function load() {
  const epoch = ++loadEpoch
  loading.value = true; error.value = ''; orderNotice.value = ''; historyUnavailable.value = false; recentOrders.value = []
  if (isPreview) {
    credits.value = { enabled: true, balance: 0, musicCost: 5 }
    loading.value = false
    return
  }
  try {
    await ensureCreator()
    if (epoch !== loadEpoch) return
    const creatorId = currentCreatorId()
    if (signedIn.value) {
      let stillPending = false
      let recent: Order[] = []
      let paidAny = false
      let failedHistory = false
      for (const provider of ['airwallex','paypal','stripe'] as const) {
        const base = provider === 'paypal' ? 'app/paypal/orders' : provider === 'stripe' ? 'app/stripe/orders' : 'app/payments/orders'
        let history: { orders: Omit<Order, 'provider'>[] }
        try { history = await requestJson<{orders:Omit<Order, 'provider'>[]}>(base) }
        catch { failedHistory = true; continue }
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
            const result = await requestJson<{orderId:string;status:string;credits:number;amount?:number;currency?:string;packageId?:string;paymentMethod?:Order['paymentMethod']}>(`${base}/${encodeURIComponent(id)}`)
            if (currentCreatorId() !== creatorId || result.orderId !== id) throw new Error('Identity or order changed')
            const updated: Order = {...existing, orderId:id, status:result.status as Order['status'], credits:result.credits, ...(result.amount !== undefined && result.currency ? {amount:result.amount,currency:result.currency} : {}), ...(result.packageId ? {packageId:result.packageId} : {}), ...(result.paymentMethod ? {paymentMethod:result.paymentMethod} : {}), provider}
            recent = mergeOrderHistory(recent, [updated])
            if (!isPendingOrder(updated.status)) { paid.push(id); if (result.status === 'PAID') paidAny = true }
            else stillPending = true
          } catch { const unknown: Order = {...existing || {orderId:id,status:'UNKNOWN' as const},provider}; stillPending = true; recent = mergeOrderHistory(recent, [unknown]) }
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
      recentOrders.value = recent
      historyUnavailable.value = failedHistory
      if (failedHistory) orderNotice.value = '暂时无法核对全部订单，请稍后刷新再购买。'
      else if (stillPending) orderNotice.value = '有订单仍待确认，请稍后刷新；请先核对付款记录，避免重复支付。'
      else if (paidAny) orderNotice.value = '支付已确认，积分余额已更新。'
    }
    const balance = await requestJson<Credits>('app/credits').catch(() => undefined)
    if (epoch !== loadEpoch) return
    credits.value = balance
    balanceUnavailable.value = balance === undefined
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
async function resumeStripe(order: Order) {
  if (isPreview || busy.value || order.provider !== 'stripe' || !isPendingOrder(order.status)) return
  const creatorId = currentCreatorId()
  if (!creatorId) return
  busy.value = true; error.value = ''; orderNotice.value = ''
  try {
    const result = await requestJson<{orderId:string;status:string;checkoutUrl?:string}>(`app/stripe/orders/${encodeURIComponent(order.orderId)}/resume`, 'POST', undefined, 60000)
    if (creatorId !== currentCreatorId() || result.orderId !== order.orderId) throw new Error('Identity or order changed')
    if (result.checkoutUrl) { window.location.assign(trustedCheckoutUrl(result.checkoutUrl, 'stripe')); return }
    if (result.status === 'PAID') { announceCreditsChanged(); await load(); return }
    orderNotice.value = '订单结果仍待核对，请勿重复付款。'
  } catch { orderNotice.value = '暂时无法恢复 Stripe 支付，请稍后刷新并核对订单状态。' }
  finally { busy.value = false }
  if (currentCreatorId() === creatorId) await load()
}
async function cancelStripe(order: Order) {
  if (isPreview || busy.value || order.provider !== 'stripe' || order.status !== 'PENDING') return
  const creatorId = currentCreatorId()
  if (!creatorId || !window.confirm(t('确认取消此订单？已付款或支付处理中的订单不会取消。'))) return
  busy.value = true; error.value = ''; orderNotice.value = ''
  let notice = '取消未成功，订单可能已授权或状态尚未确认；请刷新核对，勿重复付款。'
  try {
    const result = await requestJson<{orderId:string;status:string}>(`app/stripe/orders/${encodeURIComponent(order.orderId)}/cancel`, 'POST', undefined, 60000)
    if (currentCreatorId() !== creatorId || result.orderId !== order.orderId) return
    if (result.status === 'CANCELLED') notice = '订单已取消，原支付链接已关闭。'
    else if (result.status === 'PAID') { notice = '订单已付款，不能取消；积分余额已更新。'; announceCreditsChanged() }
  } catch { /* Unknown outcomes remain visible until authoritative reconciliation. */ }
  finally { busy.value = false }
  if (currentCreatorId() === creatorId) {
    await load()
    if (currentCreatorId() === creatorId) orderNotice.value = notice
  }
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
const previewPrice = (amount: number) => new Intl.NumberFormat(locale.value, { style: 'currency', currency: 'USD' }).format(amount)
function orderTime(value?: string) {
  if (!value || Number.isNaN(Date.parse(value))) return ''
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
function orderStatus(status: Order['status']) {
  return status === 'PAID' ? '支付已确认' : status === 'CANCELLED' ? '已取消支付' : status === 'FAILED' ? '支付失败' : status === 'PENDING' ? '订单等待支付确认' : status === 'CREATING' ? '订单创建状态待核对' : '订单状态暂无法确认'
}
onMounted(load)
watch(signedIn, (current, previous) => { if (current !== previous) void load() })
</script>
<template>
  <section id="payment-orders" class="credits-section" :aria-label="t('创作积分')">
    <div class="credits-head"><div><span class="eyebrow">{{ t('为下一首歌准备') }}</span><h2>{{ t('创作积分') }}</h2><p>{{ t('购买积分，用于创作歌曲。') }}</p></div><button type="button" class="refresh" :disabled="loading || busy" @click="load">{{ t('刷新') }}</button></div>
    <div v-if="!isPreview" class="recharge-entry">
      <RouterLink class="recharge-link" to="/recharge">{{ t('充值积分') }}</RouterLink>
    </div>
    <p v-if="loading" class="subtle" role="status">{{ t('正在读取积分…') }}</p>
    <p v-else-if="error" class="error" role="alert">{{ t(error) }}</p>
    <template v-else>
      <div v-if="credits && (signedIn || credits.enabled)" class="balance"><span>{{ t('可用积分') }}</span><strong>{{ credits.balance }}</strong><small v-if="credits.enabled">{{ t('每次歌曲生成消耗 {count} 积分', {count: credits.musicCost}) }}</small></div>
      <p v-if="orderNotice" class="subtle" role="status">{{ t(orderNotice) }}</p>
      <p v-if="balanceUnavailable" class="subtle" role="status">{{ t('暂时无法读取积分信息，请稍后重试。') }}</p>
      <details v-if="signedIn" class="history">
        <summary>{{ t('我的订单') }}</summary>
        <p class="history-help">{{ t('待付款订单可继续支付或申请取消；是否可取消由服务端核对付款状态。') }}</p>
        <label v-if="recentOrders.length" class="order-period">
          {{ t('下单时间') }}
          <select v-model="orderPeriod">
            <option :value="0">{{ t('全部时间') }}</option>
            <option v-for="days in [7, 30, 90]" :key="days" :value="days">{{ t('最近 {count} 天', { count: days }) }}</option>
          </select>
        </label>
        <p v-if="!recentOrders.length" class="subtle">{{ t(historyUnavailable ? '订单暂时无法读取，请稍后刷新。' : '暂无订单。') }}</p>
        <p v-else-if="!visibleOrders.length" class="subtle" role="status">{{ t('这段时间内暂无订单。') }}</p>
        <ul v-else class="orders"><li v-for="order in visibleOrders" :key="`${order.provider}:${order.orderId}`">
          <div><strong>{{ t(orderStatus(order.status)) }}</strong> · {{ order.provider === 'paypal' ? 'PayPal' : order.provider === 'stripe' ? 'Stripe' : 'Airwallex' }}<template v-if="order.credits != null"> · {{ order.credits }} {{ t('积分') }}</template><template v-if="orderTime(order.createdAt)"> · {{ orderTime(order.createdAt) }}</template></div>
          <div v-if="orderAmount(order)" class="order-amount">{{ orderAmount(order) }}<span v-if="order.paymentMethod==='wechat_pay'"> · {{ locale==='en'?'WeChat Pay':'微信支付' }}</span></div>
          <div class="order-number">{{ t('订单号') }}：<code>{{ order.orderId }}</code></div>
          <div v-if="isPendingOrder(order.status) && order.provider === 'paypal'" class="order-actions">
            <button v-if="paypalApproved.includes(order.orderId)" type="button" class="order-action primary" :disabled="busy" @click="retryPaypal(order)">{{ t('重新确认支付') }}</button>
            <button v-else-if="order.status === 'PENDING'" type="button" class="order-action primary" :disabled="busy" @click="resumePaypal(order)">{{ t('继续支付') }}</button>
            <button v-if="order.status === 'PENDING'" type="button" class="order-action cancel-order" :disabled="busy" @click="cancelPaypal(order)">{{ t('取消订单') }}</button>
            <p v-else class="subtle">{{ t('订单创建结果尚未确认，请刷新核对，暂不能直接取消。') }}</p>
          </div>
          <div v-if="isPendingOrder(order.status) && order.provider === 'stripe'" class="order-actions">
            <button type="button" class="order-action primary" :disabled="busy" @click="resumeStripe(order)">{{ t('继续核对 Stripe 订单') }}</button>
            <button v-if="order.status === 'PENDING'" type="button" class="order-action cancel-order" :disabled="busy" @click="cancelStripe(order)">{{ t('取消订单') }}</button>
            <p v-else class="subtle">{{ t('订单创建结果尚未确认，请刷新核对，暂不能直接取消。') }}</p>
          </div>
        </li></ul>
      </details>
      <template v-if="isPreview">
        <div class="selection-head"><div><h3>{{ t('选择积分套餐') }}</h3><p>{{ t('选择适合你的创作计划。') }}</p></div><span class="currency-label">USD $</span></div>
        <section class="provider-section" aria-label="PayPal">
          <div class="provider-label">{{ t('支付方式') }} · PayPal</div>
          <div class="packages">
            <article v-for="item in previewPackages" :key="item.credits" class="package">
              <div class="package-top"><span class="package-caption">{{ t('创作积分') }}</span><span aria-hidden="true">✦</span></div>
              <strong>{{ item.credits }} <small>{{ t('积分') }}</small></strong>
              <p class="package-price">{{ previewPrice(item.amount) }}</p>
              <button type="button" class="buy-button" @click="orderNotice = '这是界面预览，不会产生订单或扣款。'">{{ t('选择此套餐') }}</button>
            </article>
          </div>
        </section>
        <p class="subtle">{{ t('支付完成后，积分将在确认到账后发放。') }}</p>
      </template>
    </template>
  </section>
</template>
<style scoped>
.history summary { cursor: pointer; font-size: 21px; font-weight: 700; }
.order-period { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 13px; color: var(--text); }
.order-period select { min-height: 44px; padding: 8px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--text); font: inherit; }
#payment-orders{scroll-margin-top:110px}.history{padding:22px;border:2px solid #ee8069;border-radius:16px;background:#fff8f4}.history h3{font-size:21px!important}.history-help{font-size:13px;color:var(--muted);line-height:1.7}.history .orders li{padding:18px;background:var(--surface)}.order-number{margin-top:8px}.order-actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:14px}.order-action{min-height:44px;padding:10px 22px;border-radius:10px;border:1px solid #e27660;font-size:14px;font-weight:700;cursor:pointer}.order-action.primary{background:#ed614b;color:#fff}.order-action.cancel-order{background:var(--surface);color:#b94432}.order-action:disabled{opacity:.5;cursor:wait}

.credits-section { margin-top:34px; padding:26px; border:1px solid var(--line); border-radius:20px; background:var(--surface); }
.credits-head { display:flex; align-items:start; justify-content:space-between; gap:16px; }
.recharge-entry { margin-bottom:20px; }
.recharge-link { display:inline-flex; align-items:center; min-height:44px; padding:10px 22px; border-radius:10px; background:var(--accent); color:var(--on-accent); font-weight:700; }
.recharge-link:hover { background:#e94935; }
.credits-head h2 { margin:6px 0; font-size:20px; }.credits-head p,.subtle { color:var(--muted); font-size:13px; line-height:1.7; }.credits-head p { margin:0 0 18px; }.eyebrow { color:var(--accent-text); font-size:11px; font-weight:700; letter-spacing:.08em; }
.refresh { color:var(--accent-text); font-size:12px; }.balance { display:flex; align-items:baseline; gap:12px; margin:8px 0 20px; }.balance span,.balance small { color:var(--muted); font-size:12px; }.balance strong { color:var(--accent-text); font-size:28px; }
.history { margin:20px 0; }.history h3 { font-size:15px; margin:0 0 10px; }.orders { list-style:none; padding:0; color:var(--muted); font-size:12px; line-height:1.8; overflow-wrap:anywhere; }.orders li { padding:10px 12px; margin:8px 0; border:1px solid var(--line); border-radius:10px; }.orders strong { color:var(--text); }
.selection-head p { margin:5px 0 0; color:var(--muted); font-size:12px; }
.selection-head { display:flex; justify-content:space-between; align-items:center; gap:16px; margin:24px 0 16px; }.selection-head h3 { margin:0; font-size:15px; }
.provider-label { margin:14px 0 9px; color:var(--muted); font-size:12px; font-weight:600; }.packages { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin-bottom:18px; }.package { padding:18px; border:1px solid var(--line); border-radius:15px; background:var(--surface-raised); }.package-top { display:flex; justify-content:space-between; gap:8px; color:var(--accent-text); margin-bottom:14px; }.package-caption { font-size:12px; }.package strong { font-size:25px; }.package small { font-size:12px; font-weight:400; }.package-price { margin:8px 0 18px; color:var(--muted); font-size:14px; }.buy-button { width:100%; padding:10px 12px; border:1px solid var(--line); border-radius:10px; background:var(--surface); font-size:12px; font-weight:700; }.buy-button:disabled,.refresh:disabled { opacity:.5; cursor:not-allowed; }.error { color:var(--danger); font-size:12px; }
@media(max-width:640px){.credits-section{padding:20px;margin-top:28px}.balance{flex-wrap:wrap}.balance small{width:100%}.selection-head{align-items:flex-start;flex-direction:column}.packages{grid-template-columns:1fr}.package{display:grid;grid-template-columns:1fr auto;align-items:center;gap:4px 16px}.package-top{grid-column:1/-1;margin-bottom:2px}.package-price{margin:0}.buy-button{grid-column:1/-1;margin-top:8px}}
</style>
