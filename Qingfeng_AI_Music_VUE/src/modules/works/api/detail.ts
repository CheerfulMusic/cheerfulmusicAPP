import { ensureCreator, requestJson, uploadForm, apiUrl } from '../../../shared/http/api-client'
export interface Revision { id: string; workId: string; workVersion: number; title: string; lyrics: string; source: string; confirmed: boolean; createdAt: string }
export interface LyricsRequestState {status:'NOT_FOUND'|'PENDING'|'FAILED'|'SUCCEEDED'|'UNKNOWN';revisionId:string|null;errorCode:string|null}
export const getLyricsRequest = (work:string,key:string) => call<LyricsRequestState>(`app/works/${work}/lyrics/requests/${encodeURIComponent(key)}`)
export interface AudioAsset { id: string; workId: string; origin: 'IMPORTED' | 'GENERATED'; contentType: string; byteSize: number; createdAt: string;sectionEdit?:boolean }
export interface GenerationTask { id: string; revisionId: string; requestKey?:string; status: string; title: string; style?:string; vocalGender?:string; createdAt: string; assetId: string | null; errorCode: string | null; updatedAt: string; nextAttemptAt: string }
export const closeTaskChecking=(work:string,task:string)=>call<GenerationTask>(`app/works/${work}/generations/${task}/close-checking`,'POST',{acknowledge:true})
async function call<T>(path: string, method = 'GET', body?: unknown) { await ensureCreator(); return requestJson<T>(path, method, body) }
export const listRevisions = (work: string) => call<Revision[]>(`app/works/${work}/lyrics`)
export async function generateLyrics(work: string, workVersion: number, requestKey: string, language: string, instructions = '', baseRevisionId?: string) {
  await ensureCreator()
  return requestJson<Revision>(`app/works/${work}/lyrics/generate`, 'POST', {workVersion, requestKey, language, instructions, baseRevisionId}, 120000)
}
export const getRevision = (work: string, revision: string) => call<Revision>(`app/works/${work}/lyrics/${revision}`)
export interface Allowance {dailyLimit:number;dailyRemaining:number;concurrentLimit:number;active:number;resetsAt:string;dailyUnlimited?:boolean;concurrentUnlimited?:boolean}
export const getUsage = () => call<{music:Allowance;lyrics:Allowance;cover?:Allowance}>('app/usage')
export interface LyricLine {text:string;start:number;end:number}
export interface PlaybackLyrics {status:'NOT_REQUESTED'|'FETCHING'|'READY'|'EMPTY'|'FAILED'|'UNKNOWN';text:string;lines:LyricLine[];canSync:boolean}
export const getPlaybackLyrics = (work:string,asset:string)=>call<PlaybackLyrics>(`app/works/${work}/audio/${asset}/lyrics`)
export async function syncPlaybackLyrics(work:string,asset:string,requestKey:string){await ensureCreator();return requestJson<PlaybackLyrics>(`app/works/${work}/audio/${asset}/lyrics/sync`,'POST',{requestKey},130000)}
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

export const cancelTask=(work:string,task:string)=>call<GenerationTask>(`app/works/${work}/generations/${task}/cancel`,'POST',{})
