<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { locale } from '../../../shared/i18n'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { accountState, refreshAccountState } from '../../../shared/identity/account-state'
import { announceCreditsChanged } from '../../../shared/identity/credit-balance'
import { logtoState, refreshLogtoState } from '../../../shared/identity/logto-state'
import { readPendingOrders, savePendingOrders } from '../model/payment-orders'
import { preparePaypalCheckout, type PaypalCheckout, type PaypalSdkConfig } from '../api/paypal-sdk'
import { isPendingOrder, trustedCheckoutUrl, type OrderStatus } from '../model/order-history'
import { paymentCurrencySymbol, preferredCurrency, stripeMethods, stripeOrderRequest, type CreditPack, type PaymentCatalog, type PaymentCurrency, type StripeCatalog, type StripePaymentMethodId } from '../model/stripe-payment-methods'

const props = defineProps<{ embedded?: boolean }>()
const emit = defineEmits<{ login: []; orders: [] }>()

type PaymentChoice = 'paypal' | StripePaymentMethodId
const en = computed(() => locale.value === 'en')
const signedIn = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
const currency = ref<PaymentCurrency>('USD'), selected = ref(300), busy = ref(false), error = ref(''), paymentChoice = ref<PaymentChoice>('paypal')
const loading = ref(true), paypalAvailable = ref(false), paypalCatalog = ref<CreditPack[]>([]), stripeCatalog = ref<StripeCatalog>({ enabled:false, packages:[] }), blocked = ref(false), balance = ref<number|null>(null), musicCost = ref<number|null>(null)
const sdk = shallowRef<PaypalCheckout|null>(null)
const methods = computed(() => stripeMethods(stripeCatalog.value))
const currentMethod = computed(() => methods.value.find(method => method.id === paymentChoice.value))
const provider = computed(() => paymentChoice.value === 'paypal' ? 'paypal' : 'stripe')
const available = computed(() => provider.value === 'paypal' ? paypalAvailable.value : !!currentMethod.value?.enabled)
const catalog = computed(() => provider.value === 'paypal' ? paypalCatalog.value : currentMethod.value?.packages ?? [])
const chosen = computed(() => catalog.value.find(pack => pack.currency === currency.value && pack.credits === selected.value))
const tiers = computed(() => [100,300,700].map(credits => {
  const real = catalog.value.find(pack => pack.currency === currency.value && pack.credits === credits)
  return { credits, amount:real?.amount ?? null, real:!!real }
}))
const currencyOptions = computed(() => provider.value === 'paypal'
  ? [{ currency:'USD' as const, enabled:true, reason:'' }]
  : currentMethod.value?.currencyOptions ?? [])
const symbol = computed(() => paymentCurrencySymbol(currency.value))
const songCount = (credits:number) => musicCost.value !== null && musicCost.value > 0 ? Math.floor(credits / musicCost.value) : null
const price = computed(() => chosen.value?.amount.toFixed(2) ?? '—')
function selectPayment(choice: PaymentChoice) {
  if (busy.value) return
  paymentChoice.value = choice
  currency.value = choice === 'paypal' ? 'USD' : preferredCurrency(methods.value.find(method => method.id === choice), currency.value)
}
function selectCurrency(value: PaymentCurrency) {
  if (!busy.value && currencyOptions.value.some(option => option.currency === value && option.enabled)) currency.value = value
}
function methodName(id: StripePaymentMethodId) {
  return id === 'apple_pay' ? 'Apple Pay' : id === 'wechat_pay' ? (en.value ? 'WeChat Pay' : '微信支付') : (en.value ? 'International credit / debit card' : '国际信用卡 / 借记卡')
}
function methodDescription(id: StripePaymentMethodId) {
  if (id === 'apple_pay') return en.value ? 'Shown by Stripe on compatible devices; card payment remains available.' : '在 Stripe 收银台按兼容设备显示，也可选择银行卡付款。'
  if (id === 'wechat_pay') return en.value ? 'Scan or authorize in WeChat. The displayed currency is the order currency.' : '在微信扫码或授权付款，以下价格为实际订单币种报价。'
  return en.value ? 'Secure card checkout powered by Stripe' : '使用 Stripe 安全处理银行卡付款'
}
function availabilityMessage(reason?: string) {
  if (reason === 'METHOD_DISABLED') return en.value ? 'This payment method is awaiting activation.' : '此支付方式待开通，当前可查看报价。'
  if (reason === 'ACCOUNT_COUNTRY_UNSUPPORTED') return en.value ? 'This payment method is not supported for the merchant account location.' : '当前商户账户地区暂不支持此支付方式。'
  if (reason === 'METHOD_METADATA_UNAVAILABLE') return en.value ? 'This payment method has not been confirmed available.' : '服务端尚未确认此支付方式可用。'
  return en.value ? 'Payments are not open yet. Server prices are shown for reference.' : '支付暂未开通，以下显示服务端报价。'
}
async function refresh() {
  loading.value=true; error.value=''; blocked.value=false
  try {
    await ensureCreator(); await Promise.all([refreshAccountState().catch(() => undefined), refreshLogtoState().catch(() => undefined)])
    const [p,s,b] = await Promise.allSettled([
      requestJson<PaymentCatalog>('app/paypal/catalog'),requestJson<StripeCatalog>('app/stripe/catalog'),requestJson<{enabled:boolean;balance:number;musicCost:number}>('app/credits')
    ])
    paypalAvailable.value=p.status==='fulfilled'&&p.value.enabled
    paypalCatalog.value=p.status==='fulfilled'?p.value.packages.filter(pack=>pack.currency==='USD'):[]
    stripeCatalog.value=s.status==='fulfilled'?s.value:{enabled:false,packages:[]}
    if(!paypalAvailable.value && paymentChoice.value==='paypal') {
      const first = methods.value.find(method => method.enabled) ?? methods.value.find(method => method.id==='card')
      if(first)selectPayment(first.id)
    }
    if(b.status==='fulfilled') { musicCost.value=Number.isFinite(b.value.musicCost) && b.value.musicCost > 0 ? b.value.musicCost : null; if(b.value.enabled) balance.value=b.value.balance }
    if(signedIn.value && (paypalAvailable.value||methods.value.some(method => method.enabled))) {
      const endpoints=['app/paypal/orders','app/stripe/orders','app/payments/orders']
      const histories=await Promise.allSettled(endpoints.map(url=>requestJson<{orders:{orderId:string;status:string}[]}>(url)))
      let hasUnresolved=false, stripePaid=false
      for(let i=0;i<histories.length;i++) {
        const history=histories[i]
        if(history.status==='rejected'){hasUnresolved=true;continue}
        for(const order of history.value.orders.filter(order=>isPendingOrder(order.status as OrderStatus))) {
          if(endpoints[i]==='app/stripe/orders') {
            try {
              const current=await requestJson<{orderId:string;status:string}>(`app/stripe/orders/${encodeURIComponent(order.orderId)}`)
              if(current.status==='PAID') stripePaid=true
              else hasUnresolved=true
            } catch { hasUnresolved=true }
          } else hasUnresolved=true
        }
      }
      blocked.value=hasUnresolved
      if(stripePaid){announceCreditsChanged();try{const fresh=await requestJson<{enabled:boolean;balance:number}>('app/credits');if(fresh.enabled)balance.value=fresh.balance}catch{}}
    }
    if (paypalAvailable.value) {
      try {
        const config=await requestJson<PaypalSdkConfig>('app/paypal/sdk-config')
        sdk.value=await preparePaypalCheckout(config, {
          onApproved: async id => {
            try { await requestJson(`app/paypal/orders/${encodeURIComponent(id)}/capture`,'POST',undefined,30000); announceCreditsChanged(); await refresh() }
            catch { blocked.value=true; error.value=en.value?'Payment is being checked. Visit your account before trying again.':'付款正在核对，请前往账号页查看，避免重复支付。' }
          },
          onCancel: async id => {
            try { await requestJson(`app/paypal/orders/${encodeURIComponent(id)}/close`,'POST',undefined,30000); await refresh() }
            catch { blocked.value=true; error.value=en.value?'The order needs checking. Visit your account before trying again.':'订单仍待核对，请先前往账号页查看。' }
          },
          onError: () => { blocked.value=true; error.value=en.value?'The payment status needs checking. Visit your account.':'支付状态需要核对，请前往账号页查看。' },
        })
      } catch { sdk.value=null }
    }
  } catch { blocked.value=true }
  finally { loading.value=false }
}
async function checkout() {
  if(busy.value || !available.value || !chosen.value || blocked.value || !signedIn.value) return
  busy.value=true; error.value=''
  try {
    const owner=currentCreatorId(), pack=chosen.value
    if(provider.value==='stripe') {
      const order=await requestJson<{orderId:string;checkoutUrl:string}>('app/stripe/orders','POST',stripeOrderRequest(currentMethod.value,pack,currency.value),30000)
      if(!owner || owner!==currentCreatorId()) throw new Error('identity changed')
      savePendingOrders(localStorage,owner,[...readPendingOrders(localStorage,owner,'stripe'),order.orderId],'stripe')
      window.location.assign(trustedCheckoutUrl(order.checkoutUrl,'stripe'))
      return
    }
    const createOrder=async () => {
      const order=await requestJson<{orderId:string;checkoutUrl:string;paypalOrderId:string}>('app/paypal/orders','POST',{packageId:pack.id},30000)
      if(!owner || owner!==currentCreatorId()) throw new Error('identity changed')
      savePendingOrders(localStorage,owner,[...readPendingOrders(localStorage,owner,'paypal'),order.orderId],'paypal')
      return order
    }
    // Do not allow another order while the PayPal session is awaiting approval.
    if (provider.value==='paypal'&&sdk.value) { blocked.value=true; await sdk.value.start(createOrder); return }
    const order=await createOrder()
    const url=new URL(order.checkoutUrl)
    if(url.protocol!=='https:' || !['www.paypal.com','www.sandbox.paypal.com'].includes(url.hostname) || url.username || url.password || url.port) throw new Error('invalid url')
    window.location.assign(url.href)
  } catch { blocked.value=true; error.value=en.value?'We could not confirm the order. Check your account before trying again.':'订单状态暂时无法确认，请先到账号页核对，避免重复付款。' }
  finally { busy.value=false }
}
onMounted(refresh)
</script>
<template>
  <div class="recharge">
    <div class="recharge-intro">
      <div><p class="eyebrow">{{ en ? 'CREDITS FOR YOUR CREATIVITY' : '为每一个灵感，留一首歌' }}</p><h1>{{ en ? 'Recharge credits' : '充值创作积分' }}</h1><p>{{ en ? 'Choose a credit pack and turn your stories into songs.' : '选择适合你的积分套餐，让每个故事都有自己的旋律。' }}</p></div>
      <div class="balance"><span>{{ en ? 'Available credits' : '当前可用积分' }}</span><strong>{{ balance === null ? '—' : balance }}</strong><small v-if="musicCost !== null">{{ en ? `${musicCost} credits per song generation` : `每次生成歌曲消耗 ${musicCost} 积分` }}</small></div>
    </div>
    <div class="recharge-body">
      <div class="main-column">
        <section class="section methods">
          <div class="section-head"><div><span class="step">01</span><h2>{{ en?'Payment method':'支付方式' }}</h2><p>{{ en?'Choose a method to view its available currencies and prices.':'先选择支付方式，查看对应币种与报价。' }}</p></div></div>
          <button type="button" class="method" data-method="paypal" :class="{selected:paymentChoice==='paypal',muted:!paypalAvailable}" :aria-pressed="paymentChoice==='paypal'" :disabled="busy" @click="selectPayment('paypal')">
            <span class="method-logo paypal">P</span><div><strong>PayPal</strong><small>{{ en?'Pay securely with your PayPal account':'使用 PayPal 账户安全支付' }}</small></div><span class="method-status">{{ paypalAvailable?(en?'Available':'可用'):(en?'Not available yet':'暂未开通') }}</span>
          </button>
          <button v-for="method in methods" :key="method.id" type="button" class="method" :data-method="method.id" :class="{selected:paymentChoice===method.id,muted:!method.enabled}" :aria-pressed="paymentChoice===method.id" :disabled="busy" @click="selectPayment(method.id)">
            <span class="method-logo" :class="method.id==='wechat_pay'?'wechat':method.id==='apple_pay'?'apple':'card'">{{ method.id==='wechat_pay'?'●':method.id==='apple_pay'?'Pay':'▤' }}</span>
            <div><strong>{{ methodName(method.id) }}</strong><small>{{ methodDescription(method.id) }}</small></div><span class="method-status">{{ method.enabled?(en?'Available':'可用'):(en?'Not available yet':'暂未开通') }}</span>
          </button>
        </section>
        <section class="section">
          <div class="section-head">
            <div><span class="step">02</span><h2>{{ en ? 'Choose a credit pack' : '选择积分套餐' }}</h2><p>{{ en ? 'The order amount comes from the server. No currency conversion is applied here.' : '套餐按服务端固定报价下单，此处不自动换汇。' }}</p></div>
            <div class="currency-switch" :aria-label="en?'Payment currency':'支付币种'">
              <button v-for="option in currencyOptions" :key="option.currency" type="button" :data-currency="option.currency" :aria-pressed="currency===option.currency" :disabled="busy||!option.enabled" @click="selectCurrency(option.currency)">{{ option.currency }} {{ paymentCurrencySymbol(option.currency) }}</button>
            </div>
          </div>
          <p v-if="paymentChoice==='wechat_pay' && currencyOptions.some(option=>option.currency==='USD'&&!option.enabled)" class="currency-note" data-testid="wechat-usd-unavailable">{{ en?'WeChat Pay in USD is not supported for this merchant account. Choose the CNY quote instead.':'当前商户账户不支持微信美元付款，可选择人民币报价；美元套餐仍可使用银行卡或 Apple Pay。' }}</p>
          <div v-if="!available" class="notice">{{ loading ? (en?'Checking payment availability…':'正在检查支付状态…') : availabilityMessage(currentMethod?.reason) }}</div>
          <div class="plans">
            <button v-for="(tier,i) in tiers" :key="tier.credits" type="button" class="plan" :class="{selected:selected===tier.credits}" :aria-pressed="selected===tier.credits" :disabled="busy||!tier.real" @click="selected=tier.credits">
              <span v-if="i===1" class="badge">{{ en?'POPULAR':'热门选择' }}</span><span class="plan-name">{{ en?['STARTER','CREATOR','STUDIO'][i]:['灵感起步','创作畅享','灵感满满'][i] }}</span><strong>{{ tier.credits }} <small>{{ en?'credits':'积分' }}</small></strong>
              <span class="plan-price">{{ tier.amount===null?'—':symbol+tier.amount.toFixed(2) }}</span><span v-if="tier.amount===null" class="quote-unavailable">{{ en?'No server quote':'暂无服务端报价' }}</span><span v-if="songCount(tier.credits) !== null" class="plan-info">{{ en?`About ${songCount(tier.credits)} song generations`:`约可生成 ${songCount(tier.credits)} 次歌曲` }}</span><span class="radio" aria-hidden="true"></span>
            </button>
          </div>
        </section>
      </div>
      <aside class="summary">
        <p class="explain"><RouterLink to="/legal/terms" target="_blank" rel="noopener">积分充值、使用与退款规则</RouterLink></p><p class="summary-label">{{ en?'ORDER SUMMARY':'订单概览' }}</p><h2>{{ en?'Your selection':'你的选择' }}</h2>
        <div class="summary-line"><span>{{ en?'Credit pack':'积分套餐' }}</span><strong>{{ selected }} {{ en?'credits':'积分' }}</strong></div><div class="summary-line"><span>{{ en?'Payment method':'支付方式' }}</span><strong>{{ paymentChoice==='paypal'?'PayPal':methodName(paymentChoice) }}</strong></div><div class="summary-line"><span>{{ en?'Currency':'支付币种' }}</span><strong>{{ currency }}</strong></div>
        <div class="summary-total"><span>{{ en?'Total':'合计' }}</span><strong>{{ chosen?symbol+price:'—' }}</strong></div><p class="explain">{{ en?'Credits are added after payment is confirmed.':'付款确认后，积分将添加到你的账号。' }}</p>
        <p v-if="paymentChoice==='apple_pay'" class="explain" data-testid="apple-pay-compatibility">{{ en?'You will continue to Stripe Checkout. Apple Pay appears only on supported devices with an eligible wallet; you can also pay by card.':'下一步进入 Stripe 收银台。Apple Pay 仅在兼容设备且钱包可用时显示，也可使用银行卡付款。' }}</p>
        <button v-if="props.embedded && available && !signedIn" type="button" class="pay-button" @click="emit('login')">{{ en?'Sign in to continue':'登录后继续' }}</button>
        <RouterLink v-else-if="available && !signedIn" class="pay-button" to="/account">{{ en?'Sign in to continue':'登录后继续' }}</RouterLink>
        <button v-else type="button" class="pay-button" :disabled="!available || !chosen || loading || busy || blocked" @click="checkout">{{ busy?(en?'Opening checkout…':'正在前往支付…'):!available?(en?'Payment coming soon':'支付暂未开通'):paymentChoice==='paypal'?(en?'Continue with PayPal':'使用 PayPal 支付'):paymentChoice==='apple_pay'?(en?'Continue to Apple Pay':'前往 Stripe 选择 Apple Pay'):paymentChoice==='wechat_pay'?(en?'Continue with WeChat Pay':'使用微信支付'):(en?'Continue with Stripe':'使用银行卡支付') }}</button>
        <p v-if="blocked" class="warning">{{ en?'An order needs checking. Visit your account before purchasing again.':'有订单需要核对，请先前往账号页查看。' }} <button v-if="props.embedded" type="button" class="order-entry" @click="emit('orders')">{{ en?'View pending orders':'查看待处理订单' }}</button><RouterLink v-else class="order-entry" to="/account#payment-orders">{{ en?'View pending orders':'查看待处理订单' }}</RouterLink></p>
        <p v-if="error" class="warning" role="alert">{{ error }}</p><div class="secure">♢ {{ en?'Secure checkout with '+(provider==='paypal'?'PayPal':'Stripe'):'由'+(provider==='paypal'?'PayPal':'Stripe')+' 安全处理付款' }}</div>
      </aside>
    </div>
    <div class="footnote">{{ en?'Questions about credits? Visit your account to check your balance and orders.':'想查看积分余额或订单？可随时前往账号页查看。' }} <button v-if="props.embedded" type="button" @click="emit('orders')">{{ en?'View my orders':'查看我的订单' }}</button><RouterLink v-else to="/account">{{ en?'My account →':'我的账号 →' }}</RouterLink></div>
  </div>
</template>
<style scoped>
.currency-switch{display:flex;gap:4px;padding:4px;border-radius:20px;background:#f8f3f0}.currency-switch button{border:0;background:transparent;border-radius:16px;padding:7px 11px;cursor:pointer;color:#897c73}.currency-switch button[aria-pressed=true]{background:#fff;color:#d8755c}.currency-switch button:disabled{opacity:.45;cursor:not-allowed}.currency-note{font-size:12px;line-height:1.7;color:#897c73}.apple{background:#f0eeed;color:#292420}.plan:disabled{cursor:not-allowed}.method{border:0}.plan-price{font-variant-numeric:tabular-nums}
.order-entry{display:inline-flex;align-items:center;min-height:44px;padding:10px 16px;margin-top:10px;background:#f2634d;border-radius:10px;color:white!important;font-weight:700;text-decoration:none}

.recharge{max-width:1180px;margin:0 auto;padding:56px 24px 100px;color:#292420}.recharge-intro{display:flex;justify-content:space-between;gap:30px;align-items:end;margin-bottom:38px}.eyebrow,.summary-label{font-size:11px;letter-spacing:.16em;font-weight:700;color:#d8755c}.recharge h1{font-size:clamp(30px,4vw,44px);letter-spacing:-.04em;margin:10px 0}.recharge-intro p:not(.eyebrow),.section-head p{color:#887d76;font-size:14px}.balance{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;background:#fff;border:1px solid #efe5df;border-radius:18px;padding:17px 22px;min-width:235px}.balance span,.balance small{color:#897c73;font-size:12px}.balance strong{color:#f05a45;font-size:28px}.balance small{flex-basis:100%}.recharge-body{display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:24px;align-items:start}.main-column{display:grid;gap:20px}.section,.summary{background:#fff;border:1px solid #f0e6df;border-radius:20px;padding:27px;box-shadow:0 14px 40px #8b59470a}.section-head{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:24px}.section-head h2{display:inline;font-size:19px;margin:0}.section-head p{margin:9px 0 0}.step{font-size:12px;color:#ed8067;font-weight:700;margin-right:10px}.switch{display:flex;padding:4px;background:#f8f3f0;border-radius:99px}.switch button{border:0;background:none;padding:8px 13px;border-radius:99px;color:#8a7d75;font-size:12px;font-weight:700;cursor:pointer}.switch button[aria-pressed=true]{background:#fff;color:#f04e39;box-shadow:0 2px 10px #47352a14}.notice{background:#fff8f2;border-radius:10px;color:#a26f56;font-size:12px;padding:11px 14px;margin-bottom:16px}.plans{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.plan{text-align:left;cursor:pointer;position:relative;min-height:185px;padding:20px;border:1px solid #efe5df;border-radius:15px;background:#fff;display:flex;flex-direction:column;color:inherit}.plan.selected{border:2px solid #f2634d;background:#fff8f5;padding:19px}.plan-name{font-size:12px;color:#a18e82;font-weight:700;margin-bottom:15px}.plan strong{font-size:25px}.plan strong small{font-size:12px;font-weight:500}.plan-price{font-size:17px;font-weight:700;margin-top:12px}.plan-info{color:#a29891;font-size:11px;margin-top:7px}.badge{position:absolute;right:10px;top:-10px;color:#fff;background:#f16b51;padding:4px 8px;border-radius:8px;font-size:10px}.radio{position:absolute;right:15px;bottom:16px;width:15px;height:15px;border:1px solid #d7c9bf;border-radius:50%}.selected .radio{border:5px solid #ef654e}.method{display:flex;align-items:center;gap:13px;padding:15px 0;border-top:1px solid #f4eeea;width:100%;text-align:left;color:inherit;background:transparent;cursor:pointer}.method:disabled{cursor:not-allowed}.method.selected{background:#fff8f5}.method-logo{width:37px;height:37px;border-radius:11px;display:grid;place-items:center;font-size:20px;font-weight:800}.paypal{color:#1556a2;background:#e9f2fb}.card{color:#e2674e;background:#fff0e9}.wechat{color:#2b9f5a;background:#e9f8ef}.method div{display:grid;gap:5px;flex:1}.method strong{font-size:13px}.method small{color:#a0958e;font-size:11px}.method-status{font-size:11px;color:#a19084}.muted{opacity:.72}.summary{position:sticky;top:100px}.summary h2{font-size:20px;margin:8px 0 24px}.summary-line{display:flex;justify-content:space-between;padding:10px 0;color:#8c8078;font-size:13px}.summary-line strong{color:#37302b}.summary-total{display:flex;justify-content:space-between;align-items:baseline;border-top:1px solid #f0e7e1;margin-top:17px;padding-top:20px;font-size:13px}.summary-total strong{font-size:27px;color:#f05b45}.explain,.secure,.footnote{font-size:12px;color:#a09288;line-height:1.8}.pay-button{display:block;width:100%;text-align:center;border:0;border-radius:12px;background:#f5533f;color:#fff;font-weight:700;padding:14px;margin:20px 0 10px;text-decoration:none;cursor:pointer}.pay-button:disabled{background:#e3d9d3;cursor:not-allowed}.warning{font-size:12px;color:#b65d43}.secure{text-align:center}.footnote{margin-top:28px;text-align:center}.footnote a,.warning a{color:#df604c}.recharge button:focus-visible,.recharge a:focus-visible{outline:3px solid #ffb4a3;outline-offset:3px}@media(max-width:800px){.recharge{padding:35px 16px 70px}.recharge-intro{align-items:start;flex-direction:column}.balance{width:100%}.recharge-body{grid-template-columns:1fr}.summary{position:static}.section,.summary{padding:21px}.section-head{align-items:start;flex-direction:column}.plans{grid-template-columns:1fr}.plan{min-height:0;display:grid;grid-template-columns:1fr auto;gap:5px 10px}.plan-name{margin:0}.plan strong{grid-row:2}.plan-price{margin:0;grid-column:2;grid-row:2}.plan-info{grid-column:1/-1}.radio{right:15px;bottom:15px}}
</style>
