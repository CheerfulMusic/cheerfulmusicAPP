import { afterEach, describe, expect, it, vi } from 'vitest'
import { enterInvite, readInviteStatus } from '../src/modules/beta-access/api'
import { requestJson } from '../src/shared/http/api-client'

afterEach(() => vi.unstubAllGlobals())
const reply = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
describe('Independent beta access', () => {
  it.each([{}, { enabled: false, admitted: false, csrfToken: '', expiresAt: '' }, { enabled: true, admitted: false, csrfToken: '', expiresAt: '' }, { enabled: true, admitted: true, csrfToken: 'token', expiresAt: 'invalid' }])('fails closed on incomplete or inconsistent status: %j', async data => {
    vi.stubGlobal('fetch', vi.fn(async () => reply(data)))
    await expect(readInviteStatus()).rejects.toMatchObject({ code: 'UNAVAILABLE' })
  })
  it('accepts disabled mode and server-confirmed admission without creating a creator session', async () => {
    for (const status of [{ enabled: false, admitted: true, csrfToken: '', expiresAt: '' }, { enabled: true, admitted: true, csrfToken: 'token', expiresAt: '2026-12-01T00:00:00Z' }]) {
      const fetch = vi.fn(async () => reply(status)); vi.stubGlobal('fetch', fetch)
      await expect(readInviteStatus()).resolves.toEqual(status)
      expect(fetch).toHaveBeenCalledTimes(1)
      expect(fetch).toHaveBeenCalledWith('/api/public/invite/status', expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }))
    }
  })
  it('uses a CSRF-protected POST and never puts invitation secrets in a URL', async () => {
    const fetch = vi.fn(async () => reply({ admitted: true })); vi.stubGlobal('fetch', fetch)
    await enterInvite('  private-invitation  ', 'server-csrf')
    expect(fetch).toHaveBeenCalledWith('/api/public/invite/enter', expect.objectContaining({ method: 'POST', body: '{"code":"private-invitation"}', headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'server-csrf' }) }))
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it.each([[403, 'INVITE_INVALID', 'INVALID'], [429, 'INVITE_RATE_LIMITED', 'RATE_LIMITED'], [503, 'SERVER_ERROR', 'UNAVAILABLE']])('reports %i without automatically retrying', async (status, code, expected) => {
    const fetch = vi.fn(async () => reply({ code }, Number(status))); vi.stubGlobal('fetch', fetch)
    await expect(enterInvite('private-invitation', 'csrf')).rejects.toMatchObject({ code: expected })
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('never admits on HTML, broken bodies, or an empty success response', async () => {
    for (const raw of ['<html>proxy error</html>', '', '{}']) {
      vi.stubGlobal('fetch', vi.fn(async () => new Response(raw, { status: 200 })))
      await expect(enterInvite('private-invitation', 'csrf')).rejects.toMatchObject({ code: 'UNAVAILABLE' })
    }
  })
  it('asks for re-verification without retrying a rejected generation request', async () => {
    const target = new EventTarget(), required = vi.fn()
    target.addEventListener('qingfeng:invite-required', required)
    vi.stubGlobal('window', target)
    const fetch = vi.fn(async () => reply({ code: 'INVITE_REQUIRED', message: '请先输入内测邀请码' }, 403))
    vi.stubGlobal('fetch', fetch)
    await expect(requestJson('app/works/work/generations', 'POST', { revisionId: 'revision' })).rejects.toMatchObject({ code: 'INVITE_REQUIRED', status: 403 })
    expect(required).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
