import { ensureCreator, requestJson, uploadForm, apiUrl } from '../../../shared/http/api-client'
export interface Revision { id: string; workId: string; workVersion: number; title: string; lyrics: string; source: string; confirmed: boolean; createdAt: string }
export interface AudioAsset { id: string; workId: string; origin: 'IMPORTED' | 'GENERATED'; contentType: string; byteSize: number; createdAt: string }
export interface GenerationTask { id: string; revisionId: string; status: string; title: string; createdAt: string; assetId: string | null; errorCode: string | null; updatedAt: string; nextAttemptAt: string }
async function call<T>(path: string, method = 'GET', body?: unknown) { await ensureCreator(); return requestJson<T>(path, method, body) }
export const listRevisions = (work: string) => call<Revision[]>(`app/works/${work}/lyrics`)
export async function generateLyrics(work: string, workVersion: number, requestKey: string, language: string) {
  await ensureCreator()
  return requestJson<Revision>(`app/works/${work}/lyrics/generate`, 'POST', {workVersion, requestKey, language}, 120000)
}
export const saveRevision = (work: string, workVersion: number, title: string, lyrics: string) => call<Revision>(`app/works/${work}/lyrics`, 'POST', { workVersion, title, lyrics })
export const confirmRevision = (work: string, revision: string) => call<Revision>(`app/works/${work}/lyrics/${revision}/confirm`, 'POST', {})
export const listAudio = (work: string) => call<AudioAsset[]>(`app/works/${work}/audio`)
export const listTasks = (work: string) => call<GenerationTask[]>(`app/works/${work}/generations`)
export const submitTask = (work: string, revisionId: string, requestKey: string) => call<GenerationTask>(`app/works/${work}/generations`, 'POST', { revisionId, requestKey })
export const retryTaskArchive = (work: string, task: string) => call<void>(`app/works/${work}/generations/${task}/retry-archive`, 'POST', {})
export function importAudio(work: string, file: File) { const form = new FormData(); form.append('file', file); return uploadForm<AudioAsset>(`app/works/${work}/audio`, form) }
export const audioUrl = (work: string, asset: string, download = false) => apiUrl(`app/works/${work}/audio/${asset}${download ? '?download=true' : ''}`)
export const createShare = (work: string, revisionId: string, assetId: string) => call<{ id: string; token: string; expiresAt: string }>(`app/works/${work}/shares`, 'POST', { revisionId, assetId })
export const revokeShares = (work: string) => call<void>(`app/works/${work}/shares`, 'DELETE')
