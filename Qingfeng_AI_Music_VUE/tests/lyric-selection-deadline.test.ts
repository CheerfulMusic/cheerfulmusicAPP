// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { resetCreatorSession } from '../src/shared/http/api-client'
import { rewriteLyricSelection } from '../src/modules/works/api/detail'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); resetCreatorSession() })

it('waits for the repair result after the original preview timeout without sending another request', async () => {
  vi.useFakeTimers()
  resetCreatorSession()
  let calls = 0
  let finish!: (response: Response) => void
  let signal!: AbortSignal
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith('/csrf')) return Response.json({ token: 'csrf' })
    if (url.endsWith('/session')) return Response.json({ creatorId: 'owner' })
    if (url.endsWith('/rewrite-selection')) {
      calls++
      signal = init.signal as AbortSignal
      return new Promise<Response>((resolve, reject) => {
        finish = resolve
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
    }
    throw new Error('Unexpected request')
  }))
  const result = rewriteLyricSelection('work', { requestId: 'preview', workVersion: 0, lyrics: '原句', keptLines: [1], language: 'zh-CN' })
  await vi.advanceTimersByTimeAsync(120_000)
  expect(signal.aborted).toBe(false)
  expect(calls).toBe(1)
  finish(Response.json({ lyrics: '保留合格句并完成补写的结果' }))
  await expect(result).resolves.toEqual({ lyrics: '保留合格句并完成补写的结果' })
  expect(calls).toBe(1)
})
