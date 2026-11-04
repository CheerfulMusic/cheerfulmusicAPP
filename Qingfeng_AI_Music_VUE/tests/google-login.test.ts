import { afterEach, describe, expect, it, vi } from 'vitest'
import { beginGoogleLogin, readGoogleIdentityStatus } from '../src/modules/identity/api/google-login'
import { googleLoginFeedback } from '../src/modules/identity/model/google-login'
import { resetCreatorSession, requestJson } from '../src/shared/http/api-client'
import { english } from '../src/shared/i18n/en'

afterEach(() => { vi.unstubAllGlobals(); resetCreatorSession() })
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const authorizationUrl = 'https://accounts.google.com/o/oauth2/v2/auth?client_id=client&state=state&response_type=code'

describe('Google sign-in', () => {
  it('preserves the current creator and uses a CSRF-protected POST before leaving the site', async () => {
    const fetch = vi.fn(async (url: string) => url.endsWith('/csrf') ? reply({ token: 'csrf-test' })
      : url.endsWith('/session') ? reply({ creatorId: 'existing-creator' }) : reply({ authorizationUrl }))
    vi.stubGlobal('fetch', fetch)
    await expect(beginGoogleLogin()).resolves.toBe(authorizationUrl)
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/public/csrf', '/api/public/session', '/api/public/auth/google/start'])
    expect(fetch).toHaveBeenLastCalledWith('/api/public/auth/google/start', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', body: '{}', headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-test' }),
    }))
  })

  it.each([
    'https://evil.example/o/oauth2/v2/auth',
    'https://accounts.google.com.evil.example/o/oauth2/v2/auth',
    'http://accounts.google.com/o/oauth2/v2/auth',
    'https://accounts.google.com/url?url=https://evil.example',
    'https://user:password@accounts.google.com/o/oauth2/v2/auth',
    'https://accounts.google.com/o/oauth2/v2/auth#untrusted',
    'javascript:alert(1)',
    '/api/public/auth/google/start',
    '',
  ])('rejects an unsafe or unexpected authorization redirect: %s', async url => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({ authorizationUrl: url })))
    await expect(requestJson('public/auth/google/start', 'POST', {})).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([
    { enabled: true },
    { enabled: true, authenticated: 'yes' },
    { enabled: false, authenticated: true },
    { enabled: true, authenticated: true, email: null },
    { enabled: true, authenticated: true, name: { html: 'name' } },
  ])('does not display unverified Google identity data', async status => {
    vi.stubGlobal('fetch', vi.fn(async () => reply(status)))
    await expect(readGoogleIdentityStatus()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('accepts disabled and connected states with optional identity display fields', async () => {
    for (const status of [{ enabled: false, authenticated: false }, { enabled: true, authenticated: true }, { enabled: true, authenticated: true, email: 'artist@example.com', name: 'Artist <3' }]) {
      vi.stubGlobal('fetch', vi.fn(async () => reply(status)))
      await expect(readGoogleIdentityStatus()).resolves.toEqual(status)
    }
  })

  it('keeps recovery APIs independent when Google configuration is unavailable', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/google/status')
      ? reply({ message: 'Not found' }, 404) : reply({ configured: true })))
    await expect(readGoogleIdentityStatus()).rejects.toMatchObject({ status: 404 })
    await expect(requestJson('app/identity/recovery')).resolves.toEqual({ configured: true })
  })

  it('shows success only after the server confirms the connected identity', () => {
    expect(googleLoginFeedback('success', true)?.kind).toBe('success')
    expect(googleLoginFeedback('success', false)?.kind).toBe('error')
    for (const result of ['cancelled', 'conflict', 'failed']) expect(googleLoginFeedback(result, false)?.kind).not.toBe('success')
    for (const result of [undefined, 'unexpected', ['success'], '<script>']) expect(googleLoginFeedback(result, true)).toBeUndefined()
  })

  it('has English text for every callback outcome, including an unconfirmed success', () => {
    for (const result of ['success', 'cancelled', 'conflict', 'failed']) {
      for (const authenticated of [true, false]) expect(english[googleLoginFeedback(result, authenticated)!.message]).toBeTruthy()
    }
  })
})
