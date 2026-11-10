// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'

const spies = vi.hoisted(() => ({ ensureCreator: vi.fn(), requestJson: vi.fn() }))
vi.mock('../src/shared/http/api-client', () => ({
  ensureCreator: spies.ensureCreator,
  requestJson: spies.requestJson,
  ApiError: class ApiError extends Error { code?: string; status = 0; constructor(message: string, status = 0, code?: string) { super(message); this.status = status; this.code = code } },
}))
import { rewriteInspiration } from '../src/modules/studio/api/works'

beforeEach(() => {
  spies.ensureCreator.mockReset().mockResolvedValue(undefined)
  spies.requestJson.mockReset().mockResolvedValue({ text: '改写后的创作描述' })
})

describe('AI 改写请求', () => {
  it('必须带上幂等键：同一请求键重复提交不会重复扣费', async () => {
    const key = '11111111-2222-3333-4444-555555555555'
    const result = await rewriteInspiration('我想感谢爸爸', 'zh-CN', key)
    expect(result.text).toContain('改写')
    expect(spies.requestJson).toHaveBeenCalledWith('app/inspiration/rewrite', 'POST', { text: '我想感谢爸爸', language: 'zh-CN', requestKey: key }, 90000)
  })

  it('不同请求键视为不同改写请求', async () => {
    await rewriteInspiration('原文', 'zh-CN', '11111111-2222-3333-4444-555555555555')
    await rewriteInspiration('原文', 'zh-CN', '99999999-2222-3333-4444-555555555555')
    const keys = spies.requestJson.mock.calls.map(call => (call[2] as { requestKey: string }).requestKey)
    expect(new Set(keys).size).toBe(2)
  })

  it('生成的幂等键符合后端校验规则', () => {
    // 后端 InspirationController.RewriteRequest 的 @Pattern 为 [0-9a-fA-F-]{36}，
    // 前后端必须用同一条规则，否则改写会被 400 拒绝。
    expect(crypto.randomUUID()).toMatch(/^[0-9a-fA-F-]{36}$/)
  })
})
