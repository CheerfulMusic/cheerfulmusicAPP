import { apiUrl, ensureCreator, requestJson, uploadForm } from '../../../shared/http/api-client'
import { platform } from '../../../platform'
import { audioUrl } from '../../works/api/detail'
import { coverUrl } from '../../works/api/covers'

export interface PublishedSong {
  id: string
  version: number
  title: string
  hasCover: boolean
}

export interface ArtistRelease extends Omit<PublishedSong, 'hasCover'> {
  workId: string
  assetId: string
  coverId: string | null
}

export interface ArtistProfile {
  publicId: string | null
  stageName: string
  tagline: string
  avatarKey: string | null
  published: boolean
  songs: ArtistRelease[]
}

export interface PublicArtist {
  stageName: string
  tagline: string
  avatarKey: string | null
  songs: PublishedSong[]
}

export interface SongPublication {
  workId: string
  assetId: string
  coverId: string | null
  title: string
}

interface ReleaseSelection {
  profileReady: boolean
  published: boolean
  song: ArtistRelease | null
}

const mine = 'app/artist-profile'
const publicArtist = (id: string) => `public/artists/${encodeURIComponent(id)}`
const release = (work: string) => `${mine}/releases/${encodeURIComponent(work)}`

export function artistHomeUrl(id: string) {
  const origin = platform.kind === 'web' ? window.location.origin : new URL(apiUrl('public/system/info')).origin
  return new URL(`/artist/${encodeURIComponent(id)}`, origin).href
}

export async function getArtistProfile() {
  await ensureCreator()
  return requestJson<ArtistProfile>(mine)
}

export function saveArtistProfile(stageName: string, tagline: string, avatar?: File, removeAvatar = false) {
  const form = new FormData()
  form.set('stageName', stageName)
  form.set('tagline', tagline)
  form.set('removeAvatar', String(removeAvatar))
  if (avatar) form.set('avatar', avatar)
  return uploadForm<ArtistProfile>(mine, form)
}

export async function setArtistVisibility(published: boolean) {
  await ensureCreator()
  return requestJson<ArtistProfile>(`${mine}/visibility`, 'PUT', { published })
}

export async function getSongPublication(work: string) {
  await ensureCreator()
  return requestJson<ReleaseSelection>(release(work))
}

export async function publishSong({ workId, ...song }: SongPublication) {
  await ensureCreator()
  return requestJson<ArtistRelease>(release(workId), 'PUT', song)
}

export async function removeSongFromArtist(work: string) {
  await ensureCreator()
  return requestJson<ArtistProfile>(release(work), 'DELETE')
}

export const getPublicArtist = (id: string) => requestJson<PublicArtist>(publicArtist(id))
export const ownerAvatarUrl = (key: string) => apiUrl(`${mine}/avatar/${encodeURIComponent(key)}`)
export const publicAvatarUrl = (id: string, key: string) => apiUrl(`${publicArtist(id)}/avatar/${encodeURIComponent(key)}`)

export function publicTrack(id: string, song: PublishedSong) {
  const path = `${publicArtist(id)}/songs/${encodeURIComponent(song.id)}/${song.version}`
  return { id: song.id, title: song.title, cover: song.hasCover ? apiUrl(`${path}/cover`) : '/brand/share-default-cover.png',
    audio: apiUrl(`${path}/audio`) }
}

export function ownerTrack(song: ArtistRelease) {
  return { id: song.assetId, title: song.title, cover: coverUrl(song.workId, song.coverId),
    audio: audioUrl(song.workId, song.assetId) }
}
