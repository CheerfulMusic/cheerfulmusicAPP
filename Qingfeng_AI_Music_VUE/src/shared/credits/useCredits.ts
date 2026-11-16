import { computed, ref } from 'vue'
import { currentCreatorId } from '../http/api-client'
import { creditBalance, creditPricing, creditsEnabled, creditsNewWorkLyricsNextCost, creditsRewriteFreeRemaining, loadCredits } from '../identity/credit-balance'

/**
 * 创作区的积分视图：余额与价格都来自后端共享状态（app/credits），
 * 不在页面里另存一份余额，避免出现第二套数字。读不到价格时整体为 null，界面不显示消耗提示。
 */
export function useCredits(workId?: () => string | undefined) {
  const scopedQuote = ref<{ owner: string; workId: string; cost: number | null }>()
  let quoteTurn = 0
  const credits = computed(() => {
    const pricing = creditPricing.value
    if (!pricing || !creditsEnabled.value) return null
    const currentWork = workId?.()
    return {
      enabled: true,
      balance: creditBalance.value,
      musicCost: pricing.musicCost,
      lyricsCost: pricing.lyricsCost,
      lyricsNextCost: currentWork
        ? scopedQuote.value?.workId === currentWork && scopedQuote.value.owner === currentCreatorId() ? scopedQuote.value.cost : null
        : creditsNewWorkLyricsNextCost.value,
      rewriteCost: pricing.rewriteCost,
      rewriteFreeRemaining: creditsRewriteFreeRemaining.value,
    }
  })
  async function refreshCredits() {
    const turn = ++quoteTurn
    const currentWork = workId?.()
    const view = await loadCredits(currentWork)
    if (turn !== quoteTurn || workId?.() !== currentWork) return null
    if (currentWork) scopedQuote.value = view ? { owner: currentCreatorId(), workId: currentWork, cost: view.lyricsNextCost ?? null } : undefined
    return view
  }
  return { credits, refreshCredits }
}
