// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { describe, expect, it, vi } from 'vitest'

const script = readFileSync('public/brand/song-share.js', 'utf8')
const handoff = readFileSync('public/brand/wechat-handoff.js', 'utf8')
const template = readFileSync('../Qingfeng_AI_Music/qf-server/src/main/resources/song.html', 'utf8')
const description = '我在 Cheerful Music 创作了一首歌曲，邀请你来听听'

function openSong(id: string, inWechat = false, agent = 'WebTest', canShare = true) {
  const site = 'https://cheerfulmusic.com'
  const url = site + '/song/' + id
  const api = site + '/api/songs/' + id
  const fields: Record<string, string> = {
    title: '歌曲 ' + id, creator: '青风', url, image: api + '/cover',
    siteName: '青风音乐 Cheerful Music', timelineTitle: '青风音乐 Cheerful Music｜歌曲 ' + id + ' · 青风',
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
    canShare: vi.fn().mockReturnValue(canShare),
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

  it('hands the selected song to the Windows picker on a real click and keeps the draft after cancellation', async () => {
    const { fields, navigator, assign } = openSong('song-w', false, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)')
    const title = fields.title + ' · ' + fields.creator
    const draft = title + '\n' + fields.url
    button('friend').click()
    expect(navigator.share).not.toHaveBeenCalled()
    navigator.share.mockRejectedValueOnce(new DOMException('dismissed', 'AbortError'))
    clickDestination('friend')
    expect(navigator.share).toHaveBeenCalledExactlyOnceWith({ title, url: fields.url, text: description })
    await Promise.resolve()
    expect(document.getElementById('desktop-draft')?.hidden).toBe(false)
    expect((document.getElementById('handoff-link') as HTMLTextAreaElement).value).toBe(draft)
    expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
    expect(document.getElementById('open-desktop-wechat')?.hidden).toBe(true)
    button('copy-draft').click()
    expect(navigator.clipboard.writeText).toHaveBeenCalledExactlyOnceWith(draft)
    await vi.waitFor(() => expect(document.getElementById('open-desktop-wechat')?.hidden).toBe(false))
    clickDestination('timeline')
    expect(navigator.share).toHaveBeenLastCalledWith({ title: fields.timelineTitle, url: fields.url, text: description })
    expect(document.getElementById('desktop-draft')?.hidden).toBe(true)
    expect(assign).not.toHaveBeenCalled()
  })

  it('keeps a manual copy and launch available if the desktop browser denies clipboard access', async () => {
    const { fields, navigator, assign } = openSong('song-w2', false, 'Windows NT 10.0', false)
    navigator.clipboard.writeText.mockRejectedValueOnce(new Error('denied'))
    clickDestination('friend')
    const input = document.getElementById('handoff-link') as HTMLTextAreaElement
    await vi.waitFor(() => expect(document.activeElement).toBe(input))
    expect(input.value).toBe(fields.title + ' · ' + fields.creator + '\n' + fields.url)
    expect(input.selectionEnd).toBe(input.value.length)
    expect(document.getElementById('handoff-status')?.textContent).toContain('Ctrl+C')
    expect(navigator.share).not.toHaveBeenCalled()
    expect(document.getElementById('open-desktop-wechat')?.getAttribute('href')).toBe('weixin://')
    expect(assign).not.toHaveBeenCalled()
  })

  it('does not launch after a delayed clipboard permission when the listener has switched to Moments', async () => {
    const { navigator, assign } = openSong('song-w3', false, 'Windows NT 10.0', false)
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
    expect(navigator.share).toHaveBeenLastCalledWith({ title: fields.timelineTitle, url: fields.url, text: description })
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
      const { fields, location, wx, fetch, config, navigator, assign } = openSong(id, true)
      const sdk = document.querySelector<HTMLScriptElement>('script[src*="jweixin"]')!
      await sdk.onload?.call(sdk, new Event('load'))
      expect(fetch).toHaveBeenCalledWith('/api/wechat/jssdk-config?url=' + encodeURIComponent(location.href.split('#')[0]))
      expect(wx.config).toHaveBeenCalledWith({ ...config, debug: false, jsApiList: ['updateAppMessageShareData', 'updateTimelineShareData'] })
      wx.ready.mock.calls[0][0]()
      expect(document.title).toBe(fields.timelineTitle)
      expect(document.querySelector<HTMLMetaElement>('meta[property="og:site_name"]')?.content).toBe(fields.siteName)
      expect(document.querySelector<HTMLMetaElement>('meta[property="og:title"]')?.content).toBe(fields.timelineTitle)
      expect(document.querySelector('h1')?.textContent).toBe(fields.title)
      expect(document.querySelector('.wordmark img')?.getAttribute('src')).toBe('/brand/logo-symbol.svg')
      const common = { link: fields.url, imgUrl: fields.thumbnail }
      const friend = wx.updateAppMessageShareData.mock.calls[0][0]
      const timeline = wx.updateTimelineShareData.mock.calls[0][0]
      expect(friend).toMatchObject({ ...common, title: fields.title + ' · ' + fields.creator, desc: description })
      expect(timeline).toMatchObject({ ...common, title: fields.timelineTitle })
      expect(timeline).not.toHaveProperty('desc')
      expect(button('friend').textContent).toContain('右上角菜单发送')
      clickDestination('friend')
      expect(document.getElementById('channel-instruction')?.textContent).toContain('发送给朋友')
      expect(document.getElementById('timeline-menu-hint')?.hidden).toBe(false)
      clickDestination('timeline')
      expect(document.getElementById('channel-instruction')?.textContent).toContain('分享到朋友圈')
      expect(navigator.share).not.toHaveBeenCalled()
      expect(navigator.clipboard.writeText).not.toHaveBeenCalled()
      expect(assign).not.toHaveBeenCalled()
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
    expect(navigator.share).toHaveBeenCalledWith({ title: fields.timelineTitle, url: fields.url, text: description })
    button('copy').click()
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith(fields.url)
    navigator.clipboard.writeText.mockRejectedValueOnce(new Error('clipboard unavailable'))
    button('copy').click()
    await vi.waitFor(() => expect(document.getElementById('manual')?.hidden).toBe(false))
    expect((document.getElementById('manual') as HTMLInputElement).value).toBe(fields.url)
  })
})
