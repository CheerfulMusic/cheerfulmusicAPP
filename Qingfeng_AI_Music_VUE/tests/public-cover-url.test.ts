// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { requestJson } from '../src/shared/http/api-client'
import PublicArtistPage from '../src/modules/artist/pages/PublicArtistPage.vue'
import { publicDiscoverTrack } from '../src/modules/artist/model/discover-shelves'
import { discoverSongArtwork } from '../src/modules/artist/model/discover-hero-artwork'

const player = vi.hoisted(() => ({ current: null, setQueue: vi.fn(), toggle: vi.fn() }))

vi.mock('../src/shared/http/api-client', () => ({
  apiUrl: (path: string) => `/api/${path}`, ensureCreator: vi.fn(), requestJson: vi.fn(), uploadForm: vi.fn(),
}))
vi.mock('../src/shared/player/store', () => ({ usePlayer: () => player }))
import { publicTrack } from '../src/modules/artist/api/artist'

it('refreshes the public cover URL while keeping the song and audio version stable', () => {
  const song = { id: 'song', title: '歌名', version: 3, hasCover: true, coverVersion: 'first' }
  const before = publicTrack('artist', song)
  const after = publicTrack('artist', { ...song, coverVersion: 'second' })
  expect(after.cover).toBe('/api/public/artists/artist/songs/song/3/cover?cover=second')
  expect(after.cover).not.toBe(before.cover)
  expect(after.audio).toBe(before.audio)
  expect(after.id).toBe(before.id)
  expect(publicTrack('artist', { ...song, hasCover: false, coverVersion: null }).cover).toBe('/brand/share-default-cover.png')
})

it('accepts public song data returned before cover identifiers were included', () => {
  expect(publicTrack('artist', { id: 'song', title: '歌名', version: 3, hasCover: true }).cover)
    .toBe('/api/public/artists/artist/songs/song/3/cover')
})

it('uses the square artwork from Discover for the artist feature, list and playback', async () => {
  const artistId = 'artist'
  const sea = { id: 'de4668db-cddf-4f7f-b66a-a0f5a4f54c79', version: 1, title: '唯一的航线', hasCover: true }
  const original = { id: 'another-song', version: 1, title: '我的歌', hasCover: true }
  vi.mocked(requestJson).mockResolvedValueOnce({
    stageName: '艺人', tagline: '', avatarKey: null, coverKey: null, featuredReleaseId: sea.id, songs: [original, sea],
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/artist/:id', component: PublicArtistPage }] })
  await router.push(`/artist/${artistId}`)
  const wrapper = mount(PublicArtistPage, { global: { plugins: [router], stubs: { AlbumShelf: true, ArtistShareButton: true } } })
  try {
    await flushPromises()
    const track = publicDiscoverTrack({ artistId, stageName: '艺人', song: sea })
    expect(track.cover).toBe(discoverSongArtwork[0].compact)
    expect(wrapper.get('.artist-feature-cover img').attributes('src')).toBe(track.cover)
    const rows = wrapper.findAll('.artist-track')
    expect(rows[0]!.get('img').attributes('src')).toBe(publicTrack(artistId, original).cover)
    expect(rows[1]!.get('img').attributes('src')).toBe(track.cover)
    await rows[1]!.trigger('click')
    expect(player.toggle).toHaveBeenLastCalledWith(expect.objectContaining({ cover: track.cover, audio: track.audio }))
  } finally {
    wrapper.unmount()
  }
})
