import { readonly, ref } from 'vue'
import type { LogtoIdentityStatus } from '../../modules/identity/api/logto-login'
import { requestJson, resetCreatorSession } from '../http/api-client'
import { usePlayer } from '../player/store'

const status = ref<LogtoIdentityStatus>({ enabled: false, authenticated: false })
let loaded = false
let generation = 0
let pending: Promise<LogtoIdentityStatus> | undefined
export const logtoState = readonly(status)

export function resetLogtoState() {
  generation++
  status.value = { enabled: status.value.enabled, authenticated: false,
    emailEnabled: status.value.emailEnabled, phoneEnabled: status.value.phoneEnabled, appleEnabled: status.value.appleEnabled }
  loaded = true
  pending = undefined
}

export function refreshLogtoState(): Promise<LogtoIdentityStatus> {
  if (pending) return pending
  const version = generation
  const request = requestJson<LogtoIdentityStatus>('public/auth/logto/status').then(next => {
    if (version !== generation) return status.value
    const changed = loaded && (status.value.authenticated !== next.authenticated ||
      status.value.authenticated && next.authenticated && (status.value.email !== next.email || status.value.phoneMasked !== next.phoneMasked || status.value.name !== next.name))
    if (changed) { resetCreatorSession(); usePlayer().reset() }
    status.value = next
    loaded = true
    return next
  }).finally(() => { if (pending === request) pending = undefined })
  pending = request
  return request
}
