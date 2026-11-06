import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchInviteStatus, submitInviteCode } from '../src/modules/identity/api/invite-gate'
import { ApiError } from '../src/shared/http/api-client'

afterEach(() => { vi.unstubAllGlobals() })
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })

describe('beta invite gate', () => {
  it('reads the gate status without a creator session', async () => {
    const fetch = vi.fn(async () => reply({ enabled: true, admitted: false, csrfToken: 'csrf-1', expiresAt: '' }))
    vi.stubGlobal('fetch', fetch)
    await expect(fetchInviteStatus()).resolves.toEqual({ enabled: true, admitted: false, csrfToken: 'csrf-1', expiresAt: '' })
    expect(fetch).toHaveBeenCalledWith('/api/public/invite/status', expect.objectContaining({
      method: 'GET', credentials: 'same-origin', cache: 'no-store'
    }))
  })

  it('rejects an incomplete status payload', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({ enabled: true })))
    await expect(fetchInviteStatus()).rejects.toBeInstanceOf(ApiError)
  })

  it('submits the code with the CSRF token returned by status', async () => {
    const fetch = vi.fn(async () => reply({ admitted: true }))
    vi.stubGlobal('fetch', fetch)
    await expect(submitInviteCode('csrf-1', 'BETA-2026')).resolves.toBeUndefined()
    expect(fetch).toHaveBeenCalledWith('/api/public/invite/enter', expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      body: JSON.stringify({ code: 'BETA-2026' }),
      headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-1' })
    }))
  })

  it('maps an invalid code to a readable error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({ code: 'INVITE_INVALID', message: 'invalid' }, 400)))
    await expect(submitInviteCode('csrf-1', 'bad')).rejects.toMatchObject({ code: 'INVITE_INVALID' })
  })

  it('maps rate limiting separately from an invalid code', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({ code: 'INVITE_RATE_LIMITED', message: 'slow down' }, 429)))
    await expect(submitInviteCode('csrf-1', 'bad')).rejects.toMatchObject({ code: 'INVITE_RATE_LIMITED' })
  })

  it('treats a 200 without admitted as a cookie failure', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({})))
    await expect(submitInviteCode('csrf-1', 'BETA-2026')).rejects.toBeInstanceOf(ApiError)
  })
})
