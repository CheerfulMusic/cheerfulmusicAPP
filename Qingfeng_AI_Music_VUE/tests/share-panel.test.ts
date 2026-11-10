// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import SharePanel from '../src/modules/works/components/SharePanel.vue'
import type { Revision, AudioAsset } from '../src/modules/works/api/detail'

const mocks = vi.hoisted(() => ({ create: vi.fn(), get: vi.fn(), revoke: vi.fn(), poster: vi.fn() }))
vi.mock('../src/modules/works/api/detail', () => ({ createShare: mocks.create, revokeShares: mocks.revoke }))
vi.mock('../src/shared/http/api-client', () => ({ requestJson: mocks.get, apiUrl: (s: string) => '/api/' + s }))
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => ({ current: null, playing: false, starting: false, toggle: vi.fn(), reset: vi.fn() }) }))
vi.mock('../src/modules/works/model/song-poster', () => ({ renderSongPoster: mocks.poster }))
let app: App | undefined
const flush = async () => { for (let i = 0; i < 5; i++) { await nextTick(); await new Promise(r => setTimeout(r, 0)) } }
const token = 'A'.repeat(43)
function mount() {
  const props = reactive({ open: true, workId: 'work-a', revision: { id: 'revision-a', confirmed: true } as Revision, asset: { id: 'audio-a', origin: 'GENERATED' } as AudioAsset })
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
  mocks.create.mockResolvedValue({ token, expiresAt: new Date(Date.now() + 86400000).toISOString() })
  mocks.get.mockResolvedValue({ title: '我的歌', lyrics: '[Verse]\n窗台那盆薄荷\nKeep the light on', hasCover: true })
  mocks.poster.mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
})
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; vi.restoreAllMocks() })
describe('share panel state and poster lifecycle', () => {
  it('reads the published snapshot and offers card, poster and a backup public URL', async () => {
    mount(); await flush()
    expect(document.querySelector('.share-card-copy h3')?.textContent).toBe('我的歌')
    expect(button('分享歌曲卡片')).toBeTruthy(); expect(button('制作音乐海报')).toBeTruthy()
    expect(document.querySelector<HTMLInputElement>('.share-link-backup input')?.value).toContain('/gift/' + token)
    expect(document.querySelector('.share-card-art img')?.getAttribute('src')).toContain('/cover?proxy=true')
  })
  it('retries reading without creating a second token when the public snapshot request fails', async () => {
    mocks.get.mockRejectedValueOnce(new Error('暂时无法读取'))
    mount(); await flush(); button('重试创建分享链接').click(); await flush()
    expect(mocks.create).toHaveBeenCalledTimes(1); expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(document.querySelector('.share-card-copy h3')?.textContent).toBe('我的歌')
  })
  it('discards an old work response when the user switches works during creation', async () => {
    let resolve!: (value: unknown) => void
    mocks.create.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const props = mount(); await flush(); props.workId = 'work-b'; props.revision = { id: 'revision-b', confirmed: true } as Revision
    mocks.get.mockResolvedValue({ title: '第二首歌', lyrics: '新歌词' }); await flush()
    resolve({ token: 'B'.repeat(43), expiresAt: new Date(Date.now() + 86400000).toISOString() }); await flush()
    expect(document.querySelector('.share-card-copy h3')?.textContent).toBe('第二首歌')
    expect(document.querySelector<HTMLInputElement>('.share-link-backup input')?.value).not.toContain('B'.repeat(43))
  })
  it('invalidates an old poster after editing the selected lyric and never publishes stale render results', async () => {
    let finish!: (blob: Blob) => void
    mocks.poster.mockImplementationOnce(() => new Promise(r => { finish = r }))
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
    mount(); await flush(); button('制作音乐海报').click(); await flush()
    button('撤销本作品全部分享').click(); await flush()
    expect(mocks.revoke).toHaveBeenCalledWith('work-a')
    expect(document.querySelector('.share-song-card')).toBeNull(); expect(document.querySelector('.share-link-backup')).toBeNull()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:poster')
  })
})
