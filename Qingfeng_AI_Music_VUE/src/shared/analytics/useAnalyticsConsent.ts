import { computed, ref } from 'vue'
import { setAnalyticsEnabled } from './track'

export type ConsentState = 'undecided' | 'accepted' | 'rejected'

const STORAGE_KEY = 'qf.analytics.optout'

function readState(): ConsentState {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    if (v === '0') return 'accepted'
    if (v === '1') return 'rejected'
  } catch { /* 无存储环境按未决定处理。 */ }
  return 'undecided'
}

// 单例响应式状态：全站共享同一份同意决定。
const state = ref<ConsentState>(readState())

/**
 * 分析数据采集同意的轻量状态管理。
 * 严格遵循：不默认同意、接受/拒绝/撤回三种显式操作、用现有 OPT_OUT_KEY 持久化避免重复弹窗。
 * 与登录、注册、服务条款授权完全解耦——这是一条独立的分析 Cookie 同意。
 */
export function useAnalyticsConsent() {
  function sync() { state.value = readState() }
  function accept() { setAnalyticsEnabled(true); sync() }
  function reject() { setAnalyticsEnabled(false); sync() }
  function withdraw() { setAnalyticsEnabled(false); sync() }   // 已接受 → 撤回
  function manage() { state.value = 'undecided' }              // 重新弹出选择，不改动已持久化决定

  return {
    state,
    isUndecided: computed(() => state.value === 'undecided'),
    isAccepted: computed(() => state.value === 'accepted'),
    isRejected: computed(() => state.value === 'rejected'),
    accept, reject, withdraw, manage,
  }
}
