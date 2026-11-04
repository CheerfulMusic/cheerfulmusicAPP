import { ensureCreator, requestJson, ApiError } from '../../../shared/http/api-client'
import type { VocalGender } from '../model/vocals'
export interface DraftInput { recipient: string; occasion: string; story: string; message: string; style: string; title: string; vocalGender:VocalGender }
export interface Work extends DraftInput { id: string; version: number; createdAt: string; updatedAt: string }
export async function loadWork(id: string) { await ensureCreator(); return requestJson<Work>(`app/works/${encodeURIComponent(id)}`) }
export async function saveWork(draft: DraftInput, existing?: Pick<Work, 'id' | 'version'>, draftKey?: string) {
  await ensureCreator()
  const saved=await (existing ? requestJson<Work>(`app/works/${encodeURIComponent(existing.id)}`, 'PUT', { ...draft, expectedVersion: existing.version }) : requestJson<Work>('app/works', 'POST', { ...draft, ...(draftKey ? {draftKey} : {}) }))
  if(saved.vocalGender===undefined&&draft.vocalGender!=='AUTO')throw new ApiError('演唱声音尚未保存，请重启后端后重试。',409,'BACKEND_UPGRADE_REQUIRED')
  return saved
}
