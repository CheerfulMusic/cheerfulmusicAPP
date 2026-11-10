import { readonly, ref } from 'vue'
import { ensureCreator, requestJson } from '../http/api-client'

export interface CreditBalanceResponse { enabled: boolean; balance: number; musicCost: number }

const state = ref<number | null>(null)
/** 顶栏只读余额；未登录或读取失败一律为 null，绝不展示过期数字。 */
export const creditBalance = readonly(state)
let loadTurn = 0

/**
 * 已登录才读余额；每次调用作废旧请求，避免登录切换后旧响应把余额写回来。
 * 数字来自与创作扣费相同的 app/credits 接口，不另维护第二套余额。
 */
export async function refreshCreditBalance(authenticated: boolean): Promise<number | null> {
  const turn = ++loadTurn
  if (!authenticated) { state.value = null; return null }
  try {
    await ensureCreator()
    const data = await requestJson<CreditBalanceResponse>('app/credits')
    if (turn !== loadTurn) return state.value
    state.value = Number.isFinite(data.balance) ? data.balance : null
  } catch {
    if (turn === loadTurn) state.value = null
  }
  return state.value
}
