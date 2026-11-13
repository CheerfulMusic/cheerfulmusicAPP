import { afterEach, describe, expect, it, vi } from 'vitest'
import { requestJson } from '../src/shared/http/api-client'
import { validResponse } from '../src/shared/http/response-contracts'
afterEach(() => vi.unstubAllGlobals())
describe('welcome grant reflects the recorded amount', () => {
  it.each([50, 100])('accepts the current or historical grant of %i without dropping the one-time receipt', async amount => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ show: true, amount }), { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetcher)
    await expect(requestJson('app/welcome', 'POST', {})).resolves.toEqual({ show: true, amount })
    expect(fetcher).toHaveBeenCalledOnce()
  })
  it.each([0, -1, 1.5, '50', Number.MAX_SAFE_INTEGER + 1, null])('rejects invalid displayed amount %j', amount => {
    expect(validResponse('app/welcome', 'POST', { show: true, amount })).toBe(false)
  })
  it('accepts a previously claimed receipt only with zero amount', () => {
    expect(validResponse('app/welcome', 'POST', { show: false, amount: 0 })).toBe(true)
    expect(validResponse('app/welcome', 'POST', { show: false, amount: 50 })).toBe(false)
  })
})
