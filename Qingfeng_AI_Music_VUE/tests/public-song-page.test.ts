// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { describe, expect, it, vi } from 'vitest'

const script = readFileSync('public/brand/song-share.js', 'utf8')
const handoff = readFileSync('public/brand/wechat-handoff.js', 'utf8')
const template = readFileSync('../Qingfeng_AI_Music/qf-server/src/main/resources/song.html', 'utf8')
const description = '我在 Cheerful Music 创作了一首歌曲，邀请你来听听'

function openSong(id: string, inWechat = false, agent = 'WebTest') {
  const site = 'https://cheerfulmusic.com'
  const url = site + '/song/' + id
  const api = site + '/api/songs/' + id
  const fields: Record<string, string> = {
    title: '歌曲 ' + id, creator: '青风', url, image: api + '/cover',
    lyricLines: '<p>第一句</p>', duration: '', durationLabel: '–:––', artistId: '',
    thumbnail: api + '/thumbnail.png', audio: api + '/audio', description,
  }
  const page = new DOMParser().parseFromString(template.replace(/\{\{(\w+)}}/g, (_, key) => fields[key]), 'text/html')
  document.head.innerHTML = page.head.innerHTML
  document.body.innerHTML = page.body.innerHTML
  const location = new URL(url + '?from=timeline&isappinstalled=0#player')
  const config = { appId: 'fixture', timestamp: 9, nonceStr: 'nonce', signature: 'signature' }
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => config })
  const navigator = {
    userAgent: inWechat ? 'MicroMessenger/test' : agent,
    clipboard: { writeText: vi.fn().mockResolvedValue(undefined) }, share: vi.fn().mockResolvedValue(undefined),
  }
  const wx = { ready: vi.fn(), error: vi.fn(), config: vi.fn(), updateAppMessageShareData: vi.fn(), updateTimelineShareData: vi.fn() }
  const assign = vi.fn()
  const globals = { window: {}, document, navigator, location: Object.assign(location, { assign }), URL, fetch, wx, console: { warn: vi.fn() } }
  runInNewContext(handoff, globals)
  runInNewContext(script, globals)
  return { fields, api, navigator, location, assign, config, fetch, wx }
}

const button = (id: string) => document.getElementById(id) as HTMLButtonElement

describe('public song listener page', () => {
  function clickDestination(id: string) {
    document.getElementById('share-sheet')!.setAttribute('open', '')
    const target = button(id)
    target.onclick!.call(target, { isTrusted: true } as PointerEvent)
  }

  it('copies the song and opens desktop WeChat on a Windows friend click, never on a preview or for Moments', async () => {
    const { fields, navigator, assign } = openSong('song-w', false, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)')
    button('friend').click()
    clickDestination('timeline')
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
    expect(assign).not.toHaveBeenCalled()
    clickDestination('friend')
    expect(navigator.clipboard.writeText).toHaveBeenCalledExactlyOnceWith(fields.url)
    await vi.waitFor(() => expect(assign).toHaveBeenCalledExactlyOnceWith('weixin://'))
    expect(document.getElementById('open-desktop-wechat')?.hidden).toBe(false)
    expect(document.getElementById('handoff-status')?.textContent).toContain('粘贴并发送')
  })

  it('keeps a manual copy and launch available if the desktop browser denies clipboard access', async () => {
    const { fields, navigator, assign } = openSong('song-w2', false, 'Windows NT 10.0')
    navigator.clipboard.writeText.mockRejectedValueOnce(new Error('denied'))
    clickDestination('friend')
    const input = document.getElementById('handoff-link') as HTMLInputElement
    await vi.waitFor(() => expect(input.hidden).toBe(false))
    expect(input.value).toBe(fields.url)
    expect(input.selectionEnd).toBe(fields.url.length)
    expect(document.getElementById('open-desktop-wechat')?.getAttribute('href')).toBe('weixin://')
    expect(assign).not.toHaveBeenCalled()
  })

  it('does not launch after a delayed clipboard permission when the listener has switched to Moments', async () => {
    const { navigator, assign } = openSong('song-w3', false, 'Windows NT 10.0')
    let permit!: () => void
    navigator.clipboard.writeText.mockImplementationOnce(() => new Promise<void>(resolve => { permit = resolve }))
    clickDestination('friend')
    clickDestination('timeline')
    permit()
    await Promise.resolve()
    expect(assign).not.toHaveBeenCalled()
    expect(document.getElementById('open-desktop-wechat')?.hidden).toBe(true)
  })

  it('hands the canonical URL to the mobile system picker, retaining QR access after cancellation', async () => {
    const { fields, navigator, assign } = openSong('song-m', false, 'Mozilla/5.0 (Linux; Android 14) Mobile')
    navigator.share.mockRejectedValueOnce(new DOMException('dismissed', 'AbortError'))
    clickDestination('friend')
    expect(navigator.share).toHaveBeenCalledExactlyOnceWith({ title: fields.title + ' · ' + fields.creator, url: fields.url, text: description })
    clickDestination('timeline')
    expect(navigator.share).toHaveBeenCalledTimes(2)
    expect(assign).not.toHaveBeenCalled()
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
    expect(document.getElementById('wechat-guide')?.hidden).toBe(false)
  })

  it('records a started listen once across preloading, seeking and resuming, then counts a replay after the end', () => {
    const { fetch, api } = openSong('song-a')
    const audio = document.querySelector('audio')!
    const fire = (name: string) => audio.dispatchEvent(new Event(name))
    for (const name of ['loadedmetadata', 'canplay', 'progress', 'play']) fire(name)
    expect(fetch).not.toHaveBeenCalled()
    for (const name of ['playing', 'waiting', 'playing', 'pause', 'play', 'playing', 'seeking', 'seeked', 'playing']) fire(name)
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch.mock.calls[0][0].href).toBe(api + '/play')
    expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST', mode: 'same-origin', credentials: 'omit', keepalive: true })
    fire('ended')
    fire('playing')
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('signs the current URL and supplies separate friend and timeline cards for each song', async () => {
    for (const id of ['song-a', 'song-b']) {
      const { fields, location, wx, fetch, config } = openSong(id, true)
      const sdk = document.querySelector<HTMLScriptElement>('script[src*="jweixin"]')!
      await sdk.onload?.call(sdk, new Event('load'))
      expect(fetch).toHaveBeenCalledWith('/api/wechat/jssdk-config?url=' + encodeURIComponent(location.href.split('#')[0]))
      expect(wx.config).toHaveBeenCalledWith({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
      wx.ready.mock.calls[0][0]()
      const common = { title: fields.title + ' · ' + fields.creator, link: fields.url, imgUrl: fields.thumbnail }
      const friend = wx.updateAppMessageShareData.mock.calls[0][0]
      const timeline = wx.updateTimelineShareData.mock.calls[0][0]
      expect(friend).toMatchObject({ ...common, desc: description })
      expect(timeline).toMatchObject(common)
      expect(timeline).not.toHaveProperty('desc')
      button('friend').click()
      expect(document.getElementById('channel-instruction')?.textContent).toContain('发送给朋友')
      button('timeline').click()
      expect(document.getElementById('channel-instruction')?.textContent).toContain('分享到朋友圈')
      expect(button('timeline').getAttribute('aria-pressed')).toBe('true')
      expect(button('friend').getAttribute('aria-pressed')).toBe('false')
      expect((document.getElementById('more-share') as HTMLDetailsElement).open).toBe(false)
      wx.error.mock.calls[0][0]({ errMsg: 'config:invalid signature' })
      timeline.fail({ errMsg: 'updateTimelineShareData:permission denied' })
      expect(fetch.mock.calls.slice(1).map(([url]) => url)).toEqual([
        '/api/wechat/jssdk-error?reason=INVALID_SIGNATURE', '/api/wechat/jssdk-error?reason=PERMISSION_DENIED',
      ])
    }
  })

  it('offers system sharing and copying outside WeChat, with a manual fallback', async () => {
    const { fields, navigator } = openSong('song-c')
    expect(document.querySelector('script[src*="jweixin"]')).toBeNull()
    expect(document.getElementById('wechat-actions')?.hidden).toBe(false)
    expect(button('copy').closest('details')?.open).toBe(false)
    button('timeline').click()
    expect(document.getElementById('channel-instruction')?.textContent).toContain('微信扫一扫')
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
    expect(navigator.share).not.toHaveBeenCalled()
    button('copy').closest('details')!.open = true
    button('system-share').click()
    expect(navigator.share).toHaveBeenCalledWith({ title: fields.title + ' · ' + fields.creator, url: fields.url, text: description })
    button('copy').click()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(fields.url)
    navigator.clipboard.writeText.mockRejectedValueOnce(new Error('clipboard unavailable'))
    button('copy').click()
    await vi.waitFor(() => expect(document.getElementById('manual')?.hidden).toBe(false))
    expect((document.getElementById('manual') as HTMLInputElement).value).toBe(fields.url)
  })
})
