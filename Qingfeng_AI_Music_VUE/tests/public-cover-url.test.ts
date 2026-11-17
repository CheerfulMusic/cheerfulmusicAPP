// @vitest-environment happy-dom
import { expect, it, vi } from 'vitest'
vi.mock('../src/shared/http/api-client', () => ({
  apiUrl: (path: string) => `/api/${path}`, ensureCreator: vi.fn(), requestJson: vi.fn(), uploadForm: vi.fn(),
}))
import { publicTrack } from '../src/modules/artist/api/artist'

it('refreshes the public cover URL while keeping the song and audio version stable', () => {
  const song = { id: 'song', title: '歌名', version: 3, hasCover: true, coverId: 'first' }
  const before = publicTrack('artist', song)
  const after = publicTrack('artist', { ...song, coverId: 'second' })
  expect(after.cover).toBe('/api/public/artists/artist/songs/song/3/cover?cover=second')
  expect(after.cover).not.toBe(before.cover)
  expect(after.audio).toBe(before.audio)
  expect(after.id).toBe(before.id)
  expect(publicTrack('artist', { ...song, hasCover: false, coverId: null }).cover).toBe('/brand/share-default-cover.png')
})

it('accepts public song data returned before cover identifiers were included', () => {
  expect(publicTrack('artist', { id: 'song', title: '歌名', version: 3, hasCover: true }).cover)
    .toBe('/api/public/artists/artist/songs/song/3/cover')
})
