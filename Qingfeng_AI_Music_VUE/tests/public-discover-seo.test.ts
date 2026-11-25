// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { siteOrigin } from '../src/shared/i18n/home-seo'

const path = '/discover/songs/2397b14b-4b4a-45a0-bab2-798763ca7857/1'
const metadata = { title: 'Featured song', artist: 'Public artist', description: 'Public description', image: siteOrigin + '/brand/share-default-cover.png' }

beforeEach(() => {
  vi.resetModules()
  document.head.replaceChildren()
  const description = document.createElement('meta')
  description.name = 'description'
  document.head.append(description)
})

it('keeps server-rendered metadata during the first load and replaces it on navigation', async () => {
  const { updatePageLocale, updateDiscoverSongMetadata } = await import('../src/shared/i18n')
  const canonical = document.createElement('link')
  canonical.rel = 'canonical'
  canonical.href = siteOrigin + path
  canonical.setAttribute('data-site-seo', '')
  document.head.append(canonical)
  document.title = 'Server song metadata'

  updatePageLocale(path)
  expect(document.title).toBe('Server song metadata')
  expect(document.head.querySelector('link[rel=canonical]')).toBe(canonical)

  updateDiscoverSongMetadata(path, metadata)
  expect(document.title).toBe('Featured song — Public artist | 青妙Ai')
  expect(document.querySelector('meta[name=description]')?.getAttribute('content')).toBe(metadata.description)
  expect(document.querySelector('link[rel=canonical]')?.getAttribute('href')).toBe(siteOrigin + path)
  expect(JSON.parse(document.querySelector('script[type="application/ld+json"]')!.textContent!).name).toBe(metadata.title)

  updatePageLocale('/en' + path)
  updateDiscoverSongMetadata('/en' + path, metadata)
  expect(document.title).toBe('Featured song — Public artist | cheerfulmusic')
  expect(document.querySelector('link[rel=alternate][hreflang=zh-CN]')?.getAttribute('href')).toBe(siteOrigin + path)

  updateDiscoverSongMetadata('/en' + path, null)
  expect(document.querySelector('meta[name=robots]')?.getAttribute('content')).toBe('noindex')
  expect(document.querySelector('link[rel=canonical]')).toBeNull()
  updatePageLocale('/')
  expect(document.querySelector('meta[name=robots]')).toBeNull()
})

it('handles denied play counting without opening the invitation modal', async () => {
  const { recordDiscoverPlay } = await import('../src/modules/artist/api/artist')
  const { requestJson } = await import('../src/shared/http/api-client')
  const request = vi.fn(async () => new Response(JSON.stringify({ code: 'INVITE_REQUIRED' }), { status: 403 }))
  vi.stubGlobal('fetch', request)
  const invitation = vi.fn()
  window.addEventListener('qingfeng:invite-required', invitation)
  try {
    await expect(recordDiscoverPlay({ id: 'song', version: 1, title: 'Song', hasCover: false })).rejects.toMatchObject({ code: 'INVITE_REQUIRED' })
    expect(invitation).not.toHaveBeenCalled()
    await expect(requestJson('app/works', 'POST', {})).rejects.toMatchObject({ code: 'INVITE_REQUIRED' })
    expect(invitation).toHaveBeenCalledExactlyOnceWith(expect.any(Event))
  } finally {
    window.removeEventListener('qingfeng:invite-required', invitation)
    vi.unstubAllGlobals()
  }
})
