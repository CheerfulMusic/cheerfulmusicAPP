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
  const browserLocation = Object.assign(location, { assign })
  const globals = { window: { location: browserLocation }, document, navigator, location: browserLocation, URL, fetch, wx, console: { warn: vi.fn() } }
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

  it.each([
    'Windows NT 10.0',
    'Mozilla/5.0 (Macintosh; Intel Mac OS X)',
    'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)',
    'Mozilla/5.0 (Linux; Android 14) Mobile',
  ])('opens WeChat immediately for either destination even with a system picker available on %s', async agent => {
    const { fields, navigator, assign } = openSong('song-direct', false, agent)
    for (const target of ['friend', 'timeline']) {
      assign.mockClear()
      navigator.clipboard.writeText.mockClear()
      let permit!: () => void
      navigator.clipboard.writeText.mockImplementationOnce(() => new Promise<void>(resolve => { permit = resolve }))
      button(target).click()
      expect(assign).not.toHaveBeenCalled()
      expect(navigator.clipboard.writeText).not.toHaveBeenCalled()

      clickDestination(target)
      const title = target === 'friend' ? fields.title + ' · ' + fields.creator : fields.timelineTitle
      expect(assign).toHaveBeenCalledExactlyOnceWith('weixin://')
      expect(navigator.clipboard.writeText).toHaveBeenCalledExactlyOnceWith(title + '\n' + fields.url)
      expect(navigator.share).not.toHaveBeenCalled()
      permit()
      await Promise.resolve()
      expect(assign).toHaveBeenCalledTimes(1)
    }
  })

  it.each(['friend', 'timeline'])('keeps a selectable %s draft and a retry if clipboard access is denied', async target => {
    const { fields, navigator, assign } = openSong('song-w2', false, 'Windows NT 10.0', false)
    navigator.clipboard.writeText.mockRejectedValueOnce(new Error('denied'))
    clickDestination(target)
    expect(assign).toHaveBeenCalledExactlyOnceWith('weixin://')
    const input = document.querySelector<HTMLTextAreaElement>('.destination-guide textarea')!
    await vi.waitFor(() => expect(document.activeElement).toBe(input))
    const title = target === 'friend' ? fields.title + ' · ' + fields.creator : fields.timelineTitle
    expect(input.value).toBe(title + '\n' + fields.url)
    expect(input.selectionEnd - input.selectionStart).toBe(input.value.length)
    expect(input.closest('[hidden]')).toBeNull()
    expect(document.querySelector<HTMLAnchorElement>('.destination-guide a[href="weixin://"]')?.closest('[hidden]')).toBeNull()
    expect(navigator.share).not.toHaveBeenCalled()
  })

  it('keeps the draft when the browser blocks opening the WeChat app', async () => {
    const { fields, navigator, assign } = openSong('song-blocked')
    assign.mockImplementationOnce(() => { throw new DOMException('blocked', 'SecurityError') })
    clickDestination('timeline')
    const draft = fields.timelineTitle + '\n' + fields.url
    expect(navigator.clipboard.writeText).toHaveBeenCalledExactlyOnceWith(draft)
    await vi.waitFor(() => expect(document.getElementById('handoff-status')?.textContent).toContain('浏览器未能打开微信'))
    expect(document.querySelector<HTMLTextAreaElement>('.destination-guide textarea')?.value).toBe(draft)
    expect(navigator.share).not.toHaveBeenCalled()
  })

  it('ignores stale clipboard feedback when the listener changes destinations', async () => {
    const { fields, navigator, assign } = openSong('song-race', false, 'Windows NT 10.0')
    let deny!: (reason: Error) => void
    navigator.clipboard.writeText.mockImplementationOnce(() => new Promise<void>((_, reject) => { deny = reject }))
    clickDestination('friend')
    clickDestination('timeline')
    const input = document.querySelector<HTMLTextAreaElement>('.destination-guide textarea')!
    expect(input.value).toBe(fields.timelineTitle + '\n' + fields.url)
    deny(new Error('old request'))
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(document.activeElement).not.toBe(input)
    expect(button('timeline').getAttribute('aria-pressed')).toBe('true')
    expect(assign).toHaveBeenCalledTimes(2)
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
