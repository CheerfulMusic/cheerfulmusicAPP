import { afterEach, describe, expect, it, vi } from 'vitest'
import { beginLogtoLogin } from '../src/modules/identity/api/logto-login'
import { requestJson, resetCreatorSession } from '../src/shared/http/api-client'
import { maskedPhoneLabel } from '../src/shared/identity/phone-label'

afterEach(() => { vi.unstubAllGlobals(); resetCreatorSession() })
const reply = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })
const authorizationUrl = 'https://auth.cheerfulmusic.com/oidc/auth?client_id=client&state=state&response_type=code'

describe('Logto sign-in', () => {
  it('starts only through a CSRF-protected server request bound to the existing creator', async () => {
    const fetch = vi.fn(async (url: string) => url.endsWith('/csrf') ? reply({ token: 'csrf-test' })
      : url.endsWith('/session') ? reply({ creatorId: 'existing-creator' }) : reply({ authorizationUrl }))
    vi.stubGlobal('fetch', fetch)
    await expect(beginLogtoLogin()).resolves.toBe(authorizationUrl)
    expect(fetch.mock.calls.map(call => call[0])).toEqual(['/api/public/csrf', '/api/public/session', '/api/public/auth/logto/start'])
    expect(fetch).toHaveBeenLastCalledWith('/api/public/auth/logto/start', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-test' }),
    }))
  })
  it('passes the selected identifier to the server-owned OIDC flow', async () => {
    const fetch = vi.fn(async (url: string) => url.endsWith('/csrf') ? reply({ token: 'csrf-test' })
      : url.endsWith('/session') ? reply({ creatorId: 'existing-creator' }) : reply({ authorizationUrl }))
    vi.stubGlobal('fetch', fetch)
    await expect(beginLogtoLogin({ method: 'phone', loginHint: '+8613800123456' })).resolves.toBe(authorizationUrl)
    expect(fetch).toHaveBeenLastCalledWith('/api/public/auth/logto/start', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ method: 'phone', loginHint: '+8613800123456' }),
    }))
  })
  it('requests Apple social sign-in through Logto without a client-side connector identifier', async () => {
    const fetch = vi.fn(async (url: string) => url.endsWith('/csrf') ? reply({ token: 'csrf-test' })
      : url.endsWith('/session') ? reply({ creatorId: 'existing-creator' }) : reply({ authorizationUrl }))
    vi.stubGlobal('fetch', fetch)
    await expect(beginLogtoLogin({ method: 'apple' })).resolves.toBe(authorizationUrl)
    expect(fetch).toHaveBeenLastCalledWith('/api/public/auth/logto/start', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ method: 'apple' }),
    }))
  })
  it.each([
    'https://evil.example/oidc/auth',
    'https://auth.cheerfulmusic.com.evil.example/oidc/auth',
    'http://auth.cheerfulmusic.com/oidc/auth',
    'https://auth.cheerfulmusic.com/redirect',
    'https://user:password@auth.cheerfulmusic.com/oidc/auth',
    'http://localhost:3002/oidc/auth',
    'http://127.0.0.1:3001/oidc/auth',
    'http://localhost:3001/oidc/auth#fragment',
    'javascript:alert(1)',
  ])('rejects an unexpected provider redirect: %s', async url => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({ authorizationUrl: url })))
    await expect(requestJson('public/auth/logto/start', 'POST', {})).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('accepts only the configured local Logto authorization endpoint', async () => {
    const url = 'http://localhost:3001/oidc/auth?client_id=local&state=state&response_type=code'
    vi.stubGlobal('fetch', vi.fn(async () => reply({ authorizationUrl: url })))
    await expect(requestJson('public/auth/logto/start', 'POST', {})).resolves.toEqual({ authorizationUrl: url })
  })
  it('accepts a masked phone status but rejects malformed identity fields', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => reply({ enabled: true, authenticated: true, emailEnabled: true, phoneEnabled: true, appleEnabled: true, phoneMasked: '138****1234' })))
    await expect(requestJson('public/auth/logto/status')).resolves.toMatchObject({ authenticated: true, phoneMasked: '138****1234' })
    vi.stubGlobal('fetch', vi.fn(async () => reply({ enabled: true, authenticated: false, phoneEnabled: 'yes' })))
    await expect(requestJson('public/auth/logto/status')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    vi.stubGlobal('fetch', vi.fn(async () => reply({ enabled: true, authenticated: false, appleEnabled: 'yes' })))
    await expect(requestJson('public/auth/logto/status')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    vi.stubGlobal('fetch', vi.fn(async () => reply({ enabled: true, authenticated: true, phoneMasked: 13800123456 })))
    await expect(requestJson('public/auth/logto/status')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(maskedPhoneLabel('13800123456')).toBe('手机号尾号 3456')
  })
})
