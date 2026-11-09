import { ApiError } from '../../../shared/http/api-client'

export type Credits = { enabled: boolean; balance: number; musicCost: number }

/** 积分不足是明确结果：不重试、不保留待核对意图，直接在生成按钮旁提示充值。 */
export function isCreditShortfall(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'INSUFFICIENT_CREDITS'
}

/** 只有拿到真实余额时才给出具体数字；读不到余额就不再编造差额。 */
export function creditGap(credits: Credits | undefined) {
  if (!credits || !credits.enabled || credits.musicCost <= 0) return undefined
  return { cost: credits.musicCost, balance: credits.balance, missing: Math.max(0, credits.musicCost - credits.balance) }
}
