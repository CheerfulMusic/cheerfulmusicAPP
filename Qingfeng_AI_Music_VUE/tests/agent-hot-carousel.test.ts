// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import AgentHotSongs from '../src/modules/agent/components/AgentHotSongs.vue'
import { listDiscoverSongs, recordDiscoverPlay, type DiscoverSong } from '../src/modules/artist/api/artist'
import { recordPlay } from '../src/modules/home/model/hot-songs'
import { setLocale } from '../src/shared/i18n'
import { usePlayer } from '../src/shared/player/store'

vi.mock('../src/modules/artist/api/artist', async original => ({
  ...await original<typeof import('../src/modules/artist/api/artist')>(),
  listDiscoverSongs: vi.fn(), recordDiscoverPlay: vi.fn(),
}))
vi.mock('../src/modules/home/model/hot-songs', async original => ({
  ...await original<typeof import('../src/modules/home/model/hot-songs')>(),
  loadHotSongs: vi.fn(), recordPlay: vi.fn(),
}))

function release(id: string, version = 1): DiscoverSong {
  return {
    artistId: 'artist-' + id, stageName: '歌手' + id, publishedAt: '2026-11-20T00:00:00Z',
    song: { id, version, title: '歌曲' + id, hasCover: true, coverVersion: 'v2' },
  }
}
let view: VueWrapper
let player: ReturnType<typeof usePlayer>
const list = vi.mocked(listDiscoverSongs)
const previous = () => view.get('button[aria-label="上一首推荐"]')
const next = () => view.get('button[aria-label="下一首推荐"]')
const title = () => view.get('.hot-featured h3').text()
const play = () => view.get('.hot-featured button').trigger('click')
async function open() {
  view = mount(AgentHotSongs)
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  setLocale('zh-CN')
  setActivePinia(createPinia())
  player = usePlayer()
  vi.spyOn(player, 'toggle').mockResolvedValue(undefined)
})
afterEach(() => view.unmount())

it('browses the public chart in both directions, loads later pages once and keeps the local ranking accessible', async () => {
  const a = release('a'), b = release('b'), c = release('c')
  list.mockResolvedValueOnce({ songs: [a, b], hasMore: true })
    .mockResolvedValueOnce({ songs: [b], hasMore: true })
    .mockResolvedValueOnce({ songs: [b, c], hasMore: false })
  await open()
  const top = title()
  expect(previous().attributes('aria-disabled')).toBe('true')
  expect(view.get('li .hot-rank').text()).toBe('02')
  await next().trigger('click')
  expect(title()).toBe(a.song.title)
  expect(view.get('.hot-featured-copy small').text()).toBe(a.stageName)
  expect(view.get('.hot-featured > img').attributes('src')).toContain('/artists/artist-a/songs/a/1/cover?cover=v2')
  expect(view.get('li .hot-rank').text()).toBe('01')
  expect(view.get('li strong').text()).toBe(top)
  await next().trigger('keydown', { key: 'ArrowRight' })
  expect(title()).toBe(b.song.title)
  expect(list.mock.calls).toEqual([['popular', 0, '', '']])
  await next().trigger('click')
  await flushPromises()
  expect(title()).toBe(c.song.title)
  expect(list.mock.calls.map(call => call[1])).toEqual([0, 2, 3])
  await next().trigger('click')
  expect(title()).toBe(top)
  await previous().trigger('keydown', { key: 'ArrowLeft' })
  expect(title()).toBe(c.song.title)
  expect(player.tracks).toHaveLength(0)
  expect(player.toggle).not.toHaveBeenCalled()
  expect(recordDiscoverPlay).not.toHaveBeenCalled()
  expect(recordPlay).not.toHaveBeenCalled()
})

it('queues versioned public audio and counts each release only after playback starts', async () => {
  const a = release('play-a', 4), b = release('play-b', 7)
  list.mockResolvedValue({ songs: [a, b], hasMore: false })
  await open()
  await next().trigger('click')
  await play()
  expect(player.tracks.map(track => track.audio)).toEqual([
    '/api/public/artists/artist-play-a/songs/play-a/4/audio',
    '/api/public/artists/artist-play-b/songs/play-b/7/audio',
  ])
  expect(player.toggle).toHaveBeenCalledWith(player.tracks[0])
  player.current = player.tracks[0]!
  player.playing = true
  player.starting = true
  await nextTick()
  expect(recordDiscoverPlay).not.toHaveBeenCalled()
  player.starting = false
  await nextTick()
  expect(recordDiscoverPlay).toHaveBeenCalledExactlyOnceWith(a.song)
  await next().trigger('click')
  expect(player.current?.title).toBe(a.song.title)
  expect(player.toggle).toHaveBeenCalledOnce()
  player.playing = false
  await nextTick()
  player.playing = true
  await nextTick()
  expect(recordDiscoverPlay).toHaveBeenCalledOnce()
  player.current = player.tracks[1]!
  await nextTick()
  expect(recordDiscoverPlay).toHaveBeenCalledTimes(2)
  expect(recordDiscoverPlay).toHaveBeenLastCalledWith(b.song)
  expect(recordPlay).not.toHaveBeenCalled()
})

it('keeps the cover on a failed page and resumes browsing at the same offset on retry', async () => {
  const a = release('retry-a'), b = release('retry-b')
  list.mockResolvedValueOnce({ songs: [a], hasMore: true })
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ songs: [b], hasMore: false })
  await open()
  await next().trigger('click')
  await next().trigger('click')
  await flushPromises()
  expect(title()).toBe(a.song.title)
  await view.get('.hot-chart-error button').trigger('click')
  await flushPromises()
  expect(title()).toBe(b.song.title)
  expect(list.mock.calls.map(call => call[1])).toEqual([0, 1, 1])
  expect(view.find('.hot-chart-error').exists()).toBe(false)
})

it('keeps the local chart usable if Discover is unavailable or has no releases', async () => {
  list.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({ songs: [], hasMore: false })
  await open()
  const top = title()
  expect(next().attributes('disabled')).toBeDefined()
  await view.get('.hot-chart-error button').trigger('click')
  await flushPromises()
  expect(title()).toBe(top)
  expect(view.get('.hot-chart-note').text()).toContain('广场暂无公开歌曲')
  expect(previous().attributes('disabled')).toBeDefined()
  expect(next().attributes('disabled')).toBeDefined()
  await play()
  expect(player.toggle).toHaveBeenCalledWith(expect.objectContaining({ title: top }))
  expect(player.tracks.length).toBeGreaterThan(1)
})
