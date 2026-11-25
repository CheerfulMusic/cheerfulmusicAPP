// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive, type App } from 'vue'
import GlobalPlayer from '../src/shared/player/GlobalPlayer.vue'
import type { PlaybackLyrics } from '../src/modules/works/api/detail'
import { lyricsSource } from '../src/shared/player/current-lyrics'
import discoverMedia from '../src/modules/artist/model/discover-media.json'
const api = vi.hoisted(() => ({ get: vi.fn(), sync: vi.fn(), public: vi.fn() }))
vi.mock('../src/modules/works/api/detail', () => ({ getPlaybackLyrics: api.get, syncPlaybackLyrics: api.sync }))
vi.mock('../src/shared/http/api-client', () => ({ requestJson: api.public }))
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => player }))
let player: any, app: App | undefined
const lines = [{ text: '第一句唱词', start: 10, end: 15 }, { text: '第二句唱词', start: 18, end: 24 }]
const snapshot = (status: PlaybackLyrics['status'] = 'READY', canSync = false): PlaybackLyrics =>
  ({ status, text: '已归档歌词', canSync, lines: status === 'READY' ? lines : [] })
const track = (id = 'one') => ({ id, title: '我的歌曲', cover: '/cover.png', audio: `/api/app/works/work/audio/${id}` })
async function flush() { for (let i = 0; i < 20; i++) await nextTick() }
function mount() { app = createApp({ render: () => h(GlobalPlayer) }); app.mount(document.body.appendChild(document.createElement('div'))) }
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); sessionStorage.clear()
  player = reactive({ current: track(), playing: true, starting: false, buffering: false, error: '', time: 0, duration: 30,
    volume: 0.6, muted: false, hasQueue: true, repeatMode: 'default', setRepeatMode: vi.fn(), toggle: vi.fn(), next: vi.fn(), seek: vi.fn(), close: vi.fn(), setVolume: vi.fn(), toggleMute: vi.fn() })
  api.get.mockResolvedValue(snapshot()); api.public.mockResolvedValue(snapshot()); api.sync.mockResolvedValue(snapshot())
})
afterEach(() => { app?.unmount(); app = undefined; document.body.innerHTML = ''; vi.useRealTimers(); vi.restoreAllMocks() })
it('follows exact singing boundaries, pause and seek without showing provenance', async () => {
  mount(); await flush(); expect(document.body.textContent).toContain('前奏…')
  player.time = 10; await flush(); expect(document.body.textContent).toContain('第一句唱词')
  player.playing = false; await flush(); expect(document.body.textContent).toContain('第一句唱词')
  player.time = 16; await flush(); expect(document.body.textContent).toContain('间奏…')
  player.time = 20; await flush(); expect(document.body.textContent).toContain('第二句唱词')
  player.time = 12; await flush(); expect(document.body.textContent).toContain('第一句唱词')
  player.time = 25; await flush(); expect(document.body.textContent).toContain('尾奏…')
  expect(document.body.textContent).not.toMatch(/AI生成作品|示例歌曲试听/)
})
it('a late response for a previous version never replaces the new lyric', async () => {
  let finish!: (value: PlaybackLyrics) => void
  api.get.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  mount(); await flush(); player.current = track('two'); await flush(); player.time = 20; await flush()
  finish({ ...snapshot(), lines: [{ text: '上一版本不能出现', start: 0, end: 100 }] }); await flush()
  expect(document.body.textContent).toContain('第二句唱词'); expect(document.body.textContent).not.toContain('上一版本不能出现')
})
it('syncs an owner asset once only after playback and shares the existing request key', async () => {
  api.get.mockResolvedValue(snapshot('NOT_REQUESTED', true)); player.playing = false; mount(); await flush()
  expect(api.sync).not.toHaveBeenCalled(); player.playing = true; await flush()
  expect(api.sync).toHaveBeenCalledTimes(1); expect(api.sync.mock.calls[0]![2]).toBe(sessionStorage.getItem('qingfeng.lyrics.timing:one'))
  player.playing = false; await flush(); player.playing = true; await flush(); expect(api.sync).toHaveBeenCalledTimes(1)
})
it('unknown outcomes are read again but never re-submitted and polling stops on close', async () => {
  api.get.mockResolvedValue(snapshot('NOT_REQUESTED', true)); api.sync.mockResolvedValue(snapshot('UNKNOWN', true))
  mount(); await flush(); api.get.mockResolvedValue(snapshot('UNKNOWN', true)); await vi.advanceTimersByTimeAsync(9000)
  expect(api.sync).toHaveBeenCalledTimes(1); player.current = null; await flush(); const calls = api.get.mock.calls.length
  await vi.advanceTimersByTimeAsync(200000); expect(api.get).toHaveBeenCalledTimes(calls); expect(document.querySelector('.q-player')).toBeNull()
})
it('reads only the versioned public route without starting a timing job', async () => {
  player.current = { ...track(), audio: '/api/public/artists/person/songs/release/4/audio' }
  mount(); await flush(); expect(api.public).toHaveBeenCalledWith('public/artists/person/songs/release/4/lyrics')
  expect(api.get).not.toHaveBeenCalled(); expect(api.sync).not.toHaveBeenCalled()
})
it('a lost sync response polls the existing job instead of posting again', async () => {
  api.get.mockResolvedValueOnce(snapshot('NOT_REQUESTED', true)).mockResolvedValue(snapshot())
  api.sync.mockRejectedValueOnce(new Error('response lost'))
  mount(); await flush(); expect(api.sync).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(7000); await flush(); player.time = 12; await flush()
  expect(document.body.textContent).toContain('第一句唱词'); expect(api.sync).toHaveBeenCalledTimes(1)
})
it('missing sample timing is honest and curated timing can be supplied without network calls', async () => {
  api.public.mockResolvedValue({...snapshot('EMPTY'),text:''}); player.current = { ...track(), audio: '/media/sample-feng-ye-xing-v1-preview-128.mp3' }; mount(); await flush()
  expect(document.body.textContent).toContain('暂无歌词'); expect(api.get).not.toHaveBeenCalled()
  player.current = { ...player.current, lyricLines: lines }; player.time = 20; await flush()
  expect(document.body.textContent).toContain('第二句唱词')
})
it('buffering and playback errors have priority over lyric text', async () => {
  mount(); player.time = 12; await flush(); player.buffering = true; await flush(); expect(document.body.textContent).toContain('正在缓冲')
  player.error = '播放失败'; await flush(); expect(document.body.textContent).toContain('播放失败'); expect(document.body.textContent).not.toContain('第一句唱词')
})
it('volume controls expose accessible percentage, mute and the slider action', async () => {
  mount(); await flush(); const slider = document.querySelector<HTMLInputElement>('[aria-label="音量"]')!
  expect(slider.getAttribute('aria-valuetext')).toBe('60%'); slider.value = '0.25'; slider.dispatchEvent(new Event('input')); expect(player.setVolume).toHaveBeenCalledWith(0.25)
  document.querySelector<HTMLButtonElement>('[aria-label="静音"]')!.click(); expect(player.toggleMute).toHaveBeenCalledOnce()
})

it('opens playback modes, disables playlist repeat for one song and closes the menu on selection', async () => {
  player.hasQueue = false
  mount(); await flush()
  const trigger = document.querySelector<HTMLButtonElement>('[aria-label="播放模式：默认播放"]')!
  trigger.click(); await flush()
  const menu = document.querySelector('[aria-label="播放模式"]')!
  const options = Array.from(menu.querySelectorAll('button'))
  expect(options.map(option => option.textContent?.trim().replace(/\s+✓$/, ''))).toEqual(['默认播放', '单曲循环', '列表循环'])
  expect(options[2]?.disabled).toBe(true)
  options[1]?.click(); await flush()
  expect(player.setRepeatMode).toHaveBeenCalledWith('one')
  expect(trigger.getAttribute('aria-expanded')).toBe('false')
  expect(document.activeElement).toBe(trigger)
})

it('hot songs read their catalog timeline and never start a provider job', async () => {
  player.current={...track(),audio:'/media/sample-feng-ye-xing-v1-preview-128.mp3'}
  mount(); await flush(); player.time=12; await flush()
  expect(api.public).toHaveBeenCalledWith('public/hot-songs/feng-ye-xing/lyrics')
  expect(document.body.textContent).toContain('第一句唱词'); expect(api.sync).not.toHaveBeenCalled()
})
it('new hash-named samples read the timeline for that audio version and keep authored words literal', async () => {
  player.current={...track(),audio:'/media/recommendations/body-language-8e59749c37e9-preview-128.mp3'}
  api.public.mockResolvedValue({...snapshot(),lines:[{text:"Don't talk, just dance",start:10,end:15}]})
  mount();await flush();player.time=12;await flush()
  expect(api.public).toHaveBeenCalledWith('public/hot-songs/body-language/lyrics?audioHash=8e59749c37e9')
  expect(document.body.textContent).toContain("Don't talk, just dance");expect(api.sync).not.toHaveBeenCalled()
})
it('explicit instrumental metadata is shown without polling or sync attempts', async () => {
  player.current={...track(),audio:'/media/recommendations/euphoric-drop-bceceadc198d-preview-128.mp3'}
  api.public.mockResolvedValue({status:'INSTRUMENTAL',text:'',lines:[],canSync:false})
  mount();await flush();expect(document.body.textContent).toContain('纯音乐（无歌词）')
  await vi.advanceTimersByTimeAsync(60000);expect(api.public).toHaveBeenCalledTimes(1);expect(api.sync).not.toHaveBeenCalled()
})
it('changing a recommendation audio hash invalidates an in-flight response for the old recording', async () => {
  let finish!: (value:PlaybackLyrics)=>void
  api.public.mockReturnValueOnce(new Promise(resolve=>{finish=resolve})).mockResolvedValue({...snapshot(),lines:[{text:'新版录音唱词',start:10,end:15}]})
  player.current={...track(),audio:'/media/recommendations/body-language-8e59749c37e9-preview-128.mp3'}
  mount();await flush()
  player.current={...player.current,audio:'/media/recommendations/body-language-123456789abc-preview-128.mp3'}
  await flush();player.time=12;await flush();finish(snapshot());await flush()
  expect(api.public).toHaveBeenLastCalledWith('public/hot-songs/body-language/lyrics?audioHash=123456789abc')
  expect(document.body.textContent).toContain('新版录音唱词');expect(document.body.textContent).not.toContain('第一句唱词')
})
it('rejects non-audio and malformed recommendation paths instead of treating a cover as a lyric source', () => {
  expect(lyricsSource('/media/recommendations/body-language-c251e80045fe-cover.jpg')).toBeUndefined()
  expect(lyricsSource('/media/recommendations/body-language-short-preview-128.mp3')).toBeUndefined()
  expect(lyricsSource('/media/recommendations/body-language-8e59749c37e9-preview-128.mp3/private')).toBeUndefined()
  expect(lyricsSource(discoverMedia['zui-hua-jazz'].audio)).toBeUndefined()
})
it('loads the version-bound synchronized lyrics for discover rock tracks', () => {
  expect(lyricsSource(discoverMedia['bicycle-weather'].audio)).toEqual({
    kind: 'public', path: 'public/hot-songs/bicycle-weather/lyrics?audioHash=efad57f38eeb',
  })
  expect(lyricsSource(discoverMedia['cardboard-corners'].audio)).toEqual({
    kind: 'public', path: 'public/hot-songs/cardboard-corners/lyrics?audioHash=468267313021',
  })
})
it('preparing public songs update automatically when the background timeline is ready', async () => {
  player.current={...track(),audio:'/api/public/artists/a/songs/b/2/audio'}
  api.public.mockResolvedValueOnce(snapshot('PREPARING')).mockResolvedValue(snapshot())
  mount(); await flush(); expect(document.body.textContent).toContain('歌词准备中')
  await vi.advanceTimersByTimeAsync(3000); await flush();player.time=20;await flush()
  expect(document.body.textContent).toContain('第二句唱词');expect(api.sync).not.toHaveBeenCalled()
})
