import { readonly, ref } from 'vue'
import type { GoogleIdentityStatus } from '../../modules/identity/api/google-login'
import { ensureCreator, requestJson, resetCreatorSession } from '../http/api-client'
import { usePlayer } from '../player/store'

const status = ref<GoogleIdentityStatus>({ enabled: false, authenticated: false })
const loading = ref(false), loggingOut = ref(false)
let loaded = false, generation = 0
let pending: Promise<GoogleIdentityStatus> | undefined, logoutPending: Promise<void> | undefined

export const accountState = readonly(status)
export const accountLoading = readonly(loading)
export const accountLoggingOut = readonly(loggingOut)

/** All navigation surfaces share this request and the last confirmed identity. */
export function refreshAccountState(): Promise<GoogleIdentityStatus> {
  if (pending) return pending
  const version = generation
  loading.value = true
  const request = requestJson<GoogleIdentityStatus>('public/auth/google/status').then(next => {
    if (version !== generation) return status.value
    const identityChanged = loaded && (status.value.authenticated !== next.authenticated || status.value.authenticated && next.authenticated && (status.value.email !== next.email || status.value.name !== next.name))
    if (identityChanged) {
      resetCreatorSession()
      usePlayer().reset()
    }
    status.value = next
    loaded = true
    return next
  }).finally(() => {
    if (pending === request) { pending = undefined; loading.value = false }
  })
  pending = request
  return request
}

export function ensureAccountState(): Promise<GoogleIdentityStatus> {
  return loaded ? Promise.resolve(status.value) : refreshAccountState()
}

/** Navigation is the caller's choice; never clear the local identity before confirmed logout. */
export function logoutAccount(): Promise<void> {
  if (logoutPending) return logoutPending
  const player = usePlayer()
  loggingOut.value = true
  const request = (async () => {
    await ensureCreator()
    await requestJson<{ loggedOut: true }>('public/session/logout', 'POST', {})
    generation++
    pending = undefined
    resetCreatorSession()
    player.reset()
    status.value = { enabled: status.value.enabled, authenticated: false }
    loaded = true
    // Logout is already confirmed. A failed follow-up read must not restore stale account data.
    await refreshAccountState().catch(() => undefined)
  })().finally(() => { if (logoutPending === request) { logoutPending = undefined; loggingOut.value = false } })
  logoutPending = request
  return request
}
