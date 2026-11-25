// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ArtistHome from '../src/modules/artist/components/ArtistHome.vue'
import ProfileWorks from '../src/modules/artist/components/ProfileWorks.vue'
import type { ArtistRelease } from '../src/modules/artist/api/artist'
import { listWorks, type LibraryItem } from '../src/modules/works/api/works'

vi.mock('../src/modules/works/api/works', () => ({ listWorks: vi.fn() }))
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => ({}) }))

const timestamp = '2026-11-24T00:00:00Z'
const published: ArtistRelease = {
  id: 'release', workId: 'published-work', assetId: 'published-audio',
  version: 1, title: '已在主页展示的歌', coverId: null,
}
let wrapper: VueWrapper | undefined

function item(id: string, status: LibraryItem['status'], audioId: string | null = null): LibraryItem {
  return {
    work: {
      id, title: id, version: 1, createdAt: timestamp, updatedAt: timestamp,
      recipient: '', occasion: '', message: '', story: '', style: '', vocalGender: 'AUTO',
    },
    status, audioId, audioOrigin: audioId ? 'GENERATED' : null, songTitle: null,
  }
}

function completed(id: string) {
  return item(id, 'COMPLETED', `audio-${id}`)
}

async function open(songs = [published]) {
  const profile = mount(ProfileWorks, {
    props: { songs, busy: false, published: true },
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  wrapper = profile
  await flushPromises()
  return profile
}

afterEach(() => {
  wrapper?.unmount()
  vi.resetAllMocks()
})

it('finds the five latest completed songs across pages and keeps the library order', async () => {
  vi.mocked(listWorks)
    .mockResolvedValueOnce([
      item('草稿', 'LYRICS_DRAFT'), completed('作品一'),
      item('结果待核对', 'CHECKING', 'older-audio'), completed('作品二'),
      ...Array.from({ length: 16 }, (_, index) => item(`制作中${index}`, 'GENERATING')),
    ])
    .mockResolvedValueOnce([completed('作品三'), completed('作品四'), completed('作品五'), completed('作品六')])

  const profile = await open()

  expect(profile.findAll('.profile-work-copy strong').map(title => title.text()))
    .toEqual(['作品一', '作品二', '作品三', '作品四', '作品五'])
  expect(vi.mocked(listWorks).mock.calls).toEqual([[0], [1]])
})

it('shows fewer songs when only one is completed and preserves the published tab', async () => {
  vi.mocked(listWorks).mockResolvedValueOnce([item('未生成', 'READY_TO_GENERATE'), completed('已完成歌曲')])

  const profile = await open()

  expect(profile.findAll('.profile-work-copy strong').map(title => title.text())).toEqual(['已完成歌曲'])
  expect(profile.emitted('library-count')).toEqual([[2]])
  await profile.findAll('.profile-works-switch button')[1]!.trigger('click')
  expect(profile.findAll('.profile-work-copy strong').map(title => title.text())).toEqual([published.title])
})

it('shows the completed-works empty state when the library contains only drafts', async () => {
  vi.mocked(listWorks).mockResolvedValueOnce([item('草稿', 'LYRICS_DRAFT')])

  const profile = await open()

  expect(profile.find('.profile-work-row').exists()).toBe(false)
  expect(profile.get('.profile-works-message').text()).toContain('暂无已完成作品')
})

it('uses the Discover artwork on both owner tabs for the published recording', async () => {
  const work = completed('Navidad')
  const release = {
    ...published, id: '1c5c325c-6a45-4607-a8e4-5baadabc2032', title: 'Navidad',
    workId: work.work.id, assetId: work.audioId!, onDiscover: true, coverId: 'old-cover',
  }
  vi.mocked(listWorks).mockResolvedValueOnce([{ ...work, coverId: 'old-cover' }])
  const profile = await open([release])

  expect(profile.get('.profile-work-cover img').attributes('src')).toContain('navidad.webp')
  await profile.findAll('.profile-works-switch button')[1]!.trigger('click')
  expect(profile.get('.profile-work-cover img').attributes('src')).toContain('navidad.webp')
})

it('limits the published profile tab to 15 works until expanded', async () => {
  const songs = Array.from({ length: 17 }, (_, index) => ({
    ...published, id: `release-${index}`, workId: `work-${index}`,
    assetId: `audio-${index}`, title: `主页作品 ${index + 1}`,
  }))
  vi.mocked(listWorks).mockResolvedValueOnce([completed('已完成歌曲')])
  const profile = await open(songs)
  const control = () => profile.find('button[aria-controls="profile-works-list"]')

  expect(control().exists()).toBe(false)
  expect(profile.findAll('.profile-work-row')).toHaveLength(1)

  await profile.findAll('.profile-works-switch button')[1]!.trigger('click')
  expect(profile.findAll('.profile-work-row')).toHaveLength(15)
  expect(control().text()).toBe('更多')
  await control().trigger('click')
  expect(profile.findAll('.profile-work-copy strong').map(title => title.text())).toEqual(songs.map(song => song.title))
  expect(control().attributes('aria-expanded')).toBe('true')
  await control().trigger('click')
  expect(profile.findAll('.profile-work-row')).toHaveLength(15)
})

it('reveals all artist tracks in order and hides More when there are only 15 tracks', async () => {
  const tracks = Array.from({ length: 18 }, (_, index) => ({
    id: `track-${index}`, title: `公开作品 ${index + 1}`, cover: '/cover.webp', audio: `/audio/${index}`,
  }))
  const artist = mount(ArtistHome, { props: { name: '歌手', tagline: '', tracks } })
  wrapper = artist
  const control = () => artist.find('button[aria-controls="artist-track-list"]')

  expect(artist.findAll('.artist-track')).toHaveLength(15)
  expect(artist.get('#artist-tracks').text()).toContain('(18)')
  await control().trigger('click')
  expect(artist.findAll('.artist-track-title').map(title => title.text())).toEqual(tracks.map(track => track.title))
  expect(artist.findAll('.artist-track-order').at(-1)?.text()).toBe('18')
  await artist.setProps({ tracks: tracks.slice(0, 15) })
  expect(artist.findAll('.artist-track')).toHaveLength(15)
  expect(control().exists()).toBe(false)
})
