import { ensureCreator, requestJson } from '../../../shared/http/api-client'

export interface GoogleIdentityStatus {
  enabled: boolean
  authenticated: boolean
  email?: string
  name?: string
}

export function readGoogleIdentityStatus(): Promise<GoogleIdentityStatus> {
  return requestJson<GoogleIdentityStatus>('public/auth/google/status')
}

/** Start only after an explicit button press, with the existing creator and CSRF token. */
export async function beginGoogleLogin(): Promise<string> {
  await ensureCreator()
  const result = await requestJson<{ authorizationUrl: string }>('public/auth/google/start', 'POST', {})
  return result.authorizationUrl
}
