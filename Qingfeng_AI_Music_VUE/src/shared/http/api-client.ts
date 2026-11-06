import { migrateRecoveryOwner } from '../drafts/recovery'
import { platform } from '../../platform'
import {emptyResponseAllowed,validResponse} from './response-contracts'
export class ApiError extends Error { constructor(message: string, public status: number, public code?: string, public retryAfter?: number) { super(message) } }
let csrf = '', sessionPromise: Promise<void> | undefined
let creatorId = '', associatedGuest = ''
export const associatedGuestCreatorId = () => associatedGuest
export const currentCreatorId = () => creatorId
export function lastKnownCreatorId() { try { return sessionStorage.getItem('qingfeng.lastCreator') || '' } catch { return '' } }
export function resetCreatorSession() { sessionPromise = undefined; creatorId = ''; associatedGuest = ''; try {sessionStorage.removeItem('qingfeng.lastCreator')} catch {/* 恢复身份不依赖存储。 */} }
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
    return await decodeResponse<T>(response,path,method)
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new ApiError('请求超时，请检查网络后重试；不要连续重复提交。', 408)
    if (error instanceof TypeError) throw new ApiError('无法连接服务，请检查本地服务和网络连接。', 0)
    throw error
  } finally { clearTimeout(timer) }
}
export function ensureCreator(): Promise<void> {
  sessionPromise ??= (async () => { csrf = (await requestJson<{ token: string }>('public/csrf')).token; const session = await requestJson<{creatorId:string;associatedGuestCreatorId?:string}>('public/session', 'POST', {});
    if(session.associatedGuestCreatorId && !migrateRecoveryOwner(session.associatedGuestCreatorId,session.creatorId))throw new ApiError('本机草稿备份失败，请保留当前编辑，恢复浏览器存储后重试。',0,'LOCAL_DRAFT_MIGRATION_FAILED');
    creatorId = session.creatorId; associatedGuest = session.associatedGuestCreatorId || '';try{sessionStorage.setItem('qingfeng.lastCreator',creatorId)}catch{/* 身份依赖服务器 Cookie，不依赖这个本机恢复提示。 */} })().catch(error => { sessionPromise = undefined; throw error })
  return sessionPromise
}
export const getJson = <T>(path: string) => requestJson<T>(path)
async function decodeResponse<T>(response:Response,path:string,method:string):Promise<T>{
  const raw=await response.text()
  let data:unknown
  try{data=raw.trim()?JSON.parse(raw):undefined}catch{
    if(!response.ok)throw new ApiError(`请求失败（${response.status}），请检查服务状态`,response.status)
    throw invalidResponse()
  }
  if(!response.ok){
    const failure=data&&typeof data==='object'?data as {message?:unknown;code?:unknown}:{}
    const code=typeof failure.code==='string'?failure.code:undefined
    if(code==='INVITE_REQUIRED'&&typeof window!=='undefined')window.dispatchEvent(new Event('qingfeng:invite-required'))
    throw new ApiError(typeof failure.message==='string'?failure.message:`请求失败（${response.status}），请检查服务状态`,response.status,code,retryAfterSeconds(response.headers.get('Retry-After')))
  }
  if(!raw.trim()&&!emptyResponseAllowed(path,method)||!validResponse(path,method,data))throw invalidResponse()
  return data as T
}
function invalidResponse(){return new ApiError('服务响应不完整，操作结果尚未确认，请先刷新核对，不要重复提交。',0,'INVALID_RESPONSE')}
export async function uploadForm<T>(path: string, body: FormData): Promise<T> {
  await ensureCreator()
  try{
    const response = await fetch(endpoint(path), { method: 'POST', credentials: 'same-origin', body, headers: { 'X-XSRF-TOKEN': csrf }, signal: AbortSignal.timeout(120000) })
    return await decodeResponse<T>(response,path,'POST')
  }catch(error){
    if(error instanceof Error&&['AbortError','TimeoutError'].includes(error.name))throw new ApiError('请求超时，请检查网络后重试；不要连续重复提交。',408)
    if(error instanceof TypeError)throw new ApiError('无法连接服务，请检查本地服务和网络连接。',0)
    throw error
  }
}
export const apiUrl = endpoint

export function retryAfterSeconds(value: string | null): number | undefined {
  if (!value) return undefined
  const seconds = /^\d+$/.test(value) ? Number(value) : Math.ceil((Date.parse(value) - Date.now()) / 1000)
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined
}
