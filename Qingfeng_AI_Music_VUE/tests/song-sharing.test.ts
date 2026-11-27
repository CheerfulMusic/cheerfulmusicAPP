// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { songShareUrl, lyricQuotes, posterFilename, localShareAddress, shareSongCard } from '../src/modules/works/model/song-sharing'
import { textLines } from '../src/modules/works/model/song-poster'
import { validResponse } from '../src/shared/http/response-contracts'

afterEach(() => vi.unstubAllGlobals())
describe('public song sharing', () => {
  it('only builds a public token URL and never carries private editor routes or query strings', () => {
    const token = 'A'.repeat(43)
    expect(songShareUrl(token, 'https://cheerfulmusic.com/works/private?secret=x')).toBe(`https://cheerfulmusic.com/song/${token}`)
    for (const bad of ['../works/secret', 'x?owner=1', 'A'.repeat(42)]) expect(() => songShareUrl(bad, location.origin)).toThrow()
    expect(() => songShareUrl(token, 'javascript:alert(1)')).toThrow()
  })
  it('builds a short URL only for the matching server-supplied code', () => {
    const shortCode = 'grQVIB4zmKxV_ueWTppQgi'
    const token = shortCode + 'A'.repeat(21)
    const origin = 'https://cheerfulmusic.com/works/private?secret=x'
    expect(songShareUrl(token, origin, shortCode)).toBe('https://cheerfulmusic.com/s/' + shortCode)
    for (const unavailable of [undefined, null, '../etc/passwd', shortCode.toLowerCase()]) {
      expect(songShareUrl(token, origin, unavailable)).toBe('https://cheerfulmusic.com/song/' + token)
    }
  })
  it('accepts older status cards and checks a provided short code against the card ID', () => {
    const token = 'A'.repeat(43)
    const card = { songId: token, title: '歌曲', lyrics: '', hasCover: false, expiresAt: '2030-01-01T00:00:00Z' }
    const status = { status: 'SHAREABLE', views: 0, plays: 0, card }
    const path = 'app/works/work/shares/asset'
    expect(validResponse(path, 'GET', status)).toBe(true)
    for (const shortCode of [null, token.slice(0, 22)]) {
      expect(validResponse(path, 'GET', { ...status, card: { ...card, shortCode } })).toBe(true)
    }
    expect(validResponse(path, 'GET', { ...status, card: { ...card, shortCode: 'a'.repeat(22) } })).toBe(false)
  })
  it('keeps original lyrics but omits section labels, empty lines and duplicate quotes', () => {
    expect(lyricQuotes('# 《薄荷》\n**[Verse 1]**\r\n窗台那盆薄荷\n\n【副歌】\nKeep the light on 🌙\n窗台那盆薄荷'))
      .toEqual(['窗台那盆薄荷', 'Keep the light on 🌙'])
  })
  it('does not silently replace a local share with a production URL whose token would be invalid', () => {
    for (const host of ['127.0.0.1:5173', 'localhost:5173', '192.168.1.9', '[::1]:5173', '172.20.1.4'])
      expect(localShareAddress(`http://${host}/song/x`)).toBe(true)
    expect(localShareAddress('https://cheerfulmusic.com/song/x')).toBe(false)
    expect(localShareAddress('https://localhost.example.com/song/x')).toBe(false)
  })
  it('keeps Unicode filenames while removing filesystem control characters', () => {
    expect(posterFilename('妈妈/的歌:<3?')).toBe('妈妈的歌3-poster.png')
    expect(posterFilename('  ')).toBe('Cheerful-Music-poster.png')
  })
  it('cancelling native share does not copy or report a successful send', async () => {
    const copy = vi.fn()
    vi.stubGlobal('navigator', { share: vi.fn().mockRejectedValue(new DOMException('dismissed', 'AbortError')), clipboard: { writeText: copy } })
    expect(await shareSongCard({ url: 'https://cheerfulmusic.com/song/x' })).toBe('cancelled')
    expect(copy).not.toHaveBeenCalled()
  })
  it('falls back to copying when native sharing is absent or blocked', async () => {
    const copy = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText: copy } })
    expect(await shareSongCard({ url: 'https://cheerfulmusic.com/song/x' })).toBe('copied')
    vi.stubGlobal('navigator', { share: vi.fn().mockRejectedValue(new DOMException('blocked', 'NotAllowedError')), clipboard: { writeText: copy } })
    expect(await shareSongCard({ url: 'https://cheerfulmusic.com/song/x' })).toBe('copied')
    expect(copy).toHaveBeenCalledTimes(2)
  })
  it('wraps bilingual long titles to fit and does not split emoji code points', () => {
    const measure = (text: string) => Array.from(text).length * 10
    expect(textLines('AB🌙中文EFG', measure, 40, 2)).toEqual(['AB🌙中', '文EFG'])
    expect(textLines('ABCDEFGHIJKLMN', measure, 40, 2)).toEqual(['ABCD', 'EFG…'])
  })
})
