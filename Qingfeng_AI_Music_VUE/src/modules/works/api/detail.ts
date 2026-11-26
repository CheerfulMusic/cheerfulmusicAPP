import type { SongShareStatus } from '../model/song-sharing'
import type { GenerationPublication } from '../model/generation-recovery'
import type {LyricsStructure, LyricsTemplateId} from '../model/lyrics-structure'
import { ApiError, currentCreatorId, ensureCreator, requestJson, apiUrl, uploadForm } from '../../../shared/http/api-client'
export interface Revision { id: string; workId: string; workVersion: number; title: string; lyrics: string; source: string; confirmed: boolean; createdAt: string; language?: string|null; templateId?: LyricsTemplateId|null; chorusFirst?: boolean|null; customSections?: string[]|null }
export interface LyricsRequestState {status:'NOT_FOUND'|'QUEUED'|'PENDING'|'FAILED'|'SUCCEEDED'|'UNKNOWN';revisionId:string|null;errorCode:string|null}
export const getLyricsRequest = (work:string,key:string) => call<LyricsRequestState>(`app/works/${work}/lyrics/requests/${encodeURIComponent(key)}`)
export interface AudioAsset { id: string; workId: string; origin: 'IMPORTED' | 'GENERATED'; contentType: string; byteSize: number; createdAt: string; sectionEdit?: boolean; generationTaskId?: string | null; note?: string; noteVersion?: number; versionNumber?: number | null }
export interface GenerationTask { id: string; revisionId: string; requestKey?:string; status: string; title: string; style?:string; vocalGender?:string; createdAt: string; assetId: string | null; previewAssetId?: string | null; errorCode: string | null; updatedAt: string; nextAttemptAt: string }
export const closeTaskChecking=(work:string,task:string)=>call<GenerationTask>(`app/works/${work}/generations/${task}/close-checking`,'POST',{acknowledge:true})
async function call<T>(path: string, method = 'GET', body?: unknown) { await ensureCreator(); return requestJson<T>(path, method, body) }
export const listRevisions = (work: string) => call<Revision[]>(`app/works/${work}/lyrics`)
export async function generateLyrics(work: string, workVersion: number, requestKey: string, language: string, instructions = '', baseRevisionId?: string, structure?: LyricsStructure, rewriteSource?: string) {
  await ensureCreator()
  const owner = currentCreatorId()
  const rewrite = rewriteSource === undefined ? {} : { rewriteAll: true, sourceLyrics: rewriteSource }
  const initial = await requestJson<Revision | LyricsRequestState>(`app/works/${work}/lyrics/generate`, 'POST', {workVersion, requestKey, language, instructions, baseRevisionId, ...structure, ...rewrite}, 120000, true)
  return finishLyrics(owner, work, requestKey, initial)
}
export async function generatePhotoLyrics(work: string, version: number, key: string, language: string, photos: readonly File[]) {
  await ensureCreator()
  const owner = currentCreatorId()
  const form = new FormData()
  const partName = photos.length === 1 ? 'photo' : 'photos'
  for (const photo of photos) form.append(partName, photo)
  form.set('workVersion', String(version))
  form.set('requestKey', key)
  form.set('language', language)
  const initial = await uploadForm<Revision | LyricsRequestState>(`app/works/${encodeURIComponent(work)}/lyrics/generate-photo`, form, true)
  return finishLyrics(owner, work, key, initial)
}
async function finishLyrics(owner: string, work: string, key: string, initial: Revision | LyricsRequestState): Promise<Revision> {
  if (owner !== currentCreatorId()) throw new ApiError('账号已切换。', 409, 'LYRICS_REQUEST_PENDING')
  if (!('status' in initial)) return initial
  const deadline = Date.now() + 240_000
  let state = initial
  while (owner === currentCreatorId() && Date.now() < deadline) {
    if (state.status === 'SUCCEEDED' && state.revisionId) return getRevision(work, state.revisionId)
    if (state.status === 'FAILED') throw new ApiError('编写未完成，请查看本次状态。', 409, state.errorCode ?? 'LYRICS_REQUEST_FAILED')
    if (state.status === 'UNKNOWN' || state.status === 'NOT_FOUND') break
    await new Promise(resolve => setTimeout(resolve, 2_000 + Math.random() * 1_000))
    if (owner !== currentCreatorId()) break
    state = await getLyricsRequest(work, key)
  }
  throw new ApiError('歌词请求已受理，请刷新查看本次编写状态。', 409, 'LYRICS_REQUEST_PENDING')
}
export const getRevision = (work: string, revision: string) => call<Revision>(`app/works/${work}/lyrics/${revision}`)
export interface Allowance {dailyLimit:number;dailyRemaining:number;concurrentLimit:number;active:number;resetsAt:string;dailyUnlimited?:boolean;concurrentUnlimited?:boolean}
export const getUsage = () => call<{music:Allowance;lyrics:Allowance;cover?:Allowance}>('app/usage')
export interface LyricLine {text:string;start:number;end:number}
export interface PlaybackLyrics {status:'NOT_REQUESTED'|'PREPARING'|'FETCHING'|'READY'|'EMPTY'|'FAILED'|'UNKNOWN'|'REVIEW'|'CANCELLED'|'INSTRUMENTAL';text:string;lines:LyricLine[];canSync:boolean}
export const getPlaybackLyrics = (work:string,asset:string)=>call<PlaybackLyrics>(`app/works/${work}/audio/${asset}/lyrics`)
export async function syncPlaybackLyrics(work:string,asset:string,requestKey:string){await ensureCreator();return requestJson<PlaybackLyrics>(`app/works/${work}/audio/${asset}/lyrics/sync`,'POST',{requestKey},130000)}
export const saveRevision = (work: string, workVersion: number, title: string, lyrics: string, baseRevisionId?: string) => call<Revision>(`app/works/${work}/lyrics`, 'POST', { workVersion, title, lyrics, baseRevisionId })
export type LyricRewriteAction = 'REWRITE' | 'RHYME' | 'COLLOQUIAL' | 'SHORTEN'
export async function rewriteLyricLine(work:string, input:{requestId:string;action:LyricRewriteAction;line:string;context:string;language:string}) {
  await ensureCreator()
  return requestJson<{text:string}>(`app/works/${work}/lyrics/rewrite-line`,'POST',input,35000)
}
export interface LyricRhymeRequest {
  requestId: string
  line: string
  lines: string[]
  lineIndex: number
  referenceIndex?: number
  language: string
  allowNear?: boolean
  allowCrossLanguage?: boolean
  rewriteIfAlreadyRhymes?: boolean
}
export interface LyricRhymeEnding { word: string; sound: string }
export interface LyricRhymeCandidate {
  text: string
  ending: LyricRhymeEnding | null
  match: 'EXACT' | 'NEAR' | 'CROSS_LANGUAGE' | 'SUGGESTED'
  syllables?: number | null
}
export interface LyricRhymeResult {
  status: 'SUGGESTIONS' | 'CHOOSE_REFERENCE' | 'ALREADY_RHYMES' | 'NO_MATCH' | 'UNKNOWN_PRONUNCIATION'
  referenceIndex?: number | null
  reference?: LyricRhymeEnding | null
  original?: LyricRhymeEnding | null
  candidates: LyricRhymeCandidate[]
}
export async function rhymeLyricLine(work: string, input: LyricRhymeRequest) {
  await ensureCreator()
  return requestJson<LyricRhymeResult>(`app/works/${encodeURIComponent(work)}/lyrics/rhyme-line`, 'POST', input, 60000)
}
export interface LyricSelectionInput {requestId: string; workVersion: number; lyrics: string; keptLines: number[]; language: string}
export async function rewriteLyricSelection(work: string, input: LyricSelectionInput) {
  await ensureCreator()
  // One preview can include an initial 85s model call and one bounded repair.
  return requestJson<{lyrics: string}>(`app/works/${encodeURIComponent(work)}/lyrics/rewrite-selection`, 'POST', input, 190000)
}
export const confirmRevision = (work: string, revision: string) => call<Revision>(`app/works/${work}/lyrics/${revision}/confirm`, 'POST', {})
export const saveAudioNote = (work:string,asset:string,note:string,version:number) => call<AudioAsset>(`app/works/${encodeURIComponent(work)}/audio/${encodeURIComponent(asset)}/note`,'PUT',{note,version})
export const deleteAudioVersion = (work:string,asset:string) => call<{deleted:true;assetId:string}>(`app/works/${encodeURIComponent(work)}/audio/${encodeURIComponent(asset)}`,'DELETE')
export const listAudio = (work: string) => call<AudioAsset[]>(`app/works/${work}/audio`)
export const listTasks = (work: string) => call<GenerationTask[]>(`app/works/${work}/generations`)
export const submitTask = (work: string, revisionId: string, requestKey: string, publication?: GenerationPublication, useAudioReference?: boolean) => call<GenerationTask>(`app/works/${work}/generations`, 'POST', { revisionId, requestKey, ...(publication?.scope === 'public' ? { publishToDiscover: true, stageName: publication.stageName } : {}), ...(useAudioReference ? { useAudioReference: true } : {}) })
export const audioReferenceAvailable = (work:string) => call<{available:boolean}>(`app/works/${work}/generations/audio-reference`).then(result=>result.available)
export interface GenerationPreflight {workVersion:number;revisionId:string;styleCharacters:number;styleLimit:number;negativeCharacters:number;negativeLimit:number;notice:string}
export const preflightGeneration=(work:string,revisionId:string)=>call<GenerationPreflight>(`app/works/${work}/generations/preflight`,'POST',{revisionId})
export const retryTaskArchive = (work: string, task: string) => call<void>(`app/works/${work}/generations/${task}/retry-archive`, 'POST', {})
export const audioUrl = (work: string, asset: string, download = false, downloadName?: string) => {
  const path = `app/works/${work}/audio/${asset}`
  if (!download) return apiUrl(path)
  const query = new URLSearchParams({ download: 'true' })
  if (downloadName) query.set('downloadName', downloadName)
  return apiUrl(`${path}?${query}`)
}
export const createShare = (work: string, assetId: string, revisionId?: string, workTitle?: string) => call<{ id: string; token: string; songId: string; expiresAt: string }>(`app/works/${work}/shares`, 'POST', { assetId, revisionId, workTitle, publicSong: true })
const versionSharesPath = (work: string, asset: string) => `app/works/${encodeURIComponent(work)}/shares/${encodeURIComponent(asset)}`
export const getSongShareStatus = (work: string, asset: string) => call<SongShareStatus>(versionSharesPath(work, asset))
const publicStoryPath = (id: string) => 'app/song-listening/' + encodeURIComponent(id) + '/story'
export const getPublicStory = (id: string) => call<{text: string}>(publicStoryPath(id))
export const savePublicStory = (id: string, text: string) => call<{text: string}>(publicStoryPath(id), 'PUT', { text })
export const revokeSongShare = (work: string, asset: string) => call<void>(versionSharesPath(work, asset), 'DELETE')
export const revokeShares = (work: string) => call<void>(`app/works/${work}/shares`, 'DELETE')

export const cancelTask=(work:string,task:string)=>call<GenerationTask>(`app/works/${work}/generations/${task}/cancel`,'POST',{})
