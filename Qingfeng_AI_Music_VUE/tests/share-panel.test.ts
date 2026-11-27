// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import SharePanel from '../src/modules/works/components/SharePanel.vue'
import { setLocale } from '../src/shared/i18n'
import type { PlayableTrack } from '../src/shared/player/store'
import type { Revision, AudioAsset } from '../src/modules/works/api/detail'

const mocks = vi.hoisted(() => ({ create: vi.fn(), get: vi.fn(), revoke: vi.fn(), revokeVersion: vi.fn(), internationalPoster: vi.fn(), qr: vi.fn() }))
vi.mock('../src/modules/works/api/detail', () => ({ createShare: mocks.create, getSongShareStatus: mocks.get, revokeShares: mocks.revoke, revokeSongShare: mocks.revokeVersion }))
vi.mock('../src/shared/http/api-client', () => ({ apiUrl: (s: string) => '/api/' + s }))
const player = reactive({
  current: null as PlayableTrack | null,
  playing: false,
  starting: false,
  toggle: vi.fn(),
  reset: vi.fn(),
})
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => player }))
vi.mock('../src/modules/works/model/song-poster', () => ({ renderInternationalSongPoster: mocks.internationalPoster, wechatShareQr: mocks.qr }))
let app: App | undefined
const flush = async () => { for (let i = 0; i < 5; i++) { await nextTick(); await new Promise(r => setTimeout(r, 0)) } }
const token = 'A'.repeat(43)
const shortCode = token.slice(0, 22)
const privateState = { status: 'PRIVATE', card: null, views: 0, plays: 0 }
function active(songId = token, title = '我的歌', shortCode?: string | null) {
  return { status: 'SHAREABLE', views: 5, plays: 2, card: {
    songId, shortCode, title, creator: '公开艺名', lyrics: '[Verse]\n窗台那盆薄荷\nKeep the light on', hasCover: true,
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
  } }
}
function mount(origin: AudioAsset['origin'] = 'GENERATED') {
  const revision = origin === 'GENERATED' ? { id: 'revision-a', confirmed: true } as Revision : null
  const props = reactive({ open: true, workId: 'work-a', revision, asset: { id: 'audio-a', origin } as AudioAsset })
  app = createApp({ setup: () => () => h(SharePanel, props) }); app.mount(document.body.appendChild(document.createElement('div')))
  return props
}
function button(text: string, root: ParentNode = document) {
  return [...root.querySelectorAll('button')].find(b => {
    const copy = b.cloneNode(true) as HTMLButtonElement
    copy.querySelectorAll('[aria-hidden=true], small').forEach(hint => hint.remove())
    return copy.textContent?.trim() === text
  })!
}
function channel(target: string) {
  return document.querySelector<HTMLAnchorElement>('.international-share-channels a[href$="#share-' + target + '"]')!
}
const shareLink = () => document.querySelector<HTMLInputElement>('.international-share-link input#song-share-link')
const shareTitle = () => document.querySelector('.international-song-details h3')?.textContent
const shareActions = () => document.querySelector('.international-share-actions')!
function openPosterCustomization() {
  const trigger = button('自定义海报')
  trigger.closest('details')!.open = true
  trigger.click()
}
beforeEach(() => {
  vi.clearAllMocks()
  player.current = null; player.playing = false; player.starting = false
  setLocale('zh-CN')
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false } })
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:poster') })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  mocks.create.mockResolvedValue(undefined)
  mocks.get.mockResolvedValue(privateState)
  mocks.internationalPoster.mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
  mocks.qr.mockResolvedValue('data:image/png;base64,fixture')
})
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks() })
describe('share panel state and poster lifecycle', () => {
  it('requires the creator to enable each version independently', async () => {
    const props = mount(); await flush()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('未启用歌曲分享页')
    mocks.get.mockResolvedValueOnce(active())
    button('启用公开试听').click(); await flush()
    expect(mocks.create).toHaveBeenCalledExactlyOnceWith('work-a', 'audio-a', undefined, undefined)
    props.asset = { ...props.asset, id: 'audio-b' }; props.revision = null
    await flush()
    expect(mocks.get).toHaveBeenLastCalledWith('work-a', 'audio-b')
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(shareLink()).toBeNull()
    const secondId = 'B'.repeat(43)
    mocks.get.mockResolvedValueOnce(active(secondId))
    button('启用公开试听').click(); await flush()
    expect(mocks.create).toHaveBeenLastCalledWith('work-a', 'audio-b', undefined, undefined)
    await vi.waitFor(() => expect(shareLink()?.value).toBe(new URL('/song/' + secondId, location.origin).href))
  })
  it('enables imported audio only after lyric confirmation and explicit opt-in', async () => {
    const props = mount('IMPORTED')
    await flush()
    expect(button('启用公开试听')).toBeUndefined()
    props.revision = { id: 'revision-import', confirmed: true } as Revision
    await flush()
    expect(mocks.create).not.toHaveBeenCalled()
    button('启用公开试听').click(); await flush()
    expect(mocks.create).toHaveBeenCalledExactlyOnceWith('work-a', 'audio-a', 'revision-import', undefined)
  })
  it('offers the WeChat QR and clears it when switching to YouTube', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    mocks.get.mockResolvedValue(active(token, '我的歌', shortCode))
    mount(); await flush()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('5 次访问')
    expect(shareTitle()).toBe('我的歌')
    expect(channel('friend').textContent?.trim()).toBe('微信')
    expect(channel('timeline')).toBeNull()
    const copy = button('复制本曲链接', shareActions())
    const url = new URL('/s/' + shortCode, location.origin).href
    expect(shareLink()?.value).toBe(url)
    let finish!: (data: string) => void
    mocks.qr.mockImplementationOnce(() => new Promise<string>(resolve => { finish = resolve }))
    channel('friend').click(); await flush()
    expect(mocks.qr).toHaveBeenCalledWith(url, 'friend')
    button('油管').click(); await flush()
    expect(mocks.qr).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.youtube-share-guide')?.textContent).toContain('打开 YouTube Studio')
    finish('data:image/png;base64,stale'); await flush()
    expect(document.querySelector('.destination-guide img')).toBeNull()
    expect(writeText).not.toHaveBeenCalled()
    copy.click(); await flush()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(url)
    writeText.mockRejectedValueOnce(new Error('denied'))
    copy.click(); await flush()
    const input = shareLink()!
    expect(input.selectionEnd! - input.selectionStart!).toBe(input.value.length)
    expect(document.body.textContent).toContain('请选中链接手动复制。')
    expect(document.querySelector('.international-song-cover img')?.getAttribute('src')).toContain('/songs/' + token + '/cover')
  })
  it('starts desktop WeChat friend sharing without copying automatically', async () => {
    const share = vi.fn()
    const assign = vi.fn()
    let deny!: (error: Error) => void
    const writeText = vi.fn(() => new Promise<void>((_, reject) => { deny = reject }))
    vi.stubGlobal('navigator', { userAgent: 'Windows NT 10.0', share, canShare: () => true, clipboard: { writeText } })
    const clientWindow = { location: { assign }, cheerfulWechat: undefined }
    runInNewContext(readFileSync('public/brand/wechat-handoff.js', 'utf8'), {
      navigator, window: clientWindow, location: clientWindow.location, URL,
    })
    vi.stubGlobal('cheerfulWechat', clientWindow.cheerfulWechat)
    const listeners = vi.spyOn(HTMLAnchorElement.prototype, 'addEventListener')
    mocks.get.mockResolvedValue(active())
    mount(); await flush()
    const anchor = channel('friend')
    expect(anchor.textContent?.trim()).toBe('微信')
    const url = new URL('/song/' + token + '#share-friend', location.origin).href
    anchor.click()
    expect(assign).not.toHaveBeenCalled()
    expect(writeText).not.toHaveBeenCalled()

    // jsdom dispatches synthetic clicks. Invoke the registered click handler with the browser's trusted-click flag.
    const index = listeners.mock.calls.findIndex(([type], index) => type === 'click' && listeners.mock.contexts[index] === anchor)
    const listener = listeners.mock.calls[index]?.[1] as EventListener
    expect(listener).toBeTypeOf('function')
    listener.call(anchor, { isTrusted: true, preventDefault: vi.fn(), timeStamp: Date.now() } as unknown as MouseEvent)
    expect(assign).toHaveBeenCalledExactlyOnceWith('weixin://')
    expect(share).not.toHaveBeenCalled()
    expect(writeText).not.toHaveBeenCalled()
    await nextTick()
    expect(document.querySelector('#creator-share-draft')).toBeNull()
    button('复制到电脑微信打开').click()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(url)
    deny(new Error('permission denied'))
    await flush()
    const input = document.querySelector<HTMLTextAreaElement>('#creator-share-draft')!
    expect(document.activeElement).toBe(input)
    expect(input.value).toBe(url)
    expect(input.selectionEnd - input.selectionStart).toBe(input.value.length)
    expect(document.querySelector('.destination-guide a[href="weixin://"]')).not.toBeNull()
    expect(document.querySelector('.destination-guide')?.textContent).toContain('文件传输助手')
    expect(assign).toHaveBeenCalledTimes(1)
  })
  it('keeps the QR guide usable if the desktop WeChat handoff rejects', async () => {
    const open = vi.fn().mockRejectedValue(new Error('bridge unavailable'))
    const helper: NonNullable<Window['cheerfulWechat']> = {
      mode: () => 'client',
      open,
      draft: data => data.url,
      copyDraft: vi.fn(),
      clientUrl: 'weixin://',
      isWindows: true,
      windowsInstruction: 'Open the song from WeChat.',
    }
    vi.stubGlobal('cheerfulWechat', helper)
    const listeners = vi.spyOn(HTMLAnchorElement.prototype, 'addEventListener')
    mocks.get.mockResolvedValue(active())
    mount(); await flush()

    const anchor = channel('friend')
    const index = listeners.mock.calls.findIndex(([type], index) => type === 'click' && listeners.mock.contexts[index] === anchor)
    const listener = listeners.mock.calls[index]?.[1] as EventListener
    listener.call(anchor, { isTrusted: true, preventDefault: vi.fn() } as unknown as MouseEvent)
    await flush()

    expect(open).toHaveBeenCalledOnce()
    expect(document.querySelector('.destination-guide')?.textContent).toContain('浏览器未能打开微信。请手动打开电脑微信。')
    expect(document.querySelector('.destination-guide img')?.getAttribute('src')).toBe('data:image/png;base64,fixture')
  })

  it.each(['MicroMessenger', 'WindowsWechat/test'])('navigates in %s to the signed public song for WeChat friends', async userAgent => {
    const writeText = vi.fn()
    vi.stubGlobal('navigator', { userAgent, clipboard: { writeText } })
    mocks.get.mockResolvedValue(active(token, '我的歌', shortCode))
    mount(); await flush()
    const anchor = channel('friend')
    expect(anchor.pathname).toBe('/s/' + shortCode)
    let navigates = false
    anchor.addEventListener('click', event => { navigates = !event.defaultPrevented; event.preventDefault() }, { once: true })
    anchor.click(); await flush()
    expect(navigates).toBe(true)
    expect(mocks.qr).not.toHaveBeenCalled()
    expect(writeText).not.toHaveBeenCalled()
  })
  it.each(['zh-CN', 'en'] as const)('offers real link copy and YouTube Studio in %s', async language => {
    setLocale(language)
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('denied'))
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    mocks.get.mockResolvedValue(active(token, '我的歌', shortCode))
    mount(); await flush()
    button(language === 'en' ? 'YouTube' : '油管').click(); await flush()
    const guide = document.querySelector('.youtube-share-guide')!
    const studio = guide.querySelector<HTMLAnchorElement>('a')!
    expect(studio.href).toBe('https://studio.youtube.com/')
    expect(studio.target).toBe('_blank')
    const url = new URL('/s/' + shortCode + (language === 'en' ? '?lang=en' : ''), location.origin).href
    const copy = guide.querySelector<HTMLButtonElement>('button')!
    copy.click(); await flush()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(url)
    expect(guide.querySelector('p[role="status"]')?.textContent).toBe(language === 'en' ? 'Copy successful' : '复制成功')
    copy.click(); await flush()
    const input = guide.querySelector<HTMLInputElement>('input')!
    expect(input.value).toBe(url)
    expect(input.selectionEnd! - input.selectionStart!).toBe(url.length)
  })
  it('uses the website name in system sharing, including for older song cards', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { share })
    const sample = active(token, '米仓山')
    const named = '青风音乐 Cheerful Music｜米仓山 · SNOW'
    mocks.get.mockResolvedValueOnce({ ...sample, card: { ...sample.card, timelineTitle: named } })
    const props = mount(); await flush()
    button('更多选项', shareActions()).click()
    expect(share).toHaveBeenLastCalledWith(expect.objectContaining({ title: named }))
    mocks.get.mockResolvedValueOnce(active('B'.repeat(43), '新曲'))
    props.asset = { ...props.asset, id: 'other' }; await flush()
    button('更多选项', shareActions()).click()
    expect(share).toHaveBeenLastCalledWith(expect.objectContaining({ title: '青风音乐 Cheerful Music｜新曲' }))
  })
  it('retries only the status read after a confirmed change', async () => {
    mount(); await flush()
    mocks.get.mockRejectedValueOnce(new Error('暂时无法读取'))
    button('启用公开试听').click(); await flush()
    mocks.get.mockResolvedValueOnce(active())
    button('重新读取分享状态').click(); await flush()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(shareTitle()).toBe('我的歌')
  })
  it('discards an old work response after switching songs', async () => {
    let finish!: () => void
    mocks.create.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const props = mount(); await flush()
    button('启用公开试听').click(); await flush()
    mocks.get.mockResolvedValueOnce(active('B'.repeat(43),'第二首歌'))
    props.workId = 'work-b'
    await flush()
    finish(); await flush()
    expect(shareTitle()).toBe('第二首歌')
    expect(shareLink()?.value).toContain('B'.repeat(43))
    expect(mocks.create).toHaveBeenCalledTimes(1)
  })
  it('waits for a pending write before reading an immediately reopened version', async () => {
    let finish!: () => void
    mocks.create.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const props = mount(); await flush()
    button('启用公开试听').click()
    props.open = false; await flush()
    props.open = true; await flush()
    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(button('启用公开试听')).toBeUndefined()

    mocks.get.mockResolvedValueOnce(active())
    finish(); await flush()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(shareLink()?.value).toContain('/song/' + token)
  })

  it('releases the sharing lock after a new card replaces a pending system handoff', async () => {
    let finish!: () => void
    const share = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    vi.stubGlobal('navigator', { share })
    mocks.get.mockResolvedValueOnce(active()).mockResolvedValueOnce(active('B'.repeat(43)))
    mount(); await flush()
    button('更多选项', shareActions()).click(); await nextTick()
    expect(button('更多选项', shareActions()).disabled).toBe(true)

    button('更新分享卡片').click(); await flush()
    expect(shareLink()?.value).toContain('/song/' + 'B'.repeat(43))
    expect(button('更多选项', shareActions()).disabled).toBe(false)
    finish(); await flush()
  })

  it('invalidates an old poster after editing the selected lyric and never publishes stale render results', async () => {
    let finish!: (blob: Blob) => void
    mocks.internationalPoster.mockImplementationOnce(() => new Promise(r => { finish = r }))
    mocks.get.mockResolvedValue(active())
    mount(); await flush(); openPosterCustomization(); await flush()
    expect(mocks.internationalPoster.mock.calls[0]![0]).toMatchObject({
      artist: '公开艺名', signature: '', quote: '',
      link: new URL('/song/' + token, location.origin).href,
    })
    const section = document.querySelector('.international-poster-workspace')!
    const select = section.querySelector<HTMLSelectElement>('select')!
    select.value = 'Keep the light on'; select.dispatchEvent(new Event('change')); await flush()
    const signature = section.querySelector<HTMLInputElement>('input[maxlength="24"]')!
    signature.value = '  青风  '; signature.dispatchEvent(new Event('input')); await flush()
    finish(new Blob(['stale'], { type: 'image/png' })); await flush()
    expect(section.querySelector('img')).toBeNull()
    button('生成海报预览').click(); await flush()
    expect(mocks.internationalPoster.mock.calls[1]![0]).toMatchObject({ quote: 'Keep the light on', signature: '青风' })
    expect(section.querySelector('img')).not.toBeNull()
    app?.unmount(); app = undefined; expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:poster')
  })
  it('revocation clears the card, link and generated poster', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true); mocks.revoke.mockResolvedValue(undefined)
    mocks.get.mockResolvedValue(active())
    mount(); await flush(); openPosterCustomization(); await flush()
    mocks.get.mockResolvedValue({ ...privateState, status: 'REVOKED' })
    button('撤销本作品全部分享').click(); await flush()
    expect(mocks.revoke).toHaveBeenCalledWith('work-a')
    expect(document.querySelector('.share-song-card')).toBeNull(); expect(shareLink()).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:poster')
  })
  it('revokes a single version and keeps it revoked when the panel is reopened', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    mocks.get.mockResolvedValueOnce(active()).mockResolvedValue({ ...privateState, status: 'REVOKED' })
    const props = mount(); await flush()
    button('撤销此版本分享').click(); await flush()
    expect(mocks.revokeVersion).toHaveBeenCalledExactlyOnceWith('work-a','audio-a')
    expect(mocks.revoke).not.toHaveBeenCalled()
    props.open = false; await flush()
    props.open = true; await flush()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('已撤销公开试听')
    expect(shareLink()).toBeNull()
  })
})

describe.each(['zh-CN', 'en'] as const)('shared share panel in %s', language => {
  beforeEach(() => setLocale(language))

  it('shows the selected public card and real API counts with the channels in order', async () => {
    const english = language === 'en'
    mocks.get.mockResolvedValue(active(token, 'Live & Loud', shortCode))
    mount(); await flush()
    const panel = document.querySelector('.international-share-dialog')!
    expect(panel.querySelector('.save-info')?.textContent?.replace(/\s+/g, ' ')).toContain(
      english ? 'Public preview enabled · 5 visits · 2 plays' : '5 次访问 · 2 次播放',
    )
    expect(panel.querySelector('.international-song-details h3')?.textContent).toBe('Live & Loud')
    expect(panel.querySelector('.international-song-artist')?.textContent).toBe('公开艺名')
    expect(panel.querySelector('.international-song-cover img')?.getAttribute('src')).toBe('/api/songs/' + token + '/cover')
    expect(panel.querySelectorAll('img[src="/brand/logo-symbol.svg"]')).toHaveLength(1)
    expect(panel.querySelector('header img[src="/brand/logo-symbol.svg"]')).not.toBeNull()
    const channels = [...panel.querySelectorAll<HTMLAnchorElement>('.international-share-channels > *')]
    expect(channels.map(item => item.textContent?.trim())).toEqual(english
      ? ['WeChat', 'YouTube', 'WhatsApp', 'Instagram', 'Facebook', 'Telegram', 'X', 'Email']
      : ['微信', '油管', 'WhatsApp', '照片墙', '脸书', '电报', 'X', '邮件'])
    const baseUrl = new URL('/s/' + shortCode, location.origin).href
    const expectedUrl = english ? baseUrl + '?lang=en' : baseUrl
    expect(channels[0]?.href).toBe(baseUrl + '#share-friend')
    expect(channels[1]?.tagName).toBe('BUTTON')
    expect(channels[1]?.querySelector('img')?.getAttribute('src')).toBe('/brand/share-channels/youtube.png')
    expect(new URL(channels[2]!.href).searchParams.get('text')).toContain(expectedUrl)
    expect(new URL(channels[4]!.href).searchParams.get('u')).toBe(expectedUrl)
    expect(new URL(channels[5]!.href).searchParams.get('url')).toBe(expectedUrl)
    expect(new URL(channels[6]!.href).searchParams.get('url')).toBe(expectedUrl + (english ? '&card=x' : '?card=x'))
    expect(new URL(channels[7]!.href).searchParams.get('body')).toContain(expectedUrl)
    const xPreview = panel.querySelector<HTMLAnchorElement>('.international-brand-menu a[href*="/x-player"]')!
    expect(xPreview.textContent?.trim()).toBe(english ? 'Preview X player' : '预览 X 播放器')
    expect(xPreview.href).toBe(new URL('/song/' + token + '/x-player' + (english ? '?lang=en' : ''), location.origin).href)
    expect(shareLink()?.value).toBe(expectedUrl)
    expect(button(english ? 'Copy Link' : '复制本曲链接', shareActions())).toBeDefined()
    expect(button(english ? 'More Options' : '更多选项', shareActions())).toBeDefined()

    const preview = panel.querySelector<HTMLButtonElement>('.international-preview')!
    expect(preview.textContent).toContain(english ? 'Preview song' : '播放试听')
    preview.click()
    expect(player.toggle).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ audio: '/api/songs/' + token + '/audio' }))
    player.current = player.toggle.mock.calls[0]![0]
    player.starting = true
    await nextTick()
    expect(preview.textContent).toContain(english ? 'Loading preview…' : '正在加载试听…')
    player.starting = false
    player.playing = true
    await nextTick()
    expect(preview.textContent).toContain(english ? 'Pause preview' : '暂停试听')
    preview.click()
    expect(player.toggle).toHaveBeenCalledTimes(2)
  })

  it.each(['PRIVATE', 'REVOKED', 'EXPIRED'])('hides preview and sharing controls for %s status', async status => {
    mocks.get.mockResolvedValue({ ...privateState, status })
    mount(); await flush()
    expect(document.querySelector('.international-preview')).toBeNull()
    expect(document.querySelector('.international-share-channels')).toBeNull()
    expect(document.querySelector('.international-share-link')).toBeNull()
    expect(document.querySelector('.international-public-note')).toBeNull()
    expect(document.querySelector('.international-brand-menu a[href*="/x-player"]')).toBeNull()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(button(language === 'en' ? 'Enable public listening' : '启用公开试听')).toBeDefined()
  })

  it('removes all share actions and clears its current preview when the open grant expires', async () => {
    vi.useFakeTimers()
    try {
      const song = active()
      song.card.expiresAt = new Date(Date.now() + 3000).toISOString()
      mocks.get.mockResolvedValue(song)
      mount()
      await vi.advanceTimersByTimeAsync(10)
      const preview = document.querySelector<HTMLButtonElement>('.international-preview')!
      preview.click()
      player.current = player.toggle.mock.calls[0]![0]
      player.playing = true

      await vi.advanceTimersByTimeAsync(3000)
      expect(player.reset).toHaveBeenCalledOnce()
      expect(document.querySelector('.international-preview')).toBeNull()
      expect(document.querySelector('.international-share-channels')).toBeNull()
      expect(shareLink()).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('retires a card whose public preview has already expired', async () => {
    const expired = active()
    expired.card.expiresAt = new Date(Date.now() - 1000).toISOString()
    mocks.get.mockResolvedValue(expired)
    mount(); await flush()
    expect(document.querySelector('.international-preview')).toBeNull()
    expect(shareLink()).toBeNull()
    expect(mocks.create).not.toHaveBeenCalled()
  })
})

describe('share panel system actions', () => {
  beforeEach(() => setLocale('en'))

  it('uses the trusted daily override for outbound short links and opens the short route locally', async () => {
    vi.stubEnv('VITE_SONG_SHARE_ORIGIN', 'https://cheerfulmusic.com')
    mocks.get.mockResolvedValue(active(token, '我的歌', shortCode))
    mount(); await flush()
    const publicAddress = 'https://cheerfulmusic.com/s/' + shortCode
    expect(shareLink()?.value).toBe(publicAddress + '?lang=en')
    expect(channel('friend').href).toBe(publicAddress + '#share-friend')
    const listening = [...document.querySelectorAll<HTMLAnchorElement>('.international-brand-menu a')]
      .find(anchor => anchor.textContent?.trim() === 'Open listening page')
    expect(listening?.href).toBe(new URL('/s/' + shortCode + '?lang=en', location.origin).href)
    const x = document.querySelector<HTMLAnchorElement>('.international-share-channels a[href^="https://twitter.com/"]')!
    expect(new URL(x.href).searchParams.get('url')).toBe(publicAddress + '?lang=en&card=x')
    const xPreview = document.querySelector<HTMLAnchorElement>('.international-brand-menu a[href*="/x-player"]')!
    expect(xPreview.href).toBe(new URL('/song/' + token + '/x-player?lang=en', location.origin).href)
  })

  it('keeps the same-origin link when the share origin override is untrusted', async () => {
    vi.stubEnv('VITE_SONG_SHARE_ORIGIN', 'https://example.com')
    mocks.get.mockResolvedValue(active())
    mount(); await flush()
    expect(shareLink()?.value).toBe(new URL('/song/' + token + '?lang=en', location.origin).href)
  })

  it('does not report clipboard completion for a different locale link', async () => {
    let finish!: () => void
    const writeText = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    mocks.get.mockResolvedValue(active())
    mount(); await flush()
    button('Copy Link', shareActions()).click()
    setLocale('zh-CN')
    finish(); await flush()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(new URL('/song/' + token + '?lang=en', location.origin).href)
    expect(shareLink()?.value).toBe(new URL('/song/' + token, location.origin).href)
    expect(document.body.textContent).not.toContain('复制成功')
  })

  it('copies the full link via More Options and leaves the link selected if clipboard access is denied', async () => {
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('unavailable'))
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    mocks.get.mockResolvedValue(active())
    mount(); await flush()
    const input = document.querySelector<HTMLInputElement>('.international-share-link input')!
    button('More Options', shareActions()).click(); await flush()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(new URL('/song/' + token + '?lang=en', location.origin).href)
    expect(document.body.textContent).toContain('Copy successful')
    button('Copy Link', shareActions()).click(); await flush()
    expect(input.selectionEnd! - input.selectionStart!).toBe(input.value.length)
    expect(document.body.textContent).toContain('Select the link and copy it manually.')
  })

  it('sends a real poster file to the system share sheet only after the poster is ready', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { share, canShare: () => true })
    mocks.get.mockResolvedValue(active(token, '我的歌', shortCode))
    mount(); await flush()
    button('Instagram').click(); await flush()
    expect(mocks.internationalPoster).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      title: '我的歌',
      artist: '公开艺名',
      cover: '/api/songs/' + token + '/cover',
      link: new URL('/s/' + shortCode + '?lang=en', location.origin).href,
    }))
    expect(share).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(button('Share poster')).toBeDefined())
    button('Share poster').click()
    expect(share).toHaveBeenCalledExactlyOnceWith({ files: [expect.objectContaining({ name: '我的歌-poster.png', type: 'image/png' })] })
  })

  it('opens and closes an accessible fallback when the browser lacks native dialog methods', async () => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: undefined })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: undefined })
    const opener = document.body.appendChild(document.createElement('button'))
    opener.focus()
    const props = mount(); await flush()
    const dialog = document.querySelector<HTMLDialogElement>('.international-share-dialog')!
    expect(dialog.classList.contains('dialog-fallback')).toBe(true)
    expect(dialog.hasAttribute('open')).toBe(true)
    expect(document.querySelector('.song-share-fallback-backdrop')).not.toBeNull()
    expect(document.activeElement).toBe(dialog.querySelector('#song-share-title'))
    const firstButton = dialog.querySelector<HTMLButtonElement>('.international-round-control')!
    const lastButton = dialog.querySelector<HTMLButtonElement>('.song-share-close')!
    const rect = new DOMRect()
    const visibleRects: DOMRectList = Object.assign([rect], { item: (index: number) => index === 0 ? rect : null })
    vi.spyOn(firstButton, 'getClientRects').mockReturnValue(visibleRects)
    vi.spyOn(lastButton, 'getClientRects').mockReturnValue(visibleRects)
    lastButton.focus()
    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })
    lastButton.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(firstButton)
    props.open = false; await flush()
    expect(dialog.hasAttribute('open')).toBe(false)
    expect(document.querySelector('.song-share-fallback-backdrop')).toBeNull()
    expect(document.activeElement).toBe(opener)
  })
})
