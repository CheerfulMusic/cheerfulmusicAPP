// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { DiscoverSong } from '../src/modules/artist/api/artist'
import type { PlayableTrack } from '../src/shared/player/store'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), record: vi.fn(), queue: vi.fn(), toggle: vi.fn(),
  player: { current: null as PlayableTrack | null, playing: false, starting: false, duration: 0 },
}))
vi.mock('../src/modules/artist/api/artist', async original => ({
  ...await original<typeof import('../src/modules/artist/api/artist')>(),
  listDiscoverSongs: mocks.list, recordDiscoverPlay: mocks.record,
}))
vi.mock('../src/shared/player/store', () => ({
  usePlayer: () => Object.assign(mocks.player, { setQueue: mocks.queue, toggle: mocks.toggle }),
}))
vi.mock('../src/shared/ui/BrandLogo.vue', () => ({ default: { template: '<span>CHEERFUL MUSIC</span>' } }))
vi.mock('../src/modules/home/components/PlaylistDetails.vue', () => ({
  default: { props: ['playlist'], emits: ['close'], template: '<div class="test-playlist">{{ playlist.title }}<button @click="$emit(\'close\')">close</button></div>' },
}))

import DiscoverPage from '../src/modules/artist/pages/DiscoverPage.vue'
const wrappers: VueWrapper[] = []
function song(id: string, plays = 12): DiscoverSong {
  return { artistId: `artist-${id}`, stageName: `歌手${id}`, song: { id, version: 1, title: `歌曲${id}`, hasCover: true }, publishedAt: '2026-11-18T00:00:00Z', plays, genre: 'pop' }
}
function page(songs: DiscoverSong[], hasMore = false) { return { songs, hasMore } }
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(yes => { resolve = yes })
  return { promise, resolve }
}
async function open() {
  const wrapper = mount(DiscoverPage, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  vi.resetAllMocks()
  Object.assign(mocks.player, { current: null, playing: false, starting: false, duration: 0 })
  mocks.record.mockResolvedValue(undefined)
  mocks.toggle.mockImplementation(async (track: PlayableTrack) => {
    mocks.player.playing = !(mocks.player.current?.audio === track.audio && mocks.player.playing)
    mocks.player.current = track
  })
  mocks.list.mockImplementation(async (tab: string) => page([song(tab)]))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

describe('Discover editorial layout with real content', () => {
  it('loads independent featured and popular feeds and switches only between actual highlights', async () => {
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'featured' ? [song('精选一'), song('精选二')] : [song(tab)]))
    const wrapper = await open()
    expect(mocks.list.mock.calls).toEqual([
      ['latest', 0, '', ''], ['featured', 0, '', ''], ['popular', 0, '', ''],
    ])
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲精选一')
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲popular')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲精选二')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲精选一')
    expect(mocks.list).toHaveBeenCalledTimes(3)
  })

  it('keeps the main songs usable when recommendations fail and recovers the side feed on retry', async () => {
    let failed = true
    mocks.list.mockImplementation(async (tab: string) => {
      if (failed && tab !== 'latest') throw new Error('unavailable')
      return page([song(tab)])
    })
    const wrapper = await open()
    expect(wrapper.get('#discover-tracks').text()).toContain('歌曲latest')
    expect(wrapper.get('.discover-hero h2').text()).toBe('让好音乐，被更多人听见')
    expect(wrapper.find('.discover-featured').exists()).toBe(false)
    expect(wrapper.find('.discover-hero-next').exists()).toBe(false)
    expect(wrapper.get('.discover-sidebar').text()).toContain('暂时无法加载热门歌曲。')
    failed = false
    await wrapper.get('.discover-sidebar .text-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲popular')
    expect(wrapper.get('#discover-tracks').text()).toContain('歌曲latest')
  })

  it('does not fabricate featured songs or ranking positions for empty feeds', async () => {
    mocks.list.mockResolvedValue(page([]))
    const wrapper = await open()
    expect(wrapper.find('.discover-featured').exists()).toBe(false)
    expect(wrapper.find('.discover-ranking').exists()).toBe(false)
    expect(wrapper.find('.discover-hero-cover').exists()).toBe(false)
    expect(wrapper.get('#discover-tracks').text()).toContain('还没有歌曲发布到广场。')
    expect(wrapper.get('.discover-hero-listen').attributes('href')).toBe('#discover-tracks')
  })

  it('preserves genre, search, paging and deduplication without filtering editorial recommendations', async () => {
    mocks.list.mockImplementation(async (tab: string, offset: number) => page(offset ? [song('latest'), song('第二页')] : [song(tab)], !offset))
    const wrapper = await open()
    await wrapper.get('.discover-genre').setValue('folk')
    await flushPromises()
    await wrapper.get('input[type="search"]').setValue(' 海风 ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith('latest', 0, 'folk', '海风')
    await wrapper.get('.discover-more button').trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith('latest', 1, 'folk', '海风')
    expect(wrapper.findAll('#discover-tracks .discover-song')).toHaveLength(2)
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲featured')
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲popular')
    expect(wrapper.find('.discover-featured').exists()).toBe(false)
  })

  it('ignores a late initial response after a newer search', async () => {
    const old = deferred<ReturnType<typeof page>>()
    mocks.list.mockImplementation((tab: string, _offset: number, _genre: string, query: string) => tab === 'latest' && !query ? old.promise : Promise.resolve(page([song(query || tab)])))
    const wrapper = await open()
    await wrapper.get('input[type="search"]').setValue('新搜索')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    old.resolve(page([song('旧结果')]))
    await flushPromises()
    expect(wrapper.get('#discover-tracks').text()).toContain('歌曲新搜索')
    expect(wrapper.get('#discover-tracks').text()).not.toContain('歌曲旧结果')
  })

  it('plays the selected ranking queue and updates matching play counts across sections once', async () => {
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'popular' ? [song('共同作品'), song('榜单第二')] : [song('共同作品')]))
    const wrapper = await open()
    await wrapper.get('.discover-ranking-row').trigger('click')
    await flushPromises()
    expect(mocks.queue.mock.calls[0]![0].map((track: PlayableTrack) => track.id)).toEqual(['共同作品', '榜单第二'])
    expect(mocks.record).toHaveBeenCalledTimes(1)
    expect(wrapper.find('#discover-tracks [aria-label="13 次播放"]').exists()).toBe(true)
    expect(wrapper.find('.discover-featured [aria-label="13 次播放"]').exists()).toBe(true)
    await wrapper.get('.discover-ranking-row').trigger('click')
    await wrapper.get('.discover-ranking-row').trigger('click')
    await flushPromises()
    expect(mocks.record).toHaveBeenCalledTimes(1)
  })

  it('keeps failed playback out of play statistics and opens the actual playlist', async () => {
    mocks.toggle.mockImplementation(async () => undefined)
    const wrapper = await open()
    await wrapper.get('.discover-ranking-row').trigger('click')
    await flushPromises()
    expect(mocks.record).not.toHaveBeenCalled()
    const firstTitle = wrapper.get('.discover-playlist-title strong').text()
    await wrapper.get('.discover-playlist-tiles button').trigger('click')
    expect(wrapper.get('.test-playlist').text()).toContain(firstTitle)
  })
})
