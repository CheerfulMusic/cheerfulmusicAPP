import { ensureCreator, requestJson } from '../../../shared/http/api-client'
import type { Work } from '../../studio'
export async function listWorks(page: number) { await ensureCreator(); return requestJson<Work[]>(`app/works?page=${page}`) }
