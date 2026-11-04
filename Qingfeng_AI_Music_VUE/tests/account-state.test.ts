import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const resetPlayer = vi.hoisted(() => vi.fn())
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => ({ reset: resetPlayer }) }))
const connected = { enabled: true, authenticated: true, email: 'artist@example.com', name: 'Artist' }
const signedOut = { enabled: true, authenticated: false }
const reply = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })

beforeEach(() => { vi.resetModules(); resetPlayer.mockClear() })
afterEach(() => vi.unstubAllGlobals())

describe('shared account state', () => {
  it('deduplicates concurrent navigation reads and reuses a confirmed initial state', async () => {
    const fetch = vi.fn(async () => reply(connected))
    vi.stubGlobal('fetch', fetch)
    const { refreshAccountState, ensureAccountState, accountState } = await import('../src/shared/identity/account-state')
    const first = refreshAccountState(), second = refreshAccountState()
    expect(first).toBe(second)
    await expect(first).resolves.toEqual(connected)
    await expect(ensureAccountState()).resolves.toEqual(connected)
    expect(accountState.value).toEqual(connected)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each([{}, { loggedOut: false }, { loggedOut: 'true' }])('keeps the confirmed identity when a logout response is incomplete', async result => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => url.endsWith('/csrf') ? reply({ token: 'test-csrf' })
      : url.endsWith('/session') ? reply({ creatorId: 'current-creator' })
      : url.endsWith('/logout') ? reply(result) : reply(connected)))
    const { refreshAccountState, logoutAccount, accountState } = await import('../src/shared/identity/account-state')
    await refreshAccountState()
    await expect(logoutAccount()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(accountState.value).toEqual(connected)
    expect(resetPlayer).not.toHaveBeenCalled()
  })

  it('uses a CSRF-protected logout and keeps the confirmed signed-out state if the follow-up read fails', async () => {
    let statusReads = 0
    const fetch = vi.fn(async (url: string, _init?: RequestInit) => url.endsWith('/csrf') ? reply({ token: 'test-csrf' })
      : url.endsWith('/session') ? reply({ creatorId: 'current-creator' })
      : url.endsWith('/logout') ? reply({ loggedOut: true })
      : ++statusReads === 1 ? reply(connected) : reply({ message: 'Temporarily unavailable' }, 503))
    vi.stubGlobal('fetch', fetch)
    const { refreshAccountState, logoutAccount, accountState } = await import('../src/shared/identity/account-state')
    await refreshAccountState()
    const first = logoutAccount(), second = logoutAccount()
    expect(first).toBe(second)
    await expect(first).resolves.toBeUndefined()
    expect(fetch).toHaveBeenCalledWith('/api/public/session/logout', expect.objectContaining({
      method: 'POST', credentials: 'same-origin', body: '{}', headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'test-csrf' }),
    }))
    expect(accountState.value).toEqual(signedOut)
    expect(resetPlayer).toHaveBeenCalledTimes(1)
  })

  it('does not restore an old account when a pre-logout status read finishes late', async () => {
    let statusReads = 0, finishOld!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/csrf')) return reply({ token: 'test-csrf' })
      if (url.endsWith('/session')) return reply({ creatorId: 'current-creator' })
      if (url.endsWith('/logout')) return reply({ loggedOut: true })
      statusReads++
      if (statusReads === 1) return reply(connected)
      if (statusReads === 2) return new Promise<Response>(resolve => { finishOld = resolve })
      return reply(signedOut)
    }))
    const { refreshAccountState, logoutAccount, accountState } = await import('../src/shared/identity/account-state')
    await refreshAccountState()
    const oldRead = refreshAccountState()
    await logoutAccount()
    finishOld(reply(connected))
    await expect(oldRead).resolves.toEqual(signedOut)
    expect(accountState.value).toEqual(signedOut)
  })

  it('invalidates creator and playback caches when another tab changes the connected account', async () => {
    let statusReads = 0, csrfReads = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/csrf')) { csrfReads++; return reply({ token: 'test-csrf' }) }
      if (url.endsWith('/session')) return reply({ creatorId: 'current-creator' })
      return reply(++statusReads === 1 ? connected : { ...connected, email: 'another@example.com', name: 'Another artist' })
    }))
    const { refreshAccountState } = await import('../src/shared/identity/account-state')
    const { ensureCreator } = await import('../src/shared/http/api-client')
    await ensureCreator()
    await refreshAccountState()
    await refreshAccountState()
    await ensureCreator()
    expect(resetPlayer).toHaveBeenCalledTimes(1)
    expect(csrfReads).toBe(2)
  })
})
