// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { PublicArtist } from '../src/modules/artist/api/artist'

const mocks = vi.hoisted(() => ({ profile: vi.fn(), discoverPlay: vi.fn(), samplePlay: vi.fn() }))
vi.mock('../src/modules/artist/api/artist', async original => ({
  ...await original<typeof import('../src/modules/artist/api/artist')>(),
  getPublicArtist: mocks.profile, recordDiscoverPlay: mocks.discoverPlay,
}))
vi.mock('../src/modules/home/model/hot-songs', () => ({ recordPlay: mocks.samplePlay }))

import { houseSelections, loadHouseRecommendations, recordPreviewPlay } from '../src/modules/home/model/published-recommendations'
import { filterPlaylists, usePreviewPlaylists } from '../src/modules/home/model/playlists'
import { tracks } from '../src/modules/home/model/tracks'

const wrappers: VueWrapper[] = []
function profile(): PublicArtist {
  return { stageName: 'SergioBeats', tagline: '', avatarKey: null, coverKey: null, featuredReleaseId: null,
    songs: houseSelections.map((selection, index) => ({ id: selection.songId, version: 2,
      title: ['Gravity', 'Static Bloom'][index]!, hasCover: true, coverVersion: `cover-${index}` })) }
}
async function open() {
  let playlists!: ReturnType<typeof usePreviewPlaylists>
  wrappers.push(mount(defineComponent({ setup() { playlists = usePreviewPlaylists({includeHouseReleases:true}); return () => null } })))
  await flushPromises()
  return playlists
}
beforeEach(() => { vi.resetAllMocks(); mocks.profile.mockResolvedValue(profile()); mocks.discoverPlay.mockResolvedValue(undefined) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })

it('adds the two existing releases to House with current public audio, cover and original titles', async () => {
  const playlists = await open()
  expect(mocks.profile).toHaveBeenCalledExactlyOnceWith(houseSelections[0].artistId)
  expect(playlists.value.map(playlist => playlist.tracks.length)).toEqual([8, 1, 3, 2])
  const house = playlists.value.find(playlist => playlist.id === 'house')!
  expect(house.tracks.map(track => track.title)).toEqual(['Gravity', 'Static Bloom'])
  for (const track of house.tracks) {
    expect(track.audio).toBe(`/api/public/artists/${houseSelections[0].artistId}/songs/${track.id}/2/audio`)
    expect(track.cover).toContain(`/songs/${track.id}/2/cover?cover=`)
    expect(track.publication?.stageName).toBe('SergioBeats')
    expect(track.filters).toEqual(['house', 'electronic'])
    expect(track.durationSeconds).toBeGreaterThan(220)
  }
  expect(filterPlaylists(playlists.value, 'house').flatMap(playlist => playlist.tracks)).toHaveLength(2)
  expect(filterPlaylists(playlists.value, 'electronic').flatMap(playlist => playlist.tracks)).toHaveLength(3)
  expect(tracks).toHaveLength(21) // Public releases are not copied into the demo catalogue.
})

it('stops recommending withdrawn releases and uses updated cover versions on refresh', async () => {
  const playlists = await open()
  const updated = profile()
  updated.songs = [updated.songs[1]!]
  updated.songs[0]!.coverVersion = 'updated-cover'
  mocks.profile.mockResolvedValue(updated)
  await loadHouseRecommendations()
  const house = playlists.value.find(playlist => playlist.id === 'house')!
  expect(house.tracks.map(track => track.title)).toEqual(['Static Bloom'])
  expect(house.tracks[0]!.cover).toContain('?cover=updated-cover')
  mocks.profile.mockRejectedValue(new Error('Artist page is private'))
  await loadHouseRecommendations()
  expect(playlists.value.find(playlist => playlist.id === 'house')!.tracks).toEqual([])
  expect(playlists.value[0]!.tracks).toHaveLength(8)
})

it('shares simultaneous artist lookups between playlist consumers', async () => {
  let complete!: (profile: PublicArtist) => void
  mocks.profile.mockReturnValue(new Promise<PublicArtist>(resolve => { complete = resolve }))
  const first = loadHouseRecommendations(), second = loadHouseRecommendations()
  expect(first).toBe(second)
  complete(profile())
  await Promise.all([first, second])
  expect(mocks.profile).toHaveBeenCalledTimes(1)
})

it('records public plays through the existing discover endpoint, retries failures and preserves demo counters', async () => {
  const playlists = await open()
  const [gravity, bloom] = playlists.value.find(playlist => playlist.id === 'house')!.tracks
  await Promise.all([recordPreviewPlay(gravity!), recordPreviewPlay(gravity!)])
  expect(mocks.discoverPlay).toHaveBeenCalledExactlyOnceWith(gravity!.publication!.song)
  expect(mocks.samplePlay).not.toHaveBeenCalled()
  mocks.discoverPlay.mockRejectedValueOnce(new Error('Offline'))
  await recordPreviewPlay(bloom!)
  await recordPreviewPlay(bloom!)
  expect(mocks.discoverPlay).toHaveBeenCalledTimes(3)
  await recordPreviewPlay(tracks[0]!)
  expect(mocks.samplePlay).toHaveBeenCalledExactlyOnceWith('love')
})
