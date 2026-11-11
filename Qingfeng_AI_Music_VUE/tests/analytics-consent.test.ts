import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { analyticsEnabled, setAnalyticsEnabled, track } from '../src/shared/analytics/track'

function memoryStorage() {
  const m = new Map<string, string>()
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => { m.set(k, v) },
    removeItem: (k: string) => { m.delete(k) },
    clear: () => m.clear(),
  }
}

let beacon: ReturnType<typeof vi.fn>
beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage())
  vi.stubGlobal('sessionStorage', memoryStorage())
  vi.stubGlobal('location', { pathname: '/' })
  vi.stubGlobal('document', { referrer: '' })
  vi.stubGlobal('crypto', { randomUUID: () => 'uuid' })
  beacon = vi.fn(() => true)
  vi.stubGlobal('navigator', { sendBeacon: beacon, doNotTrack: '0' })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true })))
})
afterEach(() => vi.unstubAllGlobals())

describe('分析数据同意前置（默认不采集）', () => {
  it('未明确同意前不发送、analyticsEnabled 为 false', () => {
    localStorage.clear()
    setAnalyticsEnabled(false)                 // 显式拒绝，确保内存状态一致
    localStorage.removeItem('qf.analytics.optout') // 回到“未决定”
    expect(analyticsEnabled()).toBe(false)
    track('pageview')
    expect(beacon).not.toHaveBeenCalled()
  })

  it('接受后才发送', () => {
    localStorage.clear()
    setAnalyticsEnabled(true)
    expect(analyticsEnabled()).toBe(true)
    track('pageview')
    expect(beacon).toHaveBeenCalledTimes(1)
  })

  it('拒绝后立即停止后续采集', () => {
    setAnalyticsEnabled(true)
    track('pageview')
    expect(beacon).toHaveBeenCalledTimes(1)
    setAnalyticsEnabled(false)
    track('pageview')
    expect(beacon).toHaveBeenCalledTimes(1) // 不再新增
  })

  it('撤回（再次拒绝）后仍停止', () => {
    setAnalyticsEnabled(true)
    track('pageview')
    setAnalyticsEnabled(false)
    track('pageview')
    expect(beacon).toHaveBeenCalledTimes(1)
  })

  it('存储不可用时内存兜底仍生效', () => {
    const blocked = {
      getItem: () => { throw new Error('x') },
      setItem: () => { throw new Error('x') },
      removeItem: () => { throw new Error('x') },
      clear: () => {},
    }
    vi.stubGlobal('localStorage', blocked)
    vi.stubGlobal('sessionStorage', blocked)
    setAnalyticsEnabled(true)
    expect(analyticsEnabled()).toBe(true)
    setAnalyticsEnabled(false)
    expect(analyticsEnabled()).toBe(false)
  })
})
