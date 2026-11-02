import { platform } from '../../platform'
export class ApiError extends Error { constructor(message: string, public status: number, public code?: string) { super(message) } }
let csrf = '', sessionPromise: Promise<void> | undefined
function endpoint(path: string) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  if (platform.kind !== 'web' && !base.startsWith('https://')) throw new Error('App需要配置HTTPS API地址')
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}
export async function requestJson<T>(path: string, method = 'GET', body?: unknown, timeoutMs = 10000): Promise<T> {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(endpoint(path), { method, credentials: 'same-origin', signal: controller.signal,
      headers: { Accept: 'application/json', ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(method !== 'GET' && csrf ? { 'X-XSRF-TOKEN': csrf } : {}) }, body: body !== undefined ? JSON.stringify(body) : undefined })
    const data = await response.json().catch(() => ({}))
    if (!response.ok) throw new ApiError(data.message || `请求失败（${response.status}），请检查服务状态`, response.status, data.code)
    return data as T
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new ApiError('请求超时，请检查网络后重试；不要连续重复提交。', 408)
    if (error instanceof TypeError) throw new ApiError('无法连接服务，请检查本地服务和网络连接。', 0)
    throw error
  } finally { clearTimeout(timer) }
}
export function ensureCreator(): Promise<void> {
  sessionPromise ??= (async () => { csrf = (await requestJson<{ token: string }>('public/csrf')).token; await requestJson('public/session', 'POST', {}) })().catch(error => { sessionPromise = undefined; throw error })
  return sessionPromise
}
export const getJson = <T>(path: string) => requestJson<T>(path)
export async function uploadForm<T>(path: string, body: FormData): Promise<T> {
  await ensureCreator()
  const response = await fetch(endpoint(path), { method: 'POST', credentials: 'same-origin', body, headers: { 'X-XSRF-TOKEN': csrf }, signal: AbortSignal.timeout(120000) })
  const data = await response.json().catch(() => ({}))
  if (!response.ok) throw new ApiError(data.message || `音频导入失败（${response.status}）`, response.status, data.code)
  return data as T
}
export const apiUrl = endpoint
