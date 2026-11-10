import { beforeEach, describe, expect, it, vi } from 'vitest'

const spies = vi.hoisted(() => ({ ensureCreator: vi.fn(), requestJson: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({ ensureCreator: spies.ensureCreator, requestJson: spies.requestJson }))
import { creditBalance, creditPricing, loadCredits, refreshCreditBalance } from '../src/shared/identity/credit-balance'
import { useCredits } from '../src/shared/credits/useCredits'

beforeEach(() => {
  spies.ensureCreator.mockReset().mockResolvedValue(undefined)
  spies.requestJson.mockReset().mockResolvedValue({ enabled: true, balance: 100, musicCost: 15 })
  return refreshCreditBalance(false)
})

describe('顶栏积分余额', () => {
  it('已登录读取 app/credits 的余额', async () => {
    expect(await refreshCreditBalance(true)).toBe(100)
    expect(creditBalance.value).toBe(100)
    expect(spies.ensureCreator).toHaveBeenCalledTimes(1)
    expect(spies.requestJson).toHaveBeenCalledWith('app/credits')
  })

  it('未登录不发请求并清空余额', async () => {
    await refreshCreditBalance(true)
    expect(creditBalance.value).toBe(100)
    await refreshCreditBalance(false)
    expect(creditBalance.value).toBeNull()
    expect(spies.requestJson).toHaveBeenCalledTimes(1)
  })

  it('读取失败时不保留旧数字', async () => {
    spies.requestJson.mockRejectedValueOnce(new Error('offline'))
    expect(await refreshCreditBalance(true)).toBeNull()
    expect(creditBalance.value).toBeNull()
  })

  it('余额字段异常时不展示脏值', async () => {
    spies.requestJson.mockResolvedValueOnce({ enabled: true, balance: Number.NaN, musicCost: 15 })
    expect(await refreshCreditBalance(true)).toBeNull()
  })

  it('登出后的旧响应不会把余额写回来', async () => {
    let resolve: ((value: unknown) => void) | undefined
    spies.requestJson.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const pending = refreshCreditBalance(true)
    await refreshCreditBalance(false)
    resolve?.({ enabled: true, balance: 999, musicCost: 15 })
    await pending
    expect(creditBalance.value).toBeNull()
  })
})

describe('创作区的积分价格', () => {
  it('歌词价格来自后端接口，前端不硬编码', async () => {
    spies.requestJson.mockResolvedValue({ enabled: true, balance: 30, musicCost: 15, lyricsCost: 7 })
    const view = await loadCredits()
    expect(view?.lyricsCost).toBe(7)
    expect(view?.musicCost).toBe(15)
    expect(view?.balance).toBe(30)
    expect(creditPricing.value).toEqual({ musicCost: 15, lyricsCost: 7, rewriteCost: 0 })
    expect(spies.requestJson).toHaveBeenCalledWith('app/credits')
  })

  it('读不到余额时不给出价格，避免展示猜测数字', async () => {
    spies.requestJson.mockResolvedValueOnce({ enabled: true, balance: Number.NaN, musicCost: 15, lyricsCost: 7 })
    expect(await loadCredits()).toBeNull()
    expect(creditPricing.value).toBeNull()
  })

  it('后端未返回歌词价格时不显示价格', async () => {
    spies.requestJson.mockResolvedValueOnce({ enabled: true, balance: 30, musicCost: 15 })
    const view = await loadCredits()
    expect(view?.lyricsCost).toBe(0)
  })
})

describe('创作区的积分视图', () => {
  it('直接复用共享余额与价格，不另存第二套数字', async () => {
    spies.requestJson.mockResolvedValue({ enabled: true, balance: 42, musicCost: 15, lyricsCost: 5 })
    const { credits, refreshCredits } = useCredits()
    expect(credits.value).toBeNull()
    await refreshCredits()
    expect(credits.value).toEqual({ enabled: true, balance: 42, musicCost: 15, lyricsCost: 5, rewriteCost: 0, rewriteFreeRemaining: 0 })
  })

  it('积分开关关闭时不展示消耗提示', async () => {
    spies.requestJson.mockResolvedValue({ enabled: false, balance: 42, musicCost: 15, lyricsCost: 5 })
    const { credits, refreshCredits } = useCredits()
    await refreshCredits()
    expect(credits.value).toBeNull()
  })

  it('读不到余额时整块不展示，不猜测数字', async () => {
    spies.requestJson.mockRejectedValueOnce(new Error('offline'))
    const { credits, refreshCredits } = useCredits()
    await refreshCredits()
    expect(credits.value).toBeNull()
  })
})
