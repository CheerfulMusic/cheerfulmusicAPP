import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import type { Work } from '../../studio'
export interface LibraryItem {work:Work;status:'LYRICS_DRAFT'|'READY_TO_GENERATE'|'GENERATING'|'CHECKING'|'COMPLETED'|'GENERATION_FAILED';audioId:string|null;audioOrigin:'IMPORTED'|'GENERATED'|null;songTitle:string|null;coverId?:string|null}
export async function listWorks(page: number) { await ensureCreator(); return requestJson<LibraryItem[]>(`app/works/library?page=${page}`) }
export async function deleteWork(id:string,acknowledgeCompleted=false){
 await ensureCreator()
 // requestJson 同时严格校验新版确认对象和匹配 id 的旧版作品对象。
 await requestJson<{deleted:true}|Work>(`app/works/${encodeURIComponent(id)}?acknowledgeCompleted=${acknowledgeCompleted}`,'DELETE')
 return {deleted:true} as const
}
