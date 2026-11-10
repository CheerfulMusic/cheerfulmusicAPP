import { computed } from 'vue'
import { creditBalance, creditPricing, creditsEnabled, creditsRewriteFreeRemaining, loadCredits } from '../identity/credit-balance'

/**
 * 创作区的积分视图：余额与价格都来自后端共享状态（app/credits），
 * 不在页面里另存一份余额，避免出现第二套数字。读不到价格时整体为 null，界面不显示消耗提示。
 */
export function useCredits() {
  const credits = computed(() => {
    const pricing = creditPricing.value
    if (!pricing || !creditsEnabled.value) return null
    return {
      enabled: true,
      balance: creditBalance.value,
      musicCost: pricing.musicCost,
      lyricsCost: pricing.lyricsCost,
      rewriteCost: pricing.rewriteCost,
      rewriteFreeRemaining: creditsRewriteFreeRemaining.value,
    }
  })
  return { credits, refreshCredits: loadCredits }
}
