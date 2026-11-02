import { ensureCreator, requestJson } from '../../../shared/http/api-client'
export interface DraftInput { recipient: string; occasion: string; story: string; message: string; style: string; title: string }
export interface Work extends DraftInput { id: string; version: number; createdAt: string; updatedAt: string }
export async function loadWork(id: string) { await ensureCreator(); return requestJson<Work>(`app/works/${encodeURIComponent(id)}`) }
export async function saveWork(draft: DraftInput, existing?: Pick<Work, 'id' | 'version'>) {
  await ensureCreator()
  return existing ? requestJson<Work>(`app/works/${encodeURIComponent(existing.id)}`, 'PUT', { ...draft, expectedVersion: existing.version }) : requestJson<Work>('app/works', 'POST', draft)
}
