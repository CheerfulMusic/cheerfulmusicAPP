// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'

const spies = vi.hoisted(() => ({ ensureCreator: vi.fn(), requestJson: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({ ensureCreator: spies.ensureCreator, requestJson: spies.requestJson, currentCreatorId: () => 'owner-a' }))
import { announceCreditsChanged, creditBalance, refreshCreditBalance } from '../src/shared/identity/credit-balance'
import { useCredits } from '../src/shared/credits/useCredits'

beforeEach(() => {
  spies.ensureCreator.mockReset().mockResolvedValue(undefined)
  spies.requestJson.mockReset().mockResolvedValue({ enabled: true, balance: 100, musicCost: 15, lyricsCost: 5 })
  return refreshCreditBalance(false)
})

describe('充值到账后的余额刷新', () => {
  it('收到积分变化广播后重新读取后端余额', async () => {
    await refreshCreditBalance(true)
    expect(creditBalance.value).toBe(100)
    spies.requestJson.mockResolvedValueOnce({ enabled: true, balance: 300, musicCost: 15, lyricsCost: 5 })
    announceCreditsChanged()
    await vi.waitFor(() => expect(creditBalance.value).toBe(300))
    expect(spies.requestJson).toHaveBeenCalledWith('app/credits')
  })

  it('创作区与顶栏共用同一份余额，不会出现两个数字', async () => {
    await refreshCreditBalance(true)
    const { credits } = useCredits()
    expect(credits.value?.balance).toBe(100)
    spies.requestJson.mockResolvedValueOnce({ enabled: true, balance: 300, musicCost: 15, lyricsCost: 5 })
    announceCreditsChanged()
    await vi.waitFor(() => expect(credits.value?.balance).toBe(300))
    expect(credits.value?.balance).toBe(creditBalance.value)
  })

  it('未登录时不因广播发起请求', async () => {
    await refreshCreditBalance(false)
    const calls = spies.requestJson.mock.calls.length
    announceCreditsChanged()
    await new Promise(resolve => setTimeout(resolve, 10))
    expect(spies.requestJson.mock.calls.length).toBe(calls)
  })

  it('余额由后端返回决定，前端广播不做任何本地加减', async () => {
    await refreshCreditBalance(true)
    // 后端返回 100，即使广播到达，余额也不会变成别的数字。
    announceCreditsChanged()
    await vi.waitFor(() => expect(spies.requestJson).toHaveBeenCalled())
    expect(creditBalance.value).toBe(100)
  })
})
