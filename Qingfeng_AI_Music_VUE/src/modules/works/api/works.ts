import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import type { Work } from '../../studio'
export interface LibraryItem {work:Work;status:'LYRICS_DRAFT'|'READY_TO_GENERATE'|'GENERATING'|'CHECKING'|'COMPLETED'|'GENERATION_FAILED';audioId:string|null;audioOrigin:'IMPORTED'|'GENERATED'|null;songTitle:string|null;coverId?:string|null}
export async function listWorks(page: number) { await ensureCreator(); return requestJson<LibraryItem[]>(`app/works/library?page=${page}`) }
export async function deleteWork(id: string) { await ensureCreator(); return requestJson<Work>(`app/works/${id}`,'DELETE') }
