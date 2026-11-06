import { apiUrl } from '../../shared/http/api-client'

export type InviteStatus = { enabled: boolean; admitted: boolean; csrfToken: string; expiresAt: string }
export class InviteError extends Error {
  constructor(public code: string) { super(code) }
}
async function call(path: string, csrfToken?: string, code?: string): Promise<Record<string, unknown>> {
  try {
    const response = await fetch(apiUrl('public/invite/' + path), {
      method: code === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(10000),
      headers: { Accept: 'application/json', ...(code === undefined ? {} : { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken! }) },
      ...(code === undefined ? {} : { body: JSON.stringify({ code }) }),
    })
    const raw = await response.text()
    let value: unknown
    try { value = JSON.parse(raw) } catch { throw new InviteError('UNAVAILABLE') }
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new InviteError('UNAVAILABLE')
    const data = value as Record<string, unknown>
    if (!response.ok) throw new InviteError(response.status === 429 ? 'RATE_LIMITED' : data.code === 'INVITE_INVALID' ? 'INVALID' : 'UNAVAILABLE')
    return data
  } catch (error) { throw error instanceof InviteError ? error : new InviteError('UNAVAILABLE') }
}
export async function readInviteStatus(): Promise<InviteStatus> {
  const data = await call('status')
  if (typeof data.enabled !== 'boolean' || typeof data.admitted !== 'boolean' || typeof data.csrfToken !== 'string' || typeof data.expiresAt !== 'string'
    || !data.enabled && !data.admitted || data.enabled && !data.csrfToken || data.enabled && data.admitted && !Number.isFinite(Date.parse(data.expiresAt))) throw new InviteError('UNAVAILABLE')
  return data as InviteStatus
}
export async function enterInvite(code: string, csrfToken: string): Promise<void> {
  const data = await call('enter', csrfToken, code.trim())
  if (data.admitted !== true) throw new InviteError('UNAVAILABLE')
}
