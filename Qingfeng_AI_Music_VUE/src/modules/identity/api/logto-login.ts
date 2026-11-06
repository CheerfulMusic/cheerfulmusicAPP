import { ensureCreator, requestJson } from '../../../shared/http/api-client'

export interface LogtoIdentityStatus {
  enabled: boolean
  authenticated: boolean
  emailEnabled?: boolean
  phoneEnabled?: boolean
  appleEnabled?: boolean
  email?: string
  phoneMasked?: string
  name?: string
}

export type LogtoLoginOption =
  | { method: 'email' | 'phone'; loginHint: string }
  | { method: 'apple' }

/** Logto credentials remain on the server; only its authorization URL reaches the browser. */
export async function beginLogtoLogin(option?: LogtoLoginOption): Promise<string> {
  await ensureCreator()
  const result = await requestJson<{ authorizationUrl: string }>('public/auth/logto/start', 'POST', option || {})
  return result.authorizationUrl
}
