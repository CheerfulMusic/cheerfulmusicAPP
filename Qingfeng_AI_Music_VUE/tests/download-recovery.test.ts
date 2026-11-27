// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { resetCreatorSession } from '../src/shared/http/api-client'
import { downloadSong } from '../src/modules/works/api/download'

vi.mock('../src/shared/identity/credit-balance', () => ({ loadCredits: vi.fn(async () => undefined) }))

afterEach(() => { resetCreatorSession(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

it('recovers when the real HTTP client aborts but the server commits the unlock later', async () => {
  vi.useFakeTimers()
  resetCreatorSession()
  let unlocked = false, posts = 0
  const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/public/csrf')) return json({ token: 'token' })
    if (url.endsWith('/public/session')) return json({ creatorId: 'creator' })
    if (url.endsWith('/download') && init.method === 'GET') return json({ unlocked, price: 15 })
    if (url.endsWith('/download') && init.method === 'POST') {
      posts++
      // Simulate the backend continuing after the browser has stopped waiting.
      return new Promise<Response>((resolve, reject) => {
        setTimeout(() => { unlocked = true; resolve(json({ unlocked: true, price: 15 })) }, 35000)
        init.signal!.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    }
    throw new Error('Unexpected request: ' + url)
  }))
  Object.defineProperty(window, 'confirm', { value: vi.fn(), configurable: true })
  Object.defineProperty(window, 'alert', { value: vi.fn(), configurable: true })
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const alert = vi.spyOn(window, 'alert').mockImplementation(() => {})
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

  const pending = downloadSong('app/works/work/audio/asset/download', '/song?download=true', 'song.mp3')
  await vi.advanceTimersByTimeAsync(30000)
  expect(posts).toBe(1)
  expect(click).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(6000)
  expect(await pending).toBe(true)
  expect(posts).toBe(1)
  expect(click).toHaveBeenCalledOnce()
  expect(alert).not.toHaveBeenCalled()
})
