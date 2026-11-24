// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { loadPublicTrack, musicOrigin, receiveOpenedSong, shareNativeMusic, tokenFromSongUrl } from '../src/platform/native/wechat-music'

const plugin = vi.hoisted(() => ({ availability: vi.fn(), share: vi.fn(), consumeOpenedSong: vi.fn() }))
vi.mock('@capacitor/core', () => ({ registerPlugin: () => plugin }))
const id = 'a'.repeat(43)
const page = musicOrigin + '/song/' + id
const base = musicOrigin + '/api/songs/' + id
const data = { title: '下一波，我还在', creator: '音乐人', url: page, thumbnail: base + '/thumbnail.png',
  audio: base + '/audio', durationSeconds: 267.13, lyrics: '没有时间戳的歌词' }
const get = vi.fn()
beforeEach(() => {
  vi.clearAllMocks()
  plugin.availability.mockResolvedValue({ configured: true, installed: true, supported: true })
  get.mockImplementation(async (url: string) => url.endsWith('/public')
    ? new Response(JSON.stringify(data)) : new Response(new Uint8Array([1])))
  vi.stubGlobal('fetch', get)
  vi.stubGlobal('Image', class { onload?: () => void; set src(_url: string) { queueMicrotask(() => this.onload?.()) } })
  vi.stubGlobal('URL', class extends URL {
    static createObjectURL() { return 'blob:thumb' }
    static revokeObjectURL() {}
  })
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ fillRect() {}, drawImage() {} } as unknown as CanvasRenderingContext2D)
  vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockReturnValue('data:image/jpeg;base64,AQ==')
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('向微信发送已公开的原生歌曲', () => {
  it('只接受本平台的公开听歌地址', () => {
    expect(tokenFromSongUrl(page + '?from=timeline')).toBe(id)
    for (const invalid of [page.replace('https:', 'http:'), page.replace('cheerfulmusic.com','cheerfulmusic.com.example'),
      page.replace('//','//user@'), page.replace('/song/', '/gift/'), page.slice(0,-1)]) expect(tokenFromSongUrl(invalid)).toBeUndefined()
  })
  it.each(['friend','timeline'] as const)('提交 %s 目标及真实毫秒时长', async destination => {
    await expect(shareNativeMusic(id, destination)).resolves.toBe(true)
    expect(get.mock.calls[0][1]).toMatchObject({ credentials: 'omit', cache: 'no-store' })
    expect(plugin.share).toHaveBeenCalledWith({ songId: id, title: data.title, singer: data.creator, destination,
      durationMs: 267130, thumbnailBase64: 'AQ==' })
  })
  it('旧歌没有时长时从音频元数据读取，全程无需开始播放', async () => {
    get.mockResolvedValueOnce(new Response(JSON.stringify({ ...data, durationSeconds: null })))
    const play = vi.spyOn(HTMLMediaElement.prototype,'play')
    vi.spyOn(HTMLMediaElement.prototype,'load').mockImplementation(function(this: HTMLMediaElement) {
      if (!this.hasAttribute('src')) return
      Object.defineProperty(this, 'duration', { configurable:true, value:263.75 })
      queueMicrotask(() => this.dispatchEvent(new Event('loadedmetadata')))
    })
    await shareNativeMusic(id,'friend')
    expect(plugin.share).toHaveBeenCalledWith(expect.objectContaining({durationMs:263750}))
    expect(play).not.toHaveBeenCalled()
  })
  it('撤销、跨歌曲响应、切换版本时不会唤起微信', async () => {
    get.mockResolvedValueOnce(new Response(null, { status: 410 }))
    await expect(shareNativeMusic(id, 'timeline')).rejects.toThrow('关闭')
    get.mockResolvedValueOnce(new Response(JSON.stringify({ ...data, audio: base + '/private' })))
    await expect(loadPublicTrack(id)).rejects.toThrow('资料不完整')
    await expect(shareNativeMusic(id,'timeline',() => false)).resolves.toBe(false)
    expect(plugin.share).not.toHaveBeenCalled()
  })
  it('未配置移动应用标识时明确告知，不申请公共音频', async () => {
    plugin.availability.mockResolvedValue({ configured:false,installed:false,supported:false })
    await expect(shareNativeMusic(id,'friend')).rejects.toThrow('移动应用 AppID')
    expect(get).not.toHaveBeenCalled()
    expect(plugin.share).not.toHaveBeenCalled()
  })
  it('从微信返回仅接受有效歌曲标识', async () => {
    const open=vi.fn()
    plugin.consumeOpenedSong.mockResolvedValueOnce({songId:'https://another.example'}).mockResolvedValueOnce({songId:id})
    await receiveOpenedSong(open); await receiveOpenedSong(open)
    expect(open).toHaveBeenCalledExactlyOnceWith(id)
  })
})
