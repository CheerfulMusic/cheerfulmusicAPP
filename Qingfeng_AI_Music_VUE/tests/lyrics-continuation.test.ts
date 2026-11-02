import { afterEach, expect, it, vi } from 'vitest'
afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

it('reuses the continuation request after reload while separating changed story versions and languages', async () => {
  const storage = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => storage.get(key), setItem: (key: string, value: string) => storage.set(key, value) })
  const first = await import('../src/modules/studio/model/lyrics-continuation')
  const key = first.continuationRequestKey('work-1', 0, 'zh-CN')
  vi.resetModules()
  const reloaded = await import('../src/modules/studio/model/lyrics-continuation')
  expect(reloaded.continuationRequestKey('work-1', 0, 'zh-CN')).toBe(key)
  expect(reloaded.continuationRequestKey('work-1', 1, 'zh-CN')).not.toBe(key)
  expect(reloaded.continuationRequestKey('work-1', 0, 'en')).not.toBe(key)
})

it('prevents repeated clicks within the page when browser storage is unavailable', async () => {
  vi.stubGlobal('sessionStorage', { getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') } })
  const { continuationRequestKey } = await import('../src/modules/studio/model/lyrics-continuation')
  expect(continuationRequestKey('work-2', 0, 'en')).toBe(continuationRequestKey('work-2', 0, 'en'))
})
