<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { locale } from '../../../shared/i18n'
import { currentCreatorId, ensureCreator, requestJson } from '../../../shared/http/api-client'
import { accountState, refreshAccountState } from '../../../shared/identity/account-state'
import { readPendingOrders, savePendingOrders } from '../model/payment-orders'
import { preparePaypalCheckout, type PaypalCheckout, type PaypalSdkConfig } from '../api/paypal-sdk'

type Pack = { id:string; title:string; amount:number; currency:string; credits:number }
type Catalog = { enabled:boolean; packages:Pack[] }
const en = computed(() => locale.value === 'en')
const signedIn = computed(() => accountState.value.authenticated)
const currency = ref<'GBP'|'USD'>('GBP'), selected = ref(300), busy = ref(false), error = ref('')
const loading = ref(true), available = ref(false), catalog = ref<Pack[]>([]), blocked = ref(false), balance = ref<number|null>(null)
const sdk = shallowRef<PaypalCheckout|null>(null)
const chosen = computed(() => catalog.value.find(p => p.currency===currency.value && p.credits===selected.value))
const tiers = computed(() => [100,300,700].map((credits, index) => {
  const real = catalog.value.find(p => p.currency===currency.value && p.credits===credits)
  return {credits, amount:real?.amount ?? [4.99,12.99,24.99][index], real:!!real}
}))
const symbol = computed(() => currency.value==='GBP' ? '£' : '$')
const price = computed(() => tiers.value.find(t => t.credits===selected.value)?.amount.toFixed(2))
async function refresh() {
  loading.value=true; error.value=''; blocked.value=false
  try {
    await ensureCreator(); await refreshAccountState().catch(() => undefined)
    const [c,b] = await Promise.allSettled([
      requestJson<Catalog>('app/paypal/catalog'),requestJson<{enabled:boolean;balance:number}>('app/credits')
    ])
    if(c.status==='fulfilled'){available.value=c.value.enabled;catalog.value=c.value.packages}
    if(b.status==='fulfilled' && b.value.enabled) balance.value=b.value.balance
    if(signedIn.value && available.value) {
      const [p,a]=await Promise.all([requestJson<{orders:{status:string}[]}>('app/paypal/orders'), requestJson<{orders:{status:string}[]}>('app/payments/orders')])
      blocked.value=[...p.orders,...a.orders].some(o=>o.status!=='PAID')
    }
    if (available.value) {
      try {
        const config=await requestJson<PaypalSdkConfig>('app/paypal/sdk-config')
        sdk.value=await preparePaypalCheckout(config, {
          onApproved: async id => {
            try { await requestJson(`app/paypal/orders/${encodeURIComponent(id)}/capture`,'POST',undefined,30000); await refresh() }
            catch { blocked.value=true; error.value=en.value?'Payment is being checked. Visit your account before trying again.':'付款正在核对，请前往账号页查看，避免重复支付。' }
          },
          onCancel: () => { blocked.value=true; error.value=en.value?'The order needs checking. Visit your account before trying again.':'订单仍待核对，请先前往账号页查看。' },
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
    const createOrder=async () => {
      const order=await requestJson<{orderId:string;checkoutUrl:string;paypalOrderId:string}>('app/paypal/orders','POST',{packageId:pack.id},30000)
      if(!owner || owner!==currentCreatorId()) throw new Error('identity changed')
      savePendingOrders(localStorage,owner,[...readPendingOrders(localStorage,owner,'paypal'),order.orderId],'paypal')
      return order
    }
    // Do not allow another order while the PayPal session is awaiting approval.
    if (sdk.value) { blocked.value=true; await sdk.value.start(createOrder); return }
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
    <div class="recharge-intro"><div><p class="eyebrow">{{ en ? 'CREDITS FOR YOUR CREATIVITY' : '为每一个灵感，留一首歌' }}</p><h1>{{ en ? 'Recharge credits' : '充值创作积分' }}</h1><p>{{ en ? 'Choose a credit pack and turn your stories into songs.' : '选择适合你的积分套餐，让每个故事都有自己的旋律。' }}</p></div><div class="balance"><span>{{ en ? 'Available credits' : '当前可用积分' }}</span><strong>{{ balance === null ? '—' : balance }}</strong><small>{{ en ? '10 credits per song generation' : '每次生成歌曲消耗 10 积分' }}</small></div></div>
    <div class="recharge-body"><div class="main-column"><section class="section"><div class="section-head"><div><span class="step">01</span><h2>{{ en ? 'Choose a credit pack' : '选择积分套餐' }}</h2><p>{{ en ? 'Select the pack that fits your creative plans.' : '挑选适合你创作计划的套餐。' }}</p></div><div class="switch" role="group" :aria-label="en?'Choose currency':'选择付款币种'"><button type="button" :aria-pressed="currency==='GBP'" @click="currency='GBP'">GBP £</button><button type="button" :aria-pressed="currency==='USD'" @click="currency='USD'">USD $</button></div></div>
      <div v-if="!available" class="notice">{{ loading ? (en?'Checking payment availability…':'正在检查支付状态…') : (en?'Payments are not open yet. Prices are shown for reference.':'支付暂未开通，以下价格仅供查看。') }}</div>
      <div class="plans"><button v-for="(tier,i) in tiers" :key="tier.credits" type="button" class="plan" :class="{selected:selected===tier.credits}" :aria-pressed="selected===tier.credits" @click="selected=tier.credits"><span v-if="i===1" class="badge">{{ en?'POPULAR':'热门选择' }}</span><span class="plan-name">{{ en?['STARTER','CREATOR','STUDIO'][i]:['灵感起步','创作畅享','灵感满满'][i] }}</span><strong>{{ tier.credits }} <small>{{ en?'credits':'积分' }}</small></strong><span class="plan-price">{{ symbol }}{{ tier.amount.toFixed(2) }}</span><span class="plan-info">{{ en?`About ${tier.credits/10} song generations`:`约可生成 ${tier.credits/10} 次歌曲` }}</span><span class="radio" aria-hidden="true"></span></button></div>
    </section><section class="section methods"><div class="section-head"><div><span class="step">02</span><h2>{{ en?'Payment method':'支付方式' }}</h2><p>{{ en?'Your payment is handled by the provider.':'选择支付方式，在支付平台完成付款。' }}</p></div></div><div class="method"><span class="method-logo paypal">P</span><div><strong>PayPal</strong><small>{{ en?'Pay securely with your PayPal account':'使用 PayPal 账户安全支付' }}</small></div><span class="method-status">{{ available?(en?'Available':'可用'):(en?'Not available yet':'暂未开通') }}</span></div><div class="method muted"><span class="method-logo card">▤</span><div><strong>{{ en?'Credit / debit card':'信用卡 / 借记卡' }}</strong><small>{{ en?'Card availability depends on merchant eligibility':'银行卡支付需商户资质审核' }}</small></div><span class="method-status">{{ en?'Not available yet':'暂未开通' }}</span></div><div class="method muted"><span class="method-logo wechat">●</span><div><strong>{{ en?'WeChat Pay':'微信支付' }}</strong><small>{{ en?'International WeChat Pay requires activation':'国际微信支付需单独开通' }}</small></div><span class="method-status">{{ en?'Not available yet':'暂未开通' }}</span></div></section></div>
      <aside class="summary"><p class="summary-label">{{ en?'ORDER SUMMARY':'订单概览' }}</p><h2>{{ en?'Your selection':'你的选择' }}</h2><div class="summary-line"><span>{{ en?'Credit pack':'积分套餐' }}</span><strong>{{ selected }} {{ en?'credits':'积分' }}</strong></div><div class="summary-line"><span>{{ en?'Currency':'支付币种' }}</span><strong>{{ currency }}</strong></div><div class="summary-total"><span>{{ en?'Total':'合计' }}</span><strong>{{ symbol }}{{ price }}</strong></div><p class="explain">{{ en?'Credits are added after payment is confirmed.':'付款确认后，积分将添加到你的账号。' }}</p><RouterLink v-if="available && !signedIn" class="pay-button" to="/account">{{ en?'Sign in to continue':'登录后继续' }}</RouterLink><button v-else type="button" class="pay-button" :disabled="!available || !chosen || loading || busy || blocked" @click="checkout">{{ busy?(en?'Opening PayPal…':'正在前往 PayPal…'):available?(en?'Continue with PayPal':'使用 PayPal 支付'):(en?'Payment coming soon':'支付暂未开通') }}</button><p v-if="blocked" class="warning">{{ en?'An order needs checking. Visit your account before purchasing again.':'有订单需要核对，请先前往账号页查看。' }} <RouterLink to="/account">{{ en?'View account':'查看账号' }}</RouterLink></p><p v-if="error" class="warning" role="alert">{{ error }}</p><div class="secure">♢ {{ en?'Secure checkout with PayPal':'由支付平台安全处理付款' }}</div></aside>
    </div><div class="footnote">{{ en?'Questions about credits? Visit your account to check your balance and orders.':'想查看积分余额或订单？可随时前往账号页面查看。' }} <RouterLink to="/account">{{ en?'My account →':'我的账号 →' }}</RouterLink></div>
  </div>
</template>
<style scoped>
.recharge{max-width:1180px;margin:0 auto;padding:56px 24px 100px;color:#292420}.recharge-intro{display:flex;justify-content:space-between;gap:30px;align-items:end;margin-bottom:38px}.eyebrow,.summary-label{font-size:11px;letter-spacing:.16em;font-weight:700;color:#d8755c}.recharge h1{font-size:clamp(30px,4vw,44px);letter-spacing:-.04em;margin:10px 0}.recharge-intro p:not(.eyebrow),.section-head p{color:#887d76;font-size:14px}.balance{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;background:#fff;border:1px solid #efe5df;border-radius:18px;padding:17px 22px;min-width:235px}.balance span,.balance small{color:#897c73;font-size:12px}.balance strong{color:#f05a45;font-size:28px}.balance small{flex-basis:100%}.recharge-body{display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:24px;align-items:start}.main-column{display:grid;gap:20px}.section,.summary{background:#fff;border:1px solid #f0e6df;border-radius:20px;padding:27px;box-shadow:0 14px 40px #8b59470a}.section-head{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:24px}.section-head h2{display:inline;font-size:19px;margin:0}.section-head p{margin:9px 0 0}.step{font-size:12px;color:#ed8067;font-weight:700;margin-right:10px}.switch{display:flex;padding:4px;background:#f8f3f0;border-radius:99px}.switch button{border:0;background:none;padding:8px 13px;border-radius:99px;color:#8a7d75;font-size:12px;font-weight:700;cursor:pointer}.switch button[aria-pressed=true]{background:#fff;color:#f04e39;box-shadow:0 2px 10px #47352a14}.notice{background:#fff8f2;border-radius:10px;color:#a26f56;font-size:12px;padding:11px 14px;margin-bottom:16px}.plans{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.plan{text-align:left;cursor:pointer;position:relative;min-height:185px;padding:20px;border:1px solid #efe5df;border-radius:15px;background:#fff;display:flex;flex-direction:column;color:inherit}.plan.selected{border:2px solid #f2634d;background:#fff8f5;padding:19px}.plan-name{font-size:12px;color:#a18e82;font-weight:700;margin-bottom:15px}.plan strong{font-size:25px}.plan strong small{font-size:12px;font-weight:500}.plan-price{font-size:17px;font-weight:700;margin-top:12px}.plan-info{color:#a29891;font-size:11px;margin-top:7px}.badge{position:absolute;right:10px;top:-10px;color:#fff;background:#f16b51;padding:4px 8px;border-radius:8px;font-size:10px}.radio{position:absolute;right:15px;bottom:16px;width:15px;height:15px;border:1px solid #d7c9bf;border-radius:50%}.selected .radio{border:5px solid #ef654e}.method{display:flex;align-items:center;gap:13px;padding:15px 0;border-top:1px solid #f4eeea}.method-logo{width:37px;height:37px;border-radius:11px;display:grid;place-items:center;font-size:20px;font-weight:800}.paypal{color:#1556a2;background:#e9f2fb}.card{color:#e2674e;background:#fff0e9}.wechat{color:#2b9f5a;background:#e9f8ef}.method div{display:grid;gap:5px;flex:1}.method strong{font-size:13px}.method small{color:#a0958e;font-size:11px}.method-status{font-size:11px;color:#a19084}.muted{opacity:.72}.summary{position:sticky;top:100px}.summary h2{font-size:20px;margin:8px 0 24px}.summary-line{display:flex;justify-content:space-between;padding:10px 0;color:#8c8078;font-size:13px}.summary-line strong{color:#37302b}.summary-total{display:flex;justify-content:space-between;align-items:baseline;border-top:1px solid #f0e7e1;margin-top:17px;padding-top:20px;font-size:13px}.summary-total strong{font-size:27px;color:#f05b45}.explain,.secure,.footnote{font-size:12px;color:#a09288;line-height:1.8}.pay-button{display:block;width:100%;text-align:center;border:0;border-radius:12px;background:#f5533f;color:#fff;font-weight:700;padding:14px;margin:20px 0 10px;text-decoration:none;cursor:pointer}.pay-button:disabled{background:#e3d9d3;cursor:not-allowed}.warning{font-size:12px;color:#b65d43}.secure{text-align:center}.footnote{margin-top:28px;text-align:center}.footnote a,.warning a{color:#df604c}.recharge button:focus-visible,.recharge a:focus-visible{outline:3px solid #ffb4a3;outline-offset:3px}@media(max-width:800px){.recharge{padding:35px 16px 70px}.recharge-intro{align-items:start;flex-direction:column}.balance{width:100%}.recharge-body{grid-template-columns:1fr}.summary{position:static}.section,.summary{padding:21px}.section-head{align-items:start;flex-direction:column}.plans{grid-template-columns:1fr}.plan{min-height:0;display:grid;grid-template-columns:1fr auto;gap:5px 10px}.plan-name{margin:0}.plan strong{grid-row:2}.plan-price{margin:0;grid-column:2;grid-row:2}.plan-info{grid-column:1/-1}.radio{right:15px;bottom:15px}}
</style>
