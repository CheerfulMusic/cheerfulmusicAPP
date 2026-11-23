import { apiUrl, ensureCreator, requestJson, uploadForm } from '../../../shared/http/api-client'
import { publicTrack, type PublishedSong } from './artist'
export interface Album {
  id: string; title: string; description: string; coverKey: string | null
  published: boolean; version: number; tracks: PublishedSong[]
}
const mine = 'app/artist-albums'
const catalogue = (artist: string) => 'public/artists/' + encodeURIComponent(artist) + '/albums'
export const albumPath = (artist: string, album: string) => '/artist/' + encodeURIComponent(artist) + '/albums/' + encodeURIComponent(album)
export const artistAlbums = (artist: string) => requestJson<Album[]>(catalogue(artist))
export const publicAlbum = (artist: string, id: string) => requestJson<Album>(catalogue(artist) + '/' + encodeURIComponent(id))
export const ownerAlbumCover = (album: Album) => apiUrl(mine + '/' + album.id + '/cover/' + album.coverKey)
export function albumCover(artist: string, album: Album) {
  if (album.coverKey) return apiUrl(catalogue(artist) + '/' + album.id + '/cover/' + album.coverKey)
  const first = album.tracks.find(track => track.hasCover)
  return first ? publicTrack(artist, first).cover : '/brand/share-default-cover.png'
}
export async function ownAlbums() { await ensureCreator(); return requestJson<Album[]>(mine) }
export const saveAlbum = (id: string, data: FormData) => uploadForm<Album>(mine + '/' + encodeURIComponent(id), data)
export async function deleteAlbum(album: Album) {
  await ensureCreator()
  return requestJson<void>(mine + '/' + encodeURIComponent(album.id) + '?version=' + album.version, 'DELETE')
}
