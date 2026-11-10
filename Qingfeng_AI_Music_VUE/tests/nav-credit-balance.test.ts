import { beforeEach, describe, expect, it, vi } from 'vitest'

const spies = vi.hoisted(() => ({ ensureCreator: vi.fn(), requestJson: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({ ensureCreator: spies.ensureCreator, requestJson: spies.requestJson }))
import { creditBalance, refreshCreditBalance } from '../src/shared/identity/credit-balance'

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
