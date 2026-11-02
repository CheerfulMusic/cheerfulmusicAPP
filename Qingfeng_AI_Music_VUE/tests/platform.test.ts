import { describe, expect, it } from 'vitest'
import { assertShareUrl } from '../src/platform/contracts'
import { createNativePlatform } from '../src/platform/native'

describe('跨端能力边界', () => {
  it('拒绝把脚本协议交给分享桥', () => {
    expect(() => assertShareUrl('javascript:alert(1)')).toThrow()
    expect(() => assertShareUrl('data:text/html,test')).toThrow()
  })
  it('允许正常HTTPS收礼地址', () => {
    expect(assertShareUrl('https://example.com/gift/abc').protocol).toBe('https:')
  })
  it('未实现原生插件时明确报告不可用', async () => {
    const platform = createNativePlatform('android')
    expect(platform.capabilities.backgroundAudio).toBe(false)
    await expect(platform.shareLink({ title: 'test', url: 'https://example.com' })).rejects.toThrow('尚未接入')
  })
})
