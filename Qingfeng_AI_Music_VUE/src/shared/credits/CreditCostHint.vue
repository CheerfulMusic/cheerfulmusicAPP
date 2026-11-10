<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '../i18n'

/**
 * 创作按钮旁的积分消耗提示。
 * 价格与余额都由后端下发，前端不硬编码；读不到价格时整块不显示，避免给出错误数字。
 * variant 区分首次生成、重新生成与 AI 改写，只影响主文案与规则说明。
 */
const props = defineProps<{
  cost: number
  balance?: number | null
  busy?: boolean
  variant?: 'generate' | 'regenerate' | 'rewrite'
  freeRemaining?: number | null
}>()

const open = ref(false)
const hover = ref(false)
const root = ref<HTMLElement | null>(null)
const rule = ref<HTMLElement | null>(null)
const shift = ref(0)

const insufficient = computed(() => props.balance !== null && props.balance !== undefined && props.balance < props.cost)
/** 改写在每日免费额度内不扣积分，此时不显示余额不足。rewriteFreeRemaining 为 -1 表示永久免费。 */
const freeRewrite = computed(() => props.variant === 'rewrite' && props.freeRemaining !== null && props.freeRemaining !== undefined && props.freeRemaining !== 0)
const label = computed(() => {
  if (props.variant === 'rewrite') {
    if (freeRewrite.value) {
      const remaining = props.freeRemaining as number
      return remaining > 0
        ? t('本次改写不消耗积分，今日免费改写还剩 {remaining} 次', { remaining })
        : t('本次改写不消耗积分')
    }
    if (insufficient.value) return t('积分不足，当前余额 {balance} 积分，改写需要 {cost} 积分。', { balance: props.balance as number, cost: props.cost })
    return t('本次改写消耗 {cost} 积分', { cost: props.cost })
  }
  if (insufficient.value) return t('积分不足，当前余额 {balance} 积分，生成需要 {cost} 积分。', { balance: props.balance as number, cost: props.cost })
  return props.variant === 'regenerate'
    ? t('重新生成歌词同样消耗 {cost} 积分', { cost: props.cost })
    : t('本次生成消耗 {cost} 积分', { cost: props.cost })
})
const visible = computed(() => open.value || hover.value)

function toggle() { open.value = !open.value }
function onDocumentClick(event: Event) {
  if (!open.value) return
  if (root.value && !root.value.contains(event.target as Node)) open.value = false
}
/** 浮层靠右时向左收，避免溢出视口；用 CSS 变量做位移，不覆盖窄屏的贴底定位。 */
async function reposition() {
  await nextTick()
  const panel = rule.value
  const host = root.value
  if (!panel || !host) return
  if (window.innerWidth <= 600) { shift.value = 0; return }
  const overflow = (host.getBoundingClientRect().left + panel.offsetWidth) - (window.innerWidth - 16)
  shift.value = overflow > 0 ? -overflow : 0
}
watch(visible, value => { if (value) void reposition() })
onMounted(() => document.addEventListener('click', onDocumentClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick))
</script>

<template>
  <span ref="root" class="credit-cost" :class="{ short: insufficient, busy }" @mouseenter="hover = true" @mouseleave="hover = false">
    <button type="button" class="credit-cost-trigger" :aria-expanded="visible" :aria-busy="busy || undefined" :aria-label="label" @click="toggle">
      <svg class="credit-cost-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.6"/><circle cx="12" cy="12" r="4.1"/></svg>
      <span class="credit-cost-text">{{ label }}</span>
    </button>
    <span v-if="visible" ref="rule" class="credit-rule" role="tooltip" :style="{ '--credit-rule-shift': shift + 'px' }">
      <template v-if="variant === 'rewrite'">
        <span class="credit-rule-title">{{ t('积分规则') }}</span>
        <span class="credit-rule-line">{{ (freeRemaining ?? 0) > 0 ? t('今日免费改写还剩 {remaining} 次，用完后每次消耗 {cost} 积分。', { remaining: freeRemaining ?? 0, cost }) : t('AI 改写当前不消耗积分。') }}</span>
        <span class="credit-rule-line">{{ t('改写失败会退还已扣积分；免费次数按调用计算，失败同样占用一次。') }}</span>
        <RouterLink v-if="insufficient && !freeRewrite" class="credit-rule-link" to="/recharge">{{ t('去充值') }}</RouterLink>
      </template>
      <template v-else>
        <span class="credit-rule-title">{{ t('积分规则') }}</span>
        <span class="credit-rule-line">{{ t('歌词生成成功后保留积分扣费。') }}</span>
        <span class="credit-rule-line">{{ t('生成失败、超时或取消将自动退还积分。') }}</span>
        <span v-if="balance !== null && balance !== undefined" class="credit-rule-line">{{ t('当前余额 {balance} 积分，单次生成消耗 {cost} 积分。', { balance, cost }) }}</span>
        <RouterLink v-if="insufficient" class="credit-rule-link" to="/recharge">{{ t('去充值') }}</RouterLink>
      </template>
    </span>
  </span>
</template>
