// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ request: vi.fn(), ensure: vi.fn(async () => undefined) }))
vi.mock('../src/shared/http/api-client', () => ({
  ensureCreator: api.ensure, requestJson: api.request, getJson: vi.fn(),
  ApiError: class ApiError extends Error { constructor(message: string, public status: number, public code?: string) { super(message) } },
}))
import { ApiError } from '../src/shared/http/api-client'
import { sendMessage } from '../src/modules/agent/api/chat'
beforeEach(() => { vi.resetAllMocks(); Object.defineProperty(window, 'confirm', { configurable: true, value: vi.fn(() => true) }) })
it('sends once with inline pricing accepted and never opens a payment dialog', async () => {
  api.request.mockResolvedValueOnce({ messages: [] })
  await sendMessage('c', '继续', 'same-key', 'zh-CN')
  expect(window.confirm).not.toHaveBeenCalled()
  expect(api.request).toHaveBeenCalledTimes(1)
  expect(api.request.mock.calls[0][2]).toEqual({ text: '继续', requestKey: 'same-key', locale: 'zh-CN', paidConfirmed: true })
})
it('never authorizes a purchase for an automatic continuation', async () => {
  await sendMessage('c', '继续已安排的操作', 'continuation-key', 'zh-CN')
  expect(api.request.mock.calls[0][2].paidConfirmed).toBe(false)
})
it('surfaces insufficient balance without retry or dialog', async () => {
  api.request.mockRejectedValueOnce(new ApiError('积分不足', 400, 'INSUFFICIENT_CREDITS'))
  await expect(sendMessage('c', '继续', 'key', 'zh-CN')).rejects.toMatchObject({ code: 'INSUFFICIENT_CREDITS' })
  expect(api.request).toHaveBeenCalledTimes(1)
  expect(window.confirm).not.toHaveBeenCalled()
})
it('never automatically retries a network failure', async () => {
  api.request.mockRejectedValueOnce(new Error('network'))
  await expect(sendMessage('c', '继续', 'key', 'zh-CN')).rejects.toThrow('network')
  expect(api.request).toHaveBeenCalledTimes(1); expect(window.confirm).not.toHaveBeenCalled()
})
