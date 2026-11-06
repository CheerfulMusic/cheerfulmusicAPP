import { ApiError, apiUrl } from '../../../shared/http/api-client'

export interface InviteStatus {
  enabled: boolean
  admitted: boolean
  csrfToken: string
  expiresAt: string
}

const unavailable = () => new ApiError('暂时无法验证内测资格，请检查网络后重试。', 0)

async function readJson(response: Response): Promise<unknown> {
  const raw = await response.text()
  if (!raw.trim()) return null
  try { return JSON.parse(raw) } catch { throw unavailable() }
}

/** 门禁状态查询不经过 ensureCreator：内测未放行时引导接口本身也会被拦截。 */
export async function fetchInviteStatus(): Promise<InviteStatus> {
  let response: Response
  try {
    response = await fetch(apiUrl('public/invite/status'), {
      method: 'GET', credentials: 'same-origin', cache: 'no-store',
      headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10000)
    })
  } catch { throw unavailable() }
  const data = await readJson(response)
  if (!response.ok || !data || typeof data !== 'object') throw unavailable()
  const status = data as Partial<InviteStatus>
  if (typeof status.enabled !== 'boolean' || typeof status.admitted !== 'boolean'
      || typeof status.csrfToken !== 'string' || typeof status.expiresAt !== 'string') {
    throw unavailable()
  }
  return status as InviteStatus
}

/** 提交邀请码需要带门禁状态返回的 CSRF 令牌；成功后服务端下发 HttpOnly 通行证 Cookie。 */
export async function submitInviteCode(csrfToken: string, code: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(apiUrl('public/invite/enter'), {
      method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: {
        Accept: 'application/json', 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken
      },
      body: JSON.stringify({ code }), signal: AbortSignal.timeout(10000)
    })
  } catch { throw unavailable() }
  if (response.ok) {
    const data = await readJson(response)
    if (!data || typeof data !== 'object' || (data as { admitted?: unknown }).admitted !== true) {
      throw new ApiError('浏览器未能保存验证状态，请允许 Cookie 后重试。', response.status)
    }
    return
  }
  const data = await readJson(response)
  const failure = data && typeof data === 'object'
    ? data as { code?: unknown; message?: unknown }
    : {}
  if (failure.code === 'INVITE_RATE_LIMITED') {
    throw new ApiError('尝试次数较多，请一分钟后再试。', response.status, 'INVITE_RATE_LIMITED')
  }
  if (failure.code === 'INVITE_INVALID' || response.status === 400) {
    throw new ApiError('邀请码不正确，请检查后重试。', response.status, 'INVITE_INVALID')
  }
  throw unavailable()
}
