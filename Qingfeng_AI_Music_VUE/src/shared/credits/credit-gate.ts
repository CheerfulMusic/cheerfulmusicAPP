import type { CreditsView } from '../identity/credit-balance'

export interface CreditGate { blocked: boolean; balance: number | null; cost: number }

/**
 * 统一的积分准入判断：所有创作入口都调用这里，保证价格与余额来源一致，
 * 不会出现某个入口用旧价格、另一个入口用新价格的情况。
 *
 * 余额不足时先重新读取一次再判断，避免用过期数字拦下刚充值完的用户；
 * 刷新后仍然不足才拦截。这只是提前拦截，后端在调用模型前还会再校验一次余额。
 *
 * costOf 决定本次入口实际要付多少：AI 改写在每日免费额度内价格为 0，
 * 用完免费额度后才按 rewriteCost 收费。rewriteFreeRemaining 为 -1 表示永久免费。
 */
export async function guardCredits(
  read: () => CreditsView | null,
  refresh: () => Promise<unknown>,
  costOf: (view: CreditsView) => number = view => view.lyricsCost,
): Promise<CreditGate> {
  const before = read()
  const cost = before ? costOf(before) : 0
  const balance = before?.balance ?? null
  // 价格或余额未知时不拦截：交由后端判定，前端不猜数字。
  if (cost <= 0 || balance === null || balance >= cost) return { blocked: false, balance, cost }
  await refresh()
  const after = read()
  const latestCost = after ? costOf(after) : cost
  const latestBalance = after?.balance ?? null
  if (latestBalance === null || latestBalance >= latestCost) return { blocked: false, balance: latestBalance, cost: latestCost }
  return { blocked: true, balance: latestBalance, cost: latestCost }
}
