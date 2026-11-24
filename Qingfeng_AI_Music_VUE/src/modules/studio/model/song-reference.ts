import { getJson } from '../../../shared/http/api-client'
import type { PreviewTrack } from '../../home/model/tracks'
import type { MusicPreferences } from '../../works/api/music-settings'
import type { LyricsStructure } from '../../works/model/lyrics-structure'

export interface SongReference {
  title: string
  language: 'zh-CN' | 'en' | 'mixed'
  structure: LyricsStructure
  preferences: MusicPreferences
}

export function songReferenceId(value: unknown): string | undefined {
  return typeof value === 'string' && /^[a-z0-9-]{1,64}$/.test(value) ? value : undefined
}

export function songCreationRoute(track: Pick<PreviewTrack, 'id'>) {
  return { path: '/create', query: { mode: 'custom', reference: track.id } }
}

export function loadSongReference(id: string) {
  return getJson<SongReference>(`public/hot-songs/${encodeURIComponent(id)}/creation-reference`)
}
