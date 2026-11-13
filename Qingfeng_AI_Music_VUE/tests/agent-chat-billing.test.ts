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
it('asks before paid continuation and keeps the original idempotency key', async () => {
  api.request.mockRejectedValueOnce(new ApiError('1 积分', 400, 'AGENT_CHAT_PAYMENT_REQUIRED')).mockResolvedValueOnce({ messages: [] })
  await sendMessage('c', '继续', 'same-key', 'zh-CN')
  expect(window.confirm).toHaveBeenCalledWith('1 积分')
  expect(api.request.mock.calls[1][2]).toEqual({ text: '继续', requestKey: 'same-key', locale: 'zh-CN', paidConfirmed: true })
})
it('does not send a charge after cancellation', async () => {
  vi.mocked(window.confirm).mockReturnValue(false)
  api.request.mockRejectedValueOnce(new ApiError('1 积分', 400, 'AGENT_CHAT_PAYMENT_REQUIRED'))
  await expect(sendMessage('c', '继续', 'key', 'zh-CN')).rejects.toMatchObject({ code: 'AGENT_CHAT_PAYMENT_CANCELLED' })
  expect(api.request).toHaveBeenCalledTimes(1)
})
it('never automatically retries a network failure', async () => {
  api.request.mockRejectedValueOnce(new Error('network'))
  await expect(sendMessage('c', '继续', 'key', 'zh-CN')).rejects.toThrow('network')
  expect(api.request).toHaveBeenCalledTimes(1); expect(window.confirm).not.toHaveBeenCalled()
})
