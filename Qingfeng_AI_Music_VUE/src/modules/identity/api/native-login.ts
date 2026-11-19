import { startSignedInPageTracking } from '../../../shared/analytics/track'
import { ensureCreator, requestJson } from '../../../shared/http/api-client'

export type CodePurpose = 'register' | 'reset' | 'sign-in'
export type CodeChannel = 'email' | 'phone'
export interface NativeLoginStatus {
  emailPasswordEnabled: boolean
  passwordResetEnabled: boolean
  emailCodeEnabled: boolean
  phoneEnabled: boolean
  phoneInternationalEnabled: boolean
  phoneAllowedRegions?: string[]
  authenticated: boolean
  email?: string
  phoneMasked?: string
  name?: string
}
export const readNativeLoginStatus = () => requestJson<NativeLoginStatus>('public/auth/native/status')

async function post<T>(path: string, body: unknown): Promise<T> {
  await ensureCreator()
  return requestJson<T>(`public/auth/native/${path}`, 'POST', body, 30000)
}
async function signIn(path: string, body: unknown): Promise<{ authenticated: true }> {
  const result = await post<{ authenticated: true }>(path, body)
  startSignedInPageTracking()
  return result
}

export const sendLoginCode = (channel: CodeChannel, identifier: string, purpose: CodePurpose) =>
  post<{ sent: true; resendAfter: number }>('code', { channel, identifier, purpose })
export const signInWithEmail = (email: string, password: string) =>
  signIn('password/sign-in', { email, password })
export const signInWithEmailCode = (email: string, code: string) =>
  signIn('email/sign-in', { email, code })
export const registerWithEmail = (email: string, password: string, code: string) =>
  signIn('register', { email, password, code })
export const resetEmailPassword = (email: string, password: string, code: string) =>
  post<{ reset: true }>('password/reset', { email, password, code })
export const signInWithPhone = (phone: string, code: string) =>
  signIn('phone/sign-in', { phone, code })
