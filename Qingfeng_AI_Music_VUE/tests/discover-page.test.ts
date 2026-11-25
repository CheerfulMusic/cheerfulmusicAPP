// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { DiscoverSong } from '../src/modules/artist/api/artist'
import { setLocale } from '../src/shared/i18n'
import type { PlayableTrack } from '../src/shared/player/store'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), shelves: vi.fn(), profile: vi.fn(), record: vi.fn(), queue: vi.fn(), toggle: vi.fn(),
  player: { current: null as PlayableTrack | null, playing: false, starting: false, duration: 0, time: 0 },
}))
vi.mock('../src/modules/artist/api/artist', async original => ({
  ...await original<typeof import('../src/modules/artist/api/artist')>(),
  listDiscoverSongs: mocks.list, getDiscoverShelves: mocks.shelves, getPublicArtist: mocks.profile, recordDiscoverPlay: mocks.record,
}))
vi.mock('../src/shared/player/store', () => ({
  usePlayer: () => Object.assign(mocks.player, { setQueue: mocks.queue, toggle: mocks.toggle }),
}))
vi.mock('../src/shared/ui/BrandLogo.vue', () => ({ default: { template: '<span>CHEERFUL MUSIC</span>' } }))
vi.mock('../src/modules/home/components/PlaylistDetails.vue', () => ({
  default: { props: ['playlist'], emits: ['close'], template: '<div class="test-playlist">{{ playlist.title }}<span v-for="track in playlist.tracks" :key="track.id">{{ track.title }}</span><button @click="$emit(\'close\')">close</button></div>' },
}))

import DiscoverPage from '../src/modules/artist/pages/DiscoverPage.vue'
const wrappers: VueWrapper[] = []
function song(id: string, plays = 12): DiscoverSong {
  return { artistId: `artist-${id}`, stageName: `歌手${id}`, song: { id, version: 1, title: `歌曲${id}`, hasCover: true }, publishedAt: '2026-11-18T00:00:00Z', plays, genre: 'pop' }
}
function page(songs: DiscoverSong[], hasMore = false) { return { songs, hasMore } }
function shelfData(today: DiscoverSong[], vocal: DiscoverSong[] = []) {
  return { listeningDataAvailable: true, today, vocal, genres: {} }
}
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
  mocks.shelves.mockResolvedValue(shelfData([song('popular')]))
  mocks.profile.mockResolvedValue({
    stageName: 'SergioBeats',
    songs: [
      { id: '1cf20b2d-76cd-46de-9666-77a7303588a8', version: 1, title: 'Nochebuena en la Mesa', hasCover: true },
      { id: '752fa2d9-ec96-47c3-87a5-a1d49d0135af', version: 1, title: 'Snow on the Floor', hasCover: true },
      { id: 'd4b1ec5a-e68f-43e6-8624-226981ecef5c', version: 1, title: 'Christmas Sky', hasCover: true },
      { id: '1c5c325c-6a45-4607-a8e4-5baadabc2032', version: 2, title: 'Navidad', hasCover: true },
      { id: '0485811c-0297-4513-9fb1-75f981af8707', version: 1, title: 'Gravity', hasCover: true },
    ],
  })
  mocks.toggle.mockImplementation(async (track: PlayableTrack) => {
    mocks.player.playing = !(mocks.player.current?.audio === track.audio && mocks.player.playing)
    mocks.player.current = track
  })
  mocks.list.mockImplementation(async (tab: string) => page([song(tab)]))
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  setLocale('zh-CN')
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('Discover editorial layout with real content', () => {
  it('keeps covers tied to the song across shelves and uses dedicated artwork for Global Pop previews', async () => {
    const crossroads = song('0a67006e-997d-49b1-a162-a1a02f424c91')
    crossroads.song.title = '路口相逢'
    crossroads.song.hasCover = false
    const station = song('21282ee4-0645-4a38-aa08-3ba3b79be197')
    station.song.title = '晚风车站'
    station.song.hasCover = false
    const original = song('original')
    mocks.list.mockResolvedValue(page([crossroads, station, original]))
    mocks.shelves.mockResolvedValue({ ...shelfData([station, crossroads]), genres: { folk: [original, station] } })
    const wrapper = await open()
    const covers = (title: string) => wrapper.findAll('.shelf-track')
      .filter(card => card.get('.track-title').text() === title)
      .map(card => card.get('img').attributes('src'))

    expect(covers('路口相逢')).toHaveLength(3)
    expect(new Set(covers('路口相逢')).size).toBe(1)
    expect(covers('路口相逢')[0]).toContain('cover-06.webp')
    expect(new Set(covers('晚风车站')).size).toBe(1)
    expect(covers('晚风车站')[0]).toContain('wan-feng-che-zhan.webp')
    expect(new Set(covers(original.song.title)).size).toBe(1)
    expect(covers(original.song.title)[0]).toContain('/public/artists/artist-original/songs/original/1/cover')

    expect(covers('AFTER MIDNIGHT')).toHaveLength(2)
    expect(new Set(covers('AFTER MIDNIGHT')).size).toBe(1)
    expect(covers('AFTER MIDNIGHT')[0]).toContain('after-midnight.webp')
    expect(covers('Midnight Call')[0]).toContain('midnight-call.webp')
  })

  it('keeps the selected cover across shelves, rankings and search', async () => {
    const gravity = song('0485811c-0297-4513-9fb1-75f981af8707')
    gravity.song.title = 'Gravity'
    const streetlight = song('0f831f4b-1f3f-4d14-b6cf-b33534863622')
    streetlight.song.title = 'Streetlight'
    mocks.list.mockImplementation(async (tab: string) =>
      page(tab === 'featured' ? [streetlight, gravity] : [gravity, streetlight]))
    mocks.shelves.mockResolvedValue({
      ...shelfData([gravity, streetlight]),
      genres: { electronic: [gravity], folk: [gravity] },
    })
    const wrapper = await open()
    const cover = (section: string, title: string) => wrapper.findAll(section + ' .shelf-track')
      .find(card => card.get('.track-title').text() === title)?.get('img').attributes('src')

    for (const section of ['#discover-featured', '#discover-latest', '#discover-today', '#discover-electronic', '#discover-folk']) {
      expect(cover(section, 'Gravity')).toContain('cover-23.webp')
    }
    expect(cover('#discover-today', 'Streetlight')).toContain('cover-14.webp')
    expect(wrapper.get('.discover-ranking img').attributes('src')).toContain('cover-23.webp')
    await wrapper.get('input[type="search"]').setValue('Gravity')
    await wrapper.get('.discover-search').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.discover-song img').attributes('src')).toContain('cover-23.webp')
  })

  it('plays the four distinct vocal recordings and keeps the upcoming entry out of the queue and carousel logic', async () => {
    const ai = song('sole-route')
    ai.song.title = '唯一的航线'
    mocks.shelves.mockResolvedValue(shelfData([]))
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'popular' ? [ai] : []))
    const wrapper = await open()
    const section = wrapper.get('#discover-vocal')
    expect(section.findAll('.track-title').map(title => title.text())).toEqual([
      '忽然下起雨', '流浪诗人', '明天再说', '走了那么远', '唯一的航线',
    ])
    const upcoming = section.get('li:last-child')
    expect(upcoming.text()).toContain('待上线')
    expect(upcoming.find('button').exists()).toBe(false)
    expect(upcoming.get('img').attributes('src')).toContain('carousel-sea-stacks')
    expect(section.findAll('.shelf-track:not(.shelf-track-upcoming) img').map(image => image.attributes('src')?.split('/').at(-1))).toEqual([
      'hu-ran-xia-qi-yu.webp', 'liu-lang-shi-ren.webp', 'ming-tian-zai-shuo.webp', 'zou-le-na-me-yuan.webp',
    ])
    expect(section.findAll('li button')).toHaveLength(4)
    await section.get('[aria-label="试听 流浪诗人"]').trigger('click')
    expect(mocks.toggle.mock.calls[0]![0].audio).toMatch(/^\/media\/recommendations\/vocal-liu-lang-shi-ren-/)
    expect(mocks.queue.mock.calls[0]![0].map((track: PlayableTrack) => track.title)).toEqual([
      '忽然下起雨', '流浪诗人', '明天再说', '走了那么远',
    ])
    expect(mocks.record).not.toHaveBeenCalled()
    await wrapper.get('[aria-label="查看推荐：唯一的航线"]').trigger('click')
    await wrapper.get('.discover-hero-listen').trigger('click')
    expect(mocks.toggle.mock.calls[1]![0].id).toBe(ai.song.id)
  })

  it('shows five playlists, up to seven picks and the latest shelf before vocal and daily hits', async () => {
    const featured = Array.from({ length: 8 }, (_, i) => song(`精选${i + 1}`))
    const latest = Array.from({ length: 37 }, (_, i) => song(`新歌${i + 1}`))
    mocks.shelves.mockResolvedValue(shelfData([song('热门')], [song('真人')]))
    mocks.list.mockImplementation(async (tab: string, offset: number, _genre: string, query: string) => {
      if (tab === 'featured') return page(featured)
      if (tab === 'latest' && !query) return page(latest.slice(offset, offset + 24), !offset)
      return page([song(tab)])
    })
    const wrapper = await open()
    expect(wrapper.findAll('#discover-featured .shelf-square li')).toHaveLength(7)
    expect(wrapper.findAll('#discover-latest .shelf-compact li')).toHaveLength(32)
    expect(wrapper.findAll('.discover-shelf').slice(1, 4).map(section => section.attributes('id'))).toEqual([
      'discover-latest', 'discover-vocal', 'discover-today',
    ])
    expect(wrapper.get('#discover-vocal .shelf-wide').text()).toContain('歌曲真人')
    expect(wrapper.get('#discover-featured header').text()).toContain('编辑精选')
    expect(wrapper.get('#discover-today header').text()).toContain('按当日播放量排序')
    const tiles = wrapper.findAll('.discover-playlist-tiles button')
    expect(tiles.map(tile => tile.get('strong').text())).toEqual(['华语流行', '电子', '欧美流行', 'Hip-Hop&R&B', '圣诞'])
    expect(tiles.map(tile => tile.get('.discover-playlist-title > span').text())).toEqual([
      '14 首作品', '12 首作品', '6 首作品', '6 首作品', '4 首作品',
    ])
    expect(tiles.map(tile => tile.get('img').attributes('src')?.split('/').at(-1))).toEqual([
      'mandopop.webp', 'electronic.webp', 'global-pop.webp', 'house.webp', 'christmas.webp',
    ])
    await tiles[1]!.trigger('click')
    expect(wrapper.findAll('.test-playlist span').slice(-4).map(track => track.text())).toEqual([
      'Body Language', 'Into the Sun', 'One More Night', 'Gravity',
    ])
    await tiles[3]!.trigger('click')
    expect(wrapper.findAll('.test-playlist span')).toHaveLength(6)
    expect(wrapper.get('.test-playlist').text()).toContain('WESTSIDE SUMMER')
    await tiles[4]!.trigger('click')
    expect(wrapper.findAll('.test-playlist span').map(track => track.text())).toEqual([
      'Christmas Sky', 'Snow on the Floor', 'Navidad', 'Nochebuena en la Mesa',
    ])
  })

  it('adds the two supplied recordings to jazz without displacing existing songs', async () => {
    const publishedJazz = Array.from({ length: 7 }, (_, index) => song(`jazz-${index + 1}`))
    mocks.shelves.mockResolvedValue({ ...shelfData([]), genres: { 'jazz-soul': publishedJazz } })
    const wrapper = await open()
    const jazz = wrapper.get('#discover-jazz-soul .shelf-square')
    expect(jazz.findAll('.track-title').map(track => track.text())).toEqual([
      'CHRISTMAS FEELS LIKE HOME', 'Prayer Beneath the Stars', ...publishedJazz.map(track => track.song.title),
    ])
    await jazz.get('[aria-label="试听 Prayer Beneath the Stars"]').trigger('click')
    expect(mocks.toggle).toHaveBeenCalledWith(expect.objectContaining({ id: 'prayer-beneath-the-stars' }))
    expect(mocks.record).not.toHaveBeenCalled()
  })

  it('keeps the Christmas playlist visible when its public artist lookup is unavailable', async () => {
    mocks.profile.mockRejectedValue(new Error('unavailable'))
    const wrapper = await open()
    const tiles = wrapper.findAll('.discover-playlist-tiles button')
    expect(tiles).toHaveLength(5)
    expect(tiles[4]!.get('strong').text()).toBe('圣诞')
    await tiles[4]!.trigger('click')
    expect(wrapper.get('.test-playlist').text()).toContain('圣诞')
    expect(wrapper.findAll('.test-playlist span')).toHaveLength(0)
  })

  it.each([
    ['zh-CN', '青风音乐'],
    ['en', 'Cheerful Music'],
  ] as const)('shows the brand and keeps only real artist credits in %s', async (language, brand) => {
    setLocale(language)
    const release = song('欧美作品')
    release.stageName = 'SergioBeats'
    mocks.shelves.mockResolvedValue({ ...shelfData([song('热门')]), genres: { 'global-pop': [release] } })
    const wrapper = await open()

    expect(wrapper.get('#discover-featured h2').text()).toBe(brand)
    expect(wrapper.get('#discover-featured .shelf-tracks').attributes('aria-label')).toBe(brand)
    expect(wrapper.find('#discover-playlists header a').exists()).toBe(false)
    expect(wrapper.findAll('.discover-shelf header button').map(button => button.element.closest('section')?.id))
      .toEqual(['discover-vocal', 'discover-today'])
    expect(wrapper.get('#discover-global-pop .shelf-square .track-artist').text()).toBe('SergioBeats')
    for (const variant of ['square', 'wide']) {
      const track = wrapper.findAll(`#discover-global-pop .shelf-${variant} .shelf-track`)
        .find(card => card.get('.track-title').text() === 'AFTER MIDNIGHT')
      expect(track?.find('.track-meta').exists()).toBe(false)
    }
  })

  it('replaces the introduction and cycles through both new songs and the five popular slides', async () => {
    const popular = Array.from({ length: 6 }, (_, i) => song(`热门${i + 1}`, 600 - i * 100))
    mocks.shelves.mockResolvedValue(shelfData([song('今日一')]))
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'featured' ? [song('精选一')] : tab === 'popular' ? popular : [song(tab)]))
    const wrapper = await open()
    expect(mocks.list.mock.calls).toEqual([
      ['latest', 0, '', ''], ['featured', 0, '', ''], ['popular', 0, '', ''], ['latest', 0, '', 'Streetlight'],
    ])
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    const walkedFarBackground = wrapper.get('.discover-hero-background').attributes('src')
    expect(wrapper.find('.discover-hero-cover').exists()).toBe(false)
    expect(wrapper.findAll('.discover-hero-dots button')).toHaveLength(7)
    expect(wrapper.get('[aria-label="查看推荐：走了那么远"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲今日一')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲热门1')
    expect(wrapper.findAll('.discover-hero img')).toHaveLength(3)
    const songBackgrounds = new Set<string>()
    for (let i = 1; i <= 5; i++) {
      await wrapper.get(`[aria-label="查看推荐：歌曲热门${i}"]`).trigger('click')
      expect(wrapper.get('.discover-hero h2').text()).toBe(`歌曲热门${i}`)
      songBackgrounds.add(wrapper.get('.discover-hero-background').attributes('src')!)
    }
    expect(songBackgrounds.size).toBe(5)
    expect(songBackgrounds.has(walkedFarBackground!)).toBe(false)
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('Christmas Sky')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    expect(wrapper.get('.discover-hero-background').attributes('src')).toBe(walkedFarBackground)
    await wrapper.get('[aria-label="上一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('Christmas Sky')
    expect(wrapper.find('[aria-label="查看推荐：歌曲热门6"]').exists()).toBe(false)
    expect(wrapper.get('#discover-featured').text()).toContain('歌曲精选一')
  })

  it('plays the vocal recording and uses matching Christmas artwork for the carousel and latest releases', async () => {
    const christmas = song('d4b1ec5a-e68f-43e6-8624-226981ecef5c')
    christmas.song.title = 'Christmas Sky'
    mocks.profile.mockRejectedValue(new Error('unavailable'))
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'featured' ? [] : [christmas]))
    const wrapper = await open()

    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    expect(wrapper.get('.discover-hero-background').attributes('src')).toContain('carousel-walked-far.webp')
    expect(wrapper.findAll('.discover-hero-dots button')).toHaveLength(2)
    expect(wrapper.find('[aria-label="查看音乐广场介绍"]').exists()).toBe(false)
    await wrapper.get('.discover-hero-listen').trigger('click')
    expect(mocks.toggle.mock.lastCall![0].id).toBe('vocal-zou-le-na-me-yuan')
    await wrapper.get('[aria-label="查看推荐：Christmas Sky"]').trigger('click')
    expect(wrapper.get('.discover-hero-background').attributes('src')).toContain('carousel-christmas-sky.webp')
    const thumbnail = wrapper.get('#discover-latest .shelf-track img').attributes('src')
    expect(thumbnail).toContain('christmas-sky-cover.webp')
    await wrapper.get('.discover-hero-listen').trigger('click')
    expect(mocks.toggle.mock.lastCall![0]).toMatchObject({ id: christmas.song.id, cover: thumbnail })
  })

  it.each([true, false])('replaces the second slide with Streetlight and keeps the ranking intact (in feed: %s)', async (inFeed) => {
    const replaced = song('acfaa9d5-ce74-477f-9db8-d3700311a26c')
    replaced.song.title = '老爷子的领地'
    const streetlight = song('0f831f4b-1f3f-4d14-b6cf-b33534863622')
    streetlight.song.title = 'Streetlight'
    streetlight.song.version = 2
    streetlight.stageName = 'SWALEN'
    const ranked = [song('第一'), replaced, song('第三'), song('第四'), song('第五')]
    if (inFeed) ranked.push(streetlight)
    mocks.shelves.mockResolvedValue(shelfData(ranked))
    mocks.list.mockImplementation(async (tab: string, _offset: number, _genre: string, query: string) =>
      page(tab === 'popular' ? ranked : query === 'Streetlight' ? [song('同名作品'), streetlight] : []))

    const wrapper = await open()
    const dots = wrapper.findAll('.discover-hero-dots button')
    expect(dots).toHaveLength(7)
    expect(dots[2]!.attributes('aria-label')).toBe('查看推荐：Streetlight')
    expect(wrapper.find('[aria-label="查看推荐：老爷子的领地"]').exists()).toBe(false)
    expect(wrapper.get('.discover-ranking').text()).toContain('老爷子的领地')
    expect(mocks.list.mock.calls.some(call => call[3] === 'Streetlight')).toBe(!inFeed)

    await dots[2]!.trigger('click')
    expect(wrapper.get('.discover-hero').text()).toContain('SWALEN')
    await wrapper.get('.discover-hero-listen').trigger('click')
    await flushPromises()
    expect(mocks.toggle.mock.calls[0]![0].id).toBe(streetlight.song.id)
    expect(mocks.record).toHaveBeenCalledWith(streetlight.song)
  })

  it('pairs the two songs with their carousel artwork and plays the matching track', async () => {
    const micangshan = song('09aaf684-2b68-438f-aa17-8730a352bf74')
    micangshan.song.title = '米仓山'
    const invincible = song('57bb7310-c64c-4873-b239-f2a268a7dedd')
    invincible.song.title = '无敌的歌'
    const streetlight = song('0f831f4b-1f3f-4d14-b6cf-b33534863622')
    const popular = [song('航线'), micangshan, invincible, song('雨夜'), streetlight]
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'popular' ? popular : []))
    mocks.shelves.mockResolvedValue(shelfData(popular))

    const wrapper = await open()
    const dots = wrapper.findAll('.discover-hero-dots button')
    expect(dots[3]!.attributes('aria-label')).toBe('查看推荐：无敌的歌')
    expect(dots[4]!.attributes('aria-label')).toBe('查看推荐：米仓山')
    for (const [dot, expected, artwork] of [
      [dots[3]!, invincible, 'carousel-airport-guitar'],
      [dots[4]!, micangshan, 'carousel-countryside'],
    ] as const) {
      await dot.trigger('click')
      expect(wrapper.get('.discover-hero h2').text()).toBe(expected.song.title)
      expect(wrapper.get('.discover-hero-background').attributes('src')).toContain(artwork)
      await wrapper.get('.discover-hero-listen').trigger('click')
      expect(mocks.toggle.mock.calls.at(-1)![0].id).toBe(expected.song.id)
    }
    expect(wrapper.findAll('.discover-ranking-info strong').map(title => title.text()))
      .toEqual(popular.map(item => item.song.title))
  })

  it('keeps the main songs usable when recommendations fail and recovers the side feed on retry', async () => {
    mocks.profile.mockRejectedValue(new Error('unavailable'))
    let failed = true
    mocks.shelves.mockImplementation(async () => {
      if (failed) throw new Error('unavailable')
      return shelfData([song('popular')])
    })
    mocks.list.mockImplementation(async (tab: string) => {
      if (failed && tab !== 'latest') throw new Error('unavailable')
      return page([song(tab)])
    })
    const wrapper = await open()
    expect(wrapper.get('#discover-latest').text()).toContain('歌曲latest')
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    expect(wrapper.find('#discover-featured .shelf-tracks').exists()).toBe(false)
    expect(wrapper.find('.discover-hero-next').exists()).toBe(false)
    expect(wrapper.get('.discover-sidebar').text()).toContain('今日播放排行暂不可用。')
    failed = false
    await wrapper.get('.discover-sidebar .text-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲popular')
    expect(wrapper.get('#discover-latest').text()).toContain('歌曲latest')
  })

  it('fills empty picks and daily charts with public releases and labeled cumulative plays', async () => {
    const newest = song('最新')
    const sameCreator = { ...song('同歌手'), artistId: newest.artistId }
    const nextCreator = song('不同歌手')
    mocks.shelves.mockResolvedValue({ ...shelfData([]), listeningDataAvailable: false })
    mocks.list.mockImplementation(async (tab: string) => {
      if (tab === 'featured') return page([])
      if (tab === 'popular') return page([song('累计热门', 123), song('无播放', 0)])
      return page([newest, sameCreator, nextCreator])
    })
    const wrapper = await open()
    const featured = wrapper.get('#discover-featured')
    expect(featured.get('header').text()).toContain('新声推荐')
    expect(featured.findAll('.shelf-square .track-title').map(title => title.text())).toEqual(['歌曲最新', '歌曲不同歌手'])
    const hot = wrapper.get('#discover-today')
    expect(hot.get('header').text()).toContain('暂按累计播放量排序')
    expect(hot.findAll('.shelf-square .track-title').map(title => title.text())).toEqual(['歌曲累计热门'])
    expect(hot.get('.track-count').text()).toBe('123')
    expect(wrapper.get('#discover-popular-title').text()).toBe('累计热歌榜')
  })

  it('does not fabricate featured songs or ranking positions for empty feeds', async () => {
    mocks.profile.mockRejectedValue(new Error('unavailable'))
    mocks.list.mockResolvedValue(page([]))
    mocks.shelves.mockResolvedValue(shelfData([]))
    const wrapper = await open()
    expect(wrapper.find('#discover-featured .shelf-tracks').exists()).toBe(false)
    expect(wrapper.find('.discover-ranking').exists()).toBe(false)
    expect(wrapper.find('.discover-hero-cover').exists()).toBe(false)
    expect(wrapper.get('#discover-latest').text()).toContain('暂时没有歌曲')
    expect(wrapper.get('.discover-hero-listen').attributes('aria-label')).toBe('试听 走了那么远')
  })

  it('preserves genre, search, paging and deduplication without filtering editorial recommendations', async () => {
    mocks.list.mockImplementation(async (tab: string, offset: number) => page(offset ? [song('latest'), song('第二页')] : [song(tab)], !offset))
    const wrapper = await open()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
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
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲popular')
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲popular')
    expect(wrapper.find('#discover-featured').exists()).toBe(false)
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

  it('opens the public artist page from song bylines without starting playback', async () => {
    const release = song('单曲')
    mocks.list.mockResolvedValue(page([release]))
    mocks.shelves.mockResolvedValue({ ...shelfData([release]), genres: { 'global-pop': [release] } })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/artist/:id', name: 'artist-home', component: { template: '<div />' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
      ],
    })
    await router.push('/discover')
    const wrapper = mount(DiscoverPage, { global: { plugins: [router] } })
    wrappers.push(wrapper)
    await flushPromises()

    const expectedUrl = '/artist/' + encodeURIComponent(release.artistId)
    for (const selector of [
      '#discover-featured .track-artist',
      '#discover-latest .track-artist',
      '#discover-today .track-artist',
      '#discover-global-pop .shelf-square .track-artist',
      '#discover-global-pop .shelf-wide .track-artist',
      '.discover-ranking-info a:not(.discover-song-title-link)',
    ]) {
      const link = wrapper.get(selector)
      expect(link.attributes('href')).toBe(expectedUrl)
      expect(link.element.closest('button')).toBeNull()
    }
    await wrapper.get('#discover-latest .track-artist').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value).toMatchObject({ name: 'artist-home', params: { id: release.artistId } })
    expect(mocks.toggle).not.toHaveBeenCalled()
    expect(mocks.record).not.toHaveBeenCalled()

    const songLink = wrapper.get('#discover-latest .track-title')
    expect(songLink.attributes('href')).toBe('/discover/songs/' + encodeURIComponent(release.song.id) + '/1')
    await songLink.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe(songLink.attributes('href'))
    expect(mocks.toggle).not.toHaveBeenCalled()

    await wrapper.get('#discover-latest .shelf-track-play').trigger('click')
    expect(mocks.toggle.mock.lastCall![0].id).toBe(release.song.id)
  })

  it('plays the selected ranking queue and updates matching play counts across sections once', async () => {
    mocks.shelves.mockImplementation(async () => shelfData([song('共同作品', 12 + mocks.record.mock.calls.length), song('榜单第二')]))
    mocks.list.mockResolvedValue(page([song('共同作品')]))
    const wrapper = await open()
    await wrapper.get('.discover-ranking-play').trigger('click')
    await flushPromises()
    expect(mocks.queue.mock.calls[0]![0].map((track: PlayableTrack) => track.id)).toEqual(['共同作品', '榜单第二'])
    expect(mocks.record).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.discover-ranking-row .discover-ranking-count').text()).toBe('13')
    expect(wrapper.get('#discover-today .track-count').text()).toBe('13')
    await wrapper.get('.discover-ranking-play').trigger('click')
    await wrapper.get('.discover-ranking-play').trigger('click')
    await flushPromises()
    expect(mocks.record).toHaveBeenCalledTimes(1)
  })

  it('keeps failed playback out of play statistics and opens the actual playlist', async () => {
    mocks.toggle.mockImplementation(async () => undefined)
    const wrapper = await open()
    await wrapper.get('.discover-ranking-play').trigger('click')
    await flushPromises()
    expect(mocks.record).not.toHaveBeenCalled()
    const firstTitle = wrapper.get('.discover-playlist-title strong').text()
    await wrapper.get('.discover-playlist-tiles button').trigger('click')
    expect(wrapper.get('.test-playlist').text()).toContain(firstTitle)
  })

  it('shows popular slides even when the featured request is still pending', async () => {
    const pending = deferred<ReturnType<typeof page>>()
    mocks.list.mockImplementation((tab: string) => tab === 'featured' ? pending.promise : Promise.resolve(page([song(tab)])))
    const wrapper = await open()
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲popular')
    expect(wrapper.get('.discover-ranking').text()).toContain('歌曲popular')
    pending.resolve(page([song('精选')]))
    await flushPromises()
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲popular')
  })

  it('plays a hero song using all visible tracks as its queue without starting audio on slide changes', async () => {
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'popular' ? Array.from({ length: 7 }, (_, i) => song(`热门${i + 1}`)) : [song(tab)]))
    const wrapper = await open()
    await wrapper.get('[aria-label="查看推荐：歌曲热门5"]').trigger('click')
    expect(mocks.toggle).not.toHaveBeenCalled()
    expect(mocks.record).not.toHaveBeenCalled()
    await wrapper.get('.discover-hero-listen').trigger('click')
    await flushPromises()
    expect(mocks.toggle.mock.calls[0]![0].id).toBe('热门5')
    expect(mocks.queue.mock.calls[0]![0].map((track: PlayableTrack) => track.id)).toEqual([
      'vocal-zou-le-na-me-yuan', '热门1', '热门2', '热门3', '热门4', '热门5', 'd4b1ec5a-e68f-43e6-8624-226981ecef5c',
    ])
    expect(mocks.record).toHaveBeenCalledTimes(1)
  })

  it('rotates every 10 seconds, pauses on hover or focus and supports explicit pause and resume', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    mocks.list.mockImplementation(async (tab: string) => page(tab === 'popular' ? [song('热门一'), song('热门二')] : [song(tab)]))
    const wrapper = await open()
    await vi.advanceTimersByTimeAsync(9_999)
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲热门一')
    await wrapper.get('.discover-hero').trigger('mouseenter')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲热门一')
    await wrapper.get('.discover-hero').trigger('mouseleave')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲热门二')
    await wrapper.get('.discover-hero').trigger('focusin')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲热门二')
    await wrapper.get('.discover-hero').trigger('focusout', { relatedTarget: null })
    await wrapper.get('[aria-label="暂停轮播"]').trigger('click')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲热门二')
    await wrapper.get('[aria-label="继续轮播"]').trigger('click')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('Christmas Sky')
    expect(mocks.toggle).not.toHaveBeenCalled()
  })

  it('does not rotate while hidden and clears the timer when leaving the page', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    const wrapper = await open()
    expect(vi.getTimerCount()).toBe(1)
    visibility.mockReturnValue('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    visibility.mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(vi.getTimerCount()).toBe(1)
    wrapper.unmount()
    wrappers.splice(wrappers.indexOf(wrapper), 1)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('respects reduced motion and keeps arrows available with only one popular song', async () => {
    mocks.profile.mockRejectedValue(new Error('unavailable'))
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() } as unknown as MediaQueryList)
    const wrapper = await open()
    expect(wrapper.findAll('.discover-hero-dots button')).toHaveLength(2)
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('歌曲popular')
    await wrapper.get('[aria-label="下一张推荐"]').trigger('click')
    expect(wrapper.get('.discover-hero h2').text()).toBe('走了那么远')
  })
})
