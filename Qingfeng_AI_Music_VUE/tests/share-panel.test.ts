// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import SharePanel from '../src/modules/works/components/SharePanel.vue'
import type { Revision, AudioAsset } from '../src/modules/works/api/detail'

const mocks = vi.hoisted(() => ({ create: vi.fn(), get: vi.fn(), revoke: vi.fn(), revokeVersion: vi.fn(), poster: vi.fn() }))
vi.mock('../src/modules/works/api/detail', () => ({ createShare: mocks.create, getSongShareStatus: mocks.get, revokeShares: mocks.revoke, revokeSongShare: mocks.revokeVersion }))
vi.mock('../src/shared/http/api-client', () => ({ apiUrl: (s: string) => '/api/' + s }))
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => ({ current: null, playing: false, starting: false, toggle: vi.fn(), reset: vi.fn() }) }))
vi.mock('../src/modules/works/model/song-poster', () => ({ renderSongPoster: mocks.poster }))
let app: App | undefined
const flush = async () => { for (let i = 0; i < 5; i++) { await nextTick(); await new Promise(r => setTimeout(r, 0)) } }
const token = 'A'.repeat(43)
const privateState = { status: 'PRIVATE', card: null, views: 0, plays: 0 }
function active(songId = token, title = '我的歌') {
  return { status: 'SHAREABLE', views: 5, plays: 2, card: {
    songId, title, lyrics: '[Verse]\n窗台那盆薄荷\nKeep the light on', hasCover: true,
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
  } }
}
function mount(origin: AudioAsset['origin'] = 'GENERATED') {
  const revision = origin === 'GENERATED' ? { id: 'revision-a', confirmed: true } as Revision : null
  const props = reactive({ open: true, workId: 'work-a', revision, asset: { id: 'audio-a', origin } as AudioAsset })
  app = createApp({ setup: () => () => h(SharePanel, props) }); app.mount(document.body.appendChild(document.createElement('div')))
  return props
}
function button(text: string) { return [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === text)! }
beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value() { this.open = true } })
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value() { this.open = false } })
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => 'blob:poster') })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  mocks.create.mockResolvedValue(undefined)
  mocks.get.mockResolvedValue(privateState)
  mocks.poster.mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
})
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; vi.unstubAllGlobals(); vi.restoreAllMocks() })
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
    expect(document.querySelector('.share-link-panel')).toBeNull()
    const secondId = 'B'.repeat(43)
    mocks.get.mockResolvedValueOnce(active(secondId))
    button('启用公开试听').click(); await flush()
    expect(mocks.create).toHaveBeenLastCalledWith('work-a', 'audio-b', undefined, undefined)
    expect(document.querySelector<HTMLInputElement>('.share-link-panel input')?.value).toContain('/song/' + secondId)
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
  it('offers direct access to copy the public URL alongside the card and poster', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    mocks.get.mockResolvedValue(active())
    mount(); await flush()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('5 次访问')
    expect(document.querySelector('.share-card-copy h3')?.textContent).toBe('我的歌')
    expect(button('分享歌曲卡片')).toBeTruthy(); expect(button('制作音乐海报')).toBeTruthy()
    const copy = button('复制分享链接')
    const url = document.querySelector<HTMLInputElement>('.share-link-panel input')?.value
    expect(url).toContain('/song/' + token)
    expect(copy.closest('details')).toBeNull()
    copy.click(); await flush()
    expect(writeText).toHaveBeenCalledExactlyOnceWith(url)
    button('复制链接到微信').click(); await flush()
    expect(writeText).toHaveBeenLastCalledWith(url)
    expect(document.body.textContent).toContain('链接已复制，在微信聊天中粘贴即可发送。')
    writeText.mockRejectedValueOnce(new Error('denied'))
    button('复制链接到微信').click(); await flush()
    const input = document.querySelector<HTMLInputElement>('#song-share-link')!
    expect(input.selectionEnd! - input.selectionStart!).toBe(input.value.length)
    expect(document.body.textContent).toContain('请选中链接手动复制。')
    expect(document.querySelector('.share-card-art img')?.getAttribute('src')).toContain('/songs/' + token + '/cover')
  })
  it('retries only the status read after a confirmed change', async () => {
    mount(); await flush()
    mocks.get.mockRejectedValueOnce(new Error('暂时无法读取'))
    button('启用公开试听').click(); await flush()
    mocks.get.mockResolvedValueOnce(active())
    button('重新读取分享状态').click(); await flush()
    expect(mocks.create).toHaveBeenCalledTimes(1)
    expect(document.querySelector('.share-card-copy h3')?.textContent).toBe('我的歌')
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
    expect(document.querySelector('.share-card-copy h3')?.textContent).toBe('第二首歌')
    expect(document.querySelector<HTMLInputElement>('.share-link-panel input')?.value).toContain('B'.repeat(43))
    expect(mocks.create).toHaveBeenCalledTimes(1)
  })
  it('invalidates an old poster after editing the selected lyric and never publishes stale render results', async () => {
    let finish!: (blob: Blob) => void
    mocks.poster.mockImplementationOnce(() => new Promise(r => { finish = r }))
    mocks.get.mockResolvedValue(active())
    mount(); await flush(); button('制作音乐海报').click(); await flush()
    const select = document.querySelector<HTMLSelectElement>('.share-poster-options select')!
    select.value = 'Keep the light on'; select.dispatchEvent(new Event('change')); await flush()
    finish(new Blob(['stale'], { type: 'image/png' })); await flush()
    expect(document.querySelector('.share-poster-preview img')).toBeNull()
    button('生成海报预览').click(); await flush()
    expect(mocks.poster.mock.calls[1]![0].quote).toBe('Keep the light on')
    expect(document.querySelector('.share-poster-preview img')).not.toBeNull()
    app?.unmount(); app = undefined; expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:poster')
  })
  it('revocation clears the card, link and generated poster', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true); mocks.revoke.mockResolvedValue(undefined)
    mocks.get.mockResolvedValue(active())
    mount(); await flush(); button('制作音乐海报').click(); await flush()
    mocks.get.mockResolvedValue({ ...privateState, status: 'REVOKED' })
    button('撤销本作品全部分享').click(); await flush()
    expect(mocks.revoke).toHaveBeenCalledWith('work-a')
    expect(document.querySelector('.share-song-card')).toBeNull(); expect(document.querySelector('.share-link-panel')).toBeNull()
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
    expect(document.querySelector('.share-link-panel')).toBeNull()
  })
})
