// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import QRCode from 'qrcode'
import {
  copyInternationalShareLink, internationalShareHref, shareInternationalPoster, shareInternationalSong,
} from '../src/modules/works/model/international-song-sharing'
import { renderInternationalSongPoster, textLines } from '../src/modules/works/model/song-poster'

const payload = {
  title: '夜 & Day 🎵',
  text: 'Come listen to my song',
  url: `https://cheerfulmusic.com/song/${'A'.repeat(43)}?lang=en`,
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.replaceChildren()
})

describe('international share destinations', () => {
  it('passes the current link and Unicode invitation to the corresponding platform endpoints', () => {
    const whatsapp = new URL(internationalShareHref('whatsapp', payload)!)
    expect(whatsapp.origin).toBe('https://wa.me')
    expect(whatsapp.searchParams.get('text')).toBe(`${payload.title}\n${payload.text}\n${payload.url}`)

    const facebook = new URL(internationalShareHref('facebook', payload)!)
    expect(facebook.origin + facebook.pathname).toBe('https://www.facebook.com/sharer/sharer.php')
    expect([...facebook.searchParams]).toEqual([['u', payload.url]])

    const telegram = new URL(internationalShareHref('telegram', payload)!)
    const x = new URL(internationalShareHref('x', payload)!)
    expect(telegram.origin + telegram.pathname).toBe('https://t.me/share/url')
    expect(x.origin + x.pathname).toBe('https://twitter.com/intent/tweet')
    expect(telegram.searchParams.get('url')).toBe(payload.url)
    expect(x.searchParams.get('url')).toBe(payload.url + '&card=x')
    for (const destination of [telegram, x]) {
      expect(destination.searchParams.get('text')).toBe(`${payload.title}\n${payload.text}`)
    }

    const email = new URL(internationalShareHref('email', { ...payload, title: `${payload.title}\r\nBcc:test` })!)
    expect(email.protocol).toBe('mailto:')
    expect([...email.searchParams]).toEqual([
      ['subject', `${payload.title}  Bcc:test`], ['body', `${payload.text}\r\n\r\n${payload.url}`],
    ])
    expect(internationalShareHref('whatsapp', { ...payload, url: '' })).toBeUndefined()
  })

  it('preserves other X URL parameters, overwrites the single card selector and strips fragments', () => {
    const address = payload.url + '&tag=cafe%20music&card=other&card=other#share-friend'
    const destination = new URL(internationalShareHref('x', { ...payload, url: address })!)
    const card = new URL(destination.searchParams.get('url')!)
    expect(card.origin + card.pathname).toBe(payload.url.split('?')[0])
    expect([...card.searchParams]).toEqual([['lang', 'en'], ['tag', 'cafe music'], ['card', 'x']])
    expect(card.hash).toBe('')
    expect(internationalShareHref('x', { ...payload, url: 'invalid' })).toBeUndefined()
  })

  it('keeps the invitation and link when a multilingual X title exceeds the compose budget', () => {
    const title = '夜'.repeat(140) + '🎵'
    const destination = new URL(internationalShareHref('x', { ...payload, title })!)
    const [shortTitle, invitation] = destination.searchParams.get('text')!.split('\n')
    expect(shortTitle).toMatch(/^夜+…$/u)
    expect(invitation).toBe(payload.text)
    expect(shortTitle.length * 2 + 1 + invitation.length + 1 + 23).toBeLessThanOrEqual(280)
    expect(destination.searchParams.get('url')).toBe(payload.url + '&card=x')
  })

  it('encodes malformed text safely and normalizes mail-body newlines', () => {
    const input = { ...payload, title: '夜\ud800 🎵', text: 'First\nSecond\rThird\r\nFourth' }
    const telegram = new URL(internationalShareHref('telegram', input)!)
    expect(telegram.searchParams.get('text')).toBe('夜� 🎵\n' + input.text)
    const email = new URL(internationalShareHref('email', input)!)
    expect(email.searchParams.get('subject')).toBe('夜� 🎵')
    expect(email.searchParams.get('body')).toBe(`First\r\nSecond\r\nThird\r\nFourth\r\n\r\n${payload.url}`)
  })

  it('uses the legacy copy command with a selected full link when clipboard access is denied', async () => {
    const input = document.body.appendChild(document.createElement('input'))
    input.value = payload.url
    input.readOnly = true
    const copy = vi.fn(() => {
      expect(document.activeElement).toBe(input)
      expect(input.selectionStart).toBe(0)
      expect(input.selectionEnd).toBe(payload.url.length)
      return true
    })
    const systemClipboard = vi.fn().mockRejectedValue(new DOMException('blocked', 'NotAllowedError'))
    vi.stubGlobal('navigator', { clipboard: { writeText: systemClipboard } })
    vi.stubGlobal('document', { get activeElement() { return input.ownerDocument.activeElement }, execCommand: copy })
    await expect(copyInternationalShareLink(payload.url, input)).resolves.toBe(true)
    expect(copy).toHaveBeenCalledExactlyOnceWith('copy')
    expect(systemClipboard).toHaveBeenCalledExactlyOnceWith(payload.url)
  })

  it('abandons DOM fallback when the song changes during the clipboard request', async () => {
    const input = document.body.appendChild(document.createElement('input'))
    input.value = payload.url
    const focus = vi.spyOn(input, 'focus')
    let reject!: (error: Error) => void
    const writeText = vi.fn(() => new Promise<void>((_, fail) => { reject = fail }))
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    let current = true
    const pending = copyInternationalShareLink(payload.url, input, () => current)
    current = false
    reject(new Error('Clipboard permission denied'))
    await expect(pending).resolves.toBe(false)
    expect(focus).not.toHaveBeenCalled()
  })

  it('treats an unavailable file target and a dismissed share sheet as distinct results', async () => {
    const share = vi.fn().mockRejectedValue(new DOMException('dismissed', 'AbortError'))
    const canShare = vi.fn(() => false)
    vi.stubGlobal('navigator', { share, canShare })
    await expect(shareInternationalPoster(new Blob(['poster'], { type: 'image/png' }), payload.title)).resolves.toBe('unavailable')
    expect(canShare).toHaveBeenCalledOnce()
    expect(share).not.toHaveBeenCalled()
    await expect(shareInternationalSong(payload)).resolves.toBe('cancelled')
    expect(share).toHaveBeenCalledExactlyOnceWith(payload)
  })

  it('hands off only a nonempty PNG and keeps its filename valid at a surrogate boundary', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    const canShare = vi.fn(() => true)
    vi.stubGlobal('navigator', { share, canShare })
    await expect(shareInternationalPoster(new Blob([], { type: 'image/png' }), payload.title)).resolves.toBe('unavailable')
    await expect(shareInternationalPoster(new Blob(['text'], { type: 'text/plain' }), payload.title)).resolves.toBe('unavailable')
    expect(canShare).not.toHaveBeenCalled()
    const title = 'a'.repeat(69)
    const poster = new Blob(['poster image'], { type: 'image/png' })
    await expect(shareInternationalPoster(poster, title + '🎵')).resolves.toBe('shared')
    expect(share).toHaveBeenCalledExactlyOnceWith({ files: [expect.objectContaining({ name: title + '-poster.png', size: poster.size })] })
  })
})

describe('international poster', () => {
  it.each([
    [{}, ['Come listen to my song', 'Scan to listen']],
    [{ quote: '歌词摘句', signature: '手写署名', scanLabel: '扫码试听' }, ['歌词摘句', '手写署名', '扫码试听']],
  ] as const)('renders a 1080 × 1920 PNG with the exact song QR and poster text', async (options, expected) => {
    const sources: string[] = []
    class PosterImage {
      naturalWidth = 1200
      naturalHeight = 800
      onload?: () => void
      set src(url: string) { sources.push(url); queueMicrotask(() => this.onload?.()) }
    }
    vi.stubGlobal('Image', PosterImage)
    const drawImage = vi.fn()
    const fillText = vi.fn()
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({
      drawImage, createLinearGradient: () => ({ addColorStop: vi.fn() }), measureText: () => ({ width: 100 }),
      fillRect: vi.fn(), fillText, save: vi.fn(), restore: vi.fn(), beginPath: vi.fn(), rect: vi.fn(),
      clip: vi.fn(), fill: vi.fn(),
    } as unknown as CanvasRenderingContext2D))
    const output = new Blob(['rendered test canvas'], { type: 'image/png' })
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function (this: HTMLCanvasElement, callback, type) {
      expect([this.width, this.height, type]).toEqual([1080, 1920, 'image/png'])
      callback(output)
    })
    const qr = vi.spyOn(QRCode, 'toCanvas').mockImplementation(() => undefined)
    const cover = `/api/songs/${'A'.repeat(43)}/cover`
    await expect(renderInternationalSongPoster({ title: payload.title, artist: 'Artist', cover, link: payload.url, ...options }))
      .resolves.toBe(output)
    expect(sources).toEqual([cover, '/brand/logo-symbol.svg'])
    expect(drawImage).toHaveBeenNthCalledWith(2, expect.any(PosterImage), 200, 0, 800, 800, 72, 202, 936, 936)
    expect(fillText.mock.calls.map(args => args[0])).toEqual(expect.arrayContaining([payload.title, 'Artist', ...expected]))
    expect(qr).toHaveBeenCalledExactlyOnceWith(expect.any(HTMLCanvasElement), payload.url, {
      width: 236, margin: 4, errorCorrectionLevel: 'M',
    })
  })

  it('stops measuring a long lyric line once the available poster lines are filled', () => {
    const measure = vi.fn((text: string) => text.length)
    expect(textLines('a'.repeat(10_000), measure, 100, 2)).toEqual(['a'.repeat(100), 'a'.repeat(99) + '…'])
    expect(measure.mock.calls.length).toBeLessThan(220)
  })
})
