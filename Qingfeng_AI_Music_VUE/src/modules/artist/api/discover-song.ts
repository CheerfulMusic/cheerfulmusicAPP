import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import { discoverHref } from '../../../shared/i18n'
import { siteOrigin } from '../../../shared/i18n/home-seo'
import type { DiscoverSong, PublishedSong } from './artist'

export interface DiscoverLikes { count: number; liked: boolean }
export interface DiscoverComment { id: string; author: string; text: string; createdAt: string; removable: boolean }
export interface DiscoverComments { items: DiscoverComment[]; nextCursor: string | null }
export interface DiscoverSongDetail {
  release: DiscoverSong
  durationSeconds: number | null
  lyrics: string
  description: string
  signedIn: boolean
  communityAvailable?: boolean
  canEditDescription: boolean
  likes: DiscoverLikes
  commentCount: number
  comments: DiscoverComments
}

const versionPath = (song: Pick<PublishedSong, 'id' | 'version'>) => `${encodeURIComponent(song.id)}/${song.version}`
export const discoverSongHref = (song: Pick<PublishedSong, 'id' | 'version'>) => `${discoverHref.value}/songs/${versionPath(song)}`
const readPath = (song: Pick<PublishedSong, 'id' | 'version'>) => `public/discover/songs/${versionPath(song)}`
export function discoverThumbnailUrl(song: PublishedSong) {
  const url = new URL(`/api/${readPath(song)}/thumbnail.png`, siteOrigin)
  if (song.coverVersion) url.searchParams.set('v', song.coverVersion)
  return url.href
}
const writePath = (song: PublishedSong) => `app/discover/songs/${versionPath(song)}`

export const getDiscoverSong = (id: string, version: number) => requestJson<DiscoverSongDetail>(readPath({ id, version }))
export const getDiscoverComments = (song: PublishedSong, cursor: string) =>
  requestJson<DiscoverComments>(`${readPath(song)}/comments?${new URLSearchParams({ after: cursor })}`)

export async function setDiscoverLike(song: PublishedSong, active: boolean) {
  await ensureCreator()
  return requestJson<DiscoverLikes>(`${writePath(song)}/like`, 'PUT', { active })
}

export async function postDiscoverComment(song: PublishedSong, id: string, text: string) {
  await ensureCreator()
  return requestJson<{ comment: DiscoverComment; count: number }>(`${writePath(song)}/comments/${id}`, 'PUT', { text })
}

export async function removeDiscoverComment(song: PublishedSong, id: string) {
  await ensureCreator()
  return requestJson<{ count: number }>(`${writePath(song)}/comments/${id}`, 'DELETE')
}

export async function setDiscoverDescription(song: PublishedSong, text: string) {
  await ensureCreator()
  return requestJson<{ text: string }>(`${writePath(song)}/description`, 'PUT', { text })
}
