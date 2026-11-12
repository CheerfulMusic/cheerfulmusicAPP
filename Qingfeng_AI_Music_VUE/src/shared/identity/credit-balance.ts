import { readonly, ref } from 'vue'
import { currentCreatorId, ensureCreator, requestJson } from '../http/api-client'

export interface CreditBalanceResponse { enabled: boolean; balance: number; musicCost: number; lyricsCost: number; rewriteCost: number; rewriteFreeRemaining: number }
/** 创作区使用的积分视图：余额与价格都来自后端，前端不硬编码价格。balance 为 null 表示还没读到真实余额。 */
export interface CreditsView { enabled: boolean; balance: number | null; musicCost: number; lyricsCost: number; rewriteCost: number; rewriteFreeRemaining: number }

const state = ref<number | null>(null)
const pricing = ref<{ musicCost: number; lyricsCost: number; rewriteCost: number } | null>(null)
const rewriteFreeRemaining = ref(0)
const enabled = ref(true)
/** 顶栏只读余额；未登录或读取失败一律为 null，绝不展示过期数字。 */
export const creditBalance = readonly(state)
/** 当前生效的单次价格；读不到时为 null，不显示猜测数字。 */
export const creditPricing = readonly(pricing)
/** AI 改写今日剩余免费次数；读不到时为 0，界面据此告知是否要扣积分。 */
export const creditsRewriteFreeRemaining = readonly(rewriteFreeRemaining)
/** 积分扣费总开关；关闭时创作不扣积分，界面不展示消耗提示。 */
export const creditsEnabled = readonly(enabled)
let loadTurn = 0
let signedIn = false

function normalize(data: Partial<CreditBalanceResponse> | null | undefined): CreditsView | null {
  if (!data || !Number.isFinite(data.balance)) return null
  return {
    enabled: data.enabled !== false,
    balance: Number(data.balance),
    musicCost: Number.isFinite(data.musicCost) ? Number(data.musicCost) : 0,
    lyricsCost: Number.isFinite(data.lyricsCost) ? Number(data.lyricsCost) : 0,
    rewriteCost: Number.isFinite(data.rewriteCost) ? Number(data.rewriteCost) : 0,
    rewriteFreeRemaining: Number.isFinite(data.rewriteFreeRemaining) ? Number(data.rewriteFreeRemaining) : 0,
  }
}

function apply(view: CreditsView | null) {
  state.value = view ? view.balance : null
  pricing.value = view ? { musicCost: view.musicCost, lyricsCost: view.lyricsCost, rewriteCost: view.rewriteCost } : null
  rewriteFreeRemaining.value = view ? view.rewriteFreeRemaining : 0
  enabled.value = view ? view.enabled : true
}

/**
 * 已登录才读余额；每次调用作废旧请求，避免登录切换后旧响应把余额写回来。
 * 数字来自与创作扣费相同的 app/credits 接口，不另维护第二套余额。
 */
export async function refreshCreditBalance(authenticated: boolean): Promise<number | null> {
  const turn = ++loadTurn
  signedIn = authenticated
  if (!authenticated) { apply(null); return null }
  try {
    await ensureCreator()
    const data = await requestJson<CreditBalanceResponse>('app/credits')
    if (turn !== loadTurn) return state.value
    const view = normalize(data)
    apply(view)
    return state.value
  } catch {
    if (turn === loadTurn) apply(null)
    return null
  }
}

/**
 * 创作区读取余额与当前价格；与顶栏共用同一个 app/credits 接口和同一份状态。
 * 价格由后端下发，改价不需要重新发布前端。
 */
export async function loadCredits(): Promise<CreditsView | null> {
  const turn = ++loadTurn
  let owner = currentCreatorId()
  try {
    await ensureCreator()
    if (owner && owner !== currentCreatorId()) return null
    owner = currentCreatorId()
    const data = await requestJson<CreditBalanceResponse>('app/credits')
    const view = normalize(data)
    if (turn !== loadTurn || owner !== currentCreatorId()) return null
    apply(view)
    return view
  } catch {
    if (turn === loadTurn && owner === currentCreatorId()) apply(null)
    return null
  }
}

/** 充值或退款到账后广播，让已打开的页面与顶栏重新读取后端余额。 */
export const CREDITS_CHANGED_EVENT = 'qingfeng:credits-changed'

/**
 * 广播积分变化。余额只以后端 app/credits 的返回为准，
 * 前端不做任何本地加减，广播只是让各处重新读取，不会凭空改动数字。
 */
export function announceCreditsChanged() {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new Event(CREDITS_CHANGED_EVENT))
}

// 已打开的页面在收到广播后统一重新读取余额：顶栏与创作区共用同一份状态，数字始终一致。
if (typeof window !== 'undefined') {
  window.addEventListener(CREDITS_CHANGED_EVENT, () => {
    if (signedIn) void loadCredits()
  })
}
