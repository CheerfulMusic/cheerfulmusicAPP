import { describe, expect, it, vi } from 'vitest'
import { checkQuickCreationCredits, guardCredits, quickCreationCost } from '../src/shared/credits/credit-gate'
import type { CreditsView } from '../src/shared/identity/credit-balance'

function view(balance: number | null, lyricsCost = 5): CreditsView {
  return { enabled: true, balance, musicCost: 15, lyricsCost, rewriteCost: 1, rewriteFreeRemaining: 0 }
}

describe('一键创作的积分准入', () => {
  const priced = (balance: number | null): CreditsView => ({ ...view(balance, 1), musicCost: 5 })
  it('合计歌词和音乐的实时价格，余额不足时拦截', async () => {
    expect(quickCreationCost(priced(5))).toBe(6)
    expect(await checkQuickCreationCredits(async () => priced(5))).toEqual({ status: 'insufficient', cost: 6, balance: 5 })
    expect(await checkQuickCreationCredits(async () => priced(6))).toEqual({ status: 'ok' })
  })
  it('价格或余额未知时拒绝启动，不猜测价格', async () => {
    expect(await checkQuickCreationCredits(async () => null)).toEqual({ status: 'unavailable' })
    expect(await checkQuickCreationCredits(async () => ({ ...priced(20), musicCost: 0 }))).toEqual({ status: 'unavailable' })
    expect(await checkQuickCreationCredits(async () => priced(null))).toEqual({ status: 'unavailable' })
  })
  it('积分功能关闭时无需余额', async () => {
    expect(await checkQuickCreationCredits(async () => ({ ...priced(null), enabled: false }))).toEqual({ status: 'ok' })
  })
})

describe('歌词生成的积分准入', () => {
  it('余额充足时放行，且不额外请求余额', async () => {
    const refresh = vi.fn()
    const gate = await guardCredits(() => view(20), refresh)
    expect(gate.blocked).toBe(false)
    expect(gate.cost).toBe(5)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('余额刚好等于价格时放行', async () => {
    const gate = await guardCredits(() => view(5), vi.fn())
    expect(gate.blocked).toBe(false)
  })

  it('余额不足时拦截，并给出真实余额与所需积分', async () => {
    const gate = await guardCredits(() => view(2), vi.fn())
    expect(gate.blocked).toBe(true)
    expect(gate.balance).toBe(2)
    expect(gate.cost).toBe(5)
  })

  it('拦截前先刷新一次余额，刚充值完的用户不会被旧数字挡住', async () => {
    let current = view(2)
    const refresh = vi.fn(async () => { current = view(30) })
    const gate = await guardCredits(() => current, refresh)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(gate.blocked).toBe(false)
    expect(gate.balance).toBe(30)
  })

  it('刷新后仍然不足才拦截', async () => {
    const refresh = vi.fn(async () => undefined)
    const gate = await guardCredits(() => view(2), refresh)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(gate.blocked).toBe(true)
  })

  it('余额未知时不在前端猜测，交由后端判定', async () => {
    const refresh = vi.fn()
    const gate = await guardCredits(() => view(null), refresh)
    expect(gate.blocked).toBe(false)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('价格未知时不拦截，也不显示猜测价格', async () => {
    const gate = await guardCredits(() => view(0, 0), vi.fn())
    expect(gate.blocked).toBe(false)
    expect(gate.cost).toBe(0)
  })

  it('未读到积分数据时同样不拦截', async () => {
    const gate = await guardCredits(() => null, vi.fn())
    expect(gate.blocked).toBe(false)
  })
})

describe('AI 改写的积分准入', () => {
  const rewriteCost = (view: CreditsView) => view.rewriteFreeRemaining === 0 ? view.rewriteCost : 0

  it('每日免费额度内不拦截，也不要求余额', async () => {
    const refresh = vi.fn()
    const gate = await guardCredits(() => ({ ...view(0), rewriteCost: 1, rewriteFreeRemaining: 3 }), refresh, rewriteCost)
    expect(gate.blocked).toBe(false)
    expect(gate.cost).toBe(0)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('永久免费（剩余次数为 -1）同样不拦截', async () => {
    const gate = await guardCredits(() => ({ ...view(0), rewriteCost: 1, rewriteFreeRemaining: -1 }), vi.fn(), rewriteCost)
    expect(gate.blocked).toBe(false)
    expect(gate.cost).toBe(0)
  })

  it('免费额度用尽后余额不足才拦截', async () => {
    const gate = await guardCredits(() => ({ ...view(0), rewriteCost: 1, rewriteFreeRemaining: 0 }), vi.fn(), rewriteCost)
    expect(gate.blocked).toBe(true)
    expect(gate.cost).toBe(1)
    expect(gate.balance).toBe(0)
  })

  it('免费额度用尽后余额充足则放行', async () => {
    const gate = await guardCredits(() => ({ ...view(5), rewriteCost: 1, rewriteFreeRemaining: 0 }), vi.fn(), rewriteCost)
    expect(gate.blocked).toBe(false)
  })

  it('拦截前先刷新余额，刚充值完不会被旧数字挡住', async () => {
    let current: CreditsView = { ...view(0), rewriteCost: 1, rewriteFreeRemaining: 0 }
    const refresh = vi.fn(async () => { current = { ...view(9), rewriteCost: 1, rewriteFreeRemaining: 0 } })
    const gate = await guardCredits(() => current, refresh, rewriteCost)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(gate.blocked).toBe(false)
  })
})
