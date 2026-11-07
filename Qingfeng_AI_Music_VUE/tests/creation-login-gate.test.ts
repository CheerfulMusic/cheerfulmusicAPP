import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

const state = vi.hoisted(() => ({ google: false, logto: false, fail: false }))
vi.mock('../src/shared/identity/account-state', async () => {
  const { ref } = await import('vue')
  const accountState = ref({ authenticated: false })
  return { accountState, refreshAccountState: vi.fn(async () => {
    if (state.fail) throw Error('unavailable')
    return accountState.value = { authenticated: state.google }
  }) }
})
vi.mock('../src/shared/identity/logto-state', async () => {
  const { ref } = await import('vue')
  const logtoState = ref({ authenticated: false })
  return { logtoState, refreshLogtoState: vi.fn(async () => {
    if (state.fail) throw Error('unavailable')
    return logtoState.value = { authenticated: state.logto }
  }) }
})
import { refreshAccountState } from '../src/shared/identity/account-state'
import { refreshLogtoState } from '../src/shared/identity/logto-state'
import { hideLoginDialog, loginDialogOpen } from '../src/shared/identity/login-dialog'
import { installCreationLoginGate } from '../src/shared/identity/creation-login-gate'

const stops: (() => void)[] = []
beforeEach(async () => {
  state.google = state.logto = state.fail = false
  await Promise.all([refreshAccountState(), refreshLogtoState()])
  hideLoginDialog()
  const data = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => data.get(key) ?? null, setItem: (key: string, value: string) => data.set(key, value), removeItem: (key: string) => data.delete(key) })
})
afterEach(() => { stops.splice(0).forEach(stop => stop()); vi.unstubAllGlobals() })
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', name: 'home', component: {} },
    { path: '/create', name: 'studio', component: {} },
    { path: '/gift/:token', name: 'gift', component: {} },
  ] })
  stops.push(installCreationLoginGate(router))
  await router.push('/'); await router.isReady()
  return router
}
const settle = async () => { await nextTick(); await new Promise(resolve => setTimeout(resolve, 20)) }
describe('creation requires account login', () => {
  it('keeps browsing public and blocks a guest studio URL before mounting it', async () => {
    const router = await setup()
    await router.push('/gift/public-song')
    expect(loginDialogOpen.value).toBe(false)
    await router.push('/create?style=pop')
    expect(router.currentRoute.value.name).toBe('home')
    expect(loginDialogOpen.value).toBe(true)
    expect(sessionStorage.getItem('qingfeng:creation-login-destination')).toBe('/create?style=pop')
  })
  it.each(['google', 'logto'] as const)('allows a confirmed %s account', async provider => {
    const router = await setup(); state[provider] = true
    await router.push('/create')
    expect(router.currentRoute.value.name).toBe('studio')
    expect(loginDialogOpen.value).toBe(false)
  })
  it('resumes the selected style after login and the dialog closes', async () => {
    const router = await setup(); await router.push('/create?style=jazz')
    state.logto = true; await refreshLogtoState(); await settle()
    expect(router.currentRoute.value.name).toBe('home')
    hideLoginDialog(); await settle()
    expect(router.currentRoute.value.fullPath).toBe('/create?style=jazz')
  })
  it('cancels continuation when login is dismissed', async () => {
    const router = await setup(); await router.push('/create')
    hideLoginDialog(); await settle()
    state.google = true; await refreshAccountState(); await settle()
    expect(router.currentRoute.value.name).toBe('home')
  })
  it('does not allow creation when identity checks fail', async () => {
    const router = await setup(); state.fail = true
    await router.push('/create')
    expect(router.currentRoute.value.name).toBe('home')
    expect(loginDialogOpen.value).toBe(true)
  })
  it('restores the intended destination after an OAuth page reload', async () => {
    sessionStorage.setItem('qingfeng:creation-login-destination', '/create?occasion=birthday')
    state.google = true
    const router = await setup(); await settle()
    expect(router.currentRoute.value.fullPath).toBe('/create?occasion=birthday')
  })
  it('leaves the studio when the account logs out', async () => {
    const router = await setup(); state.google = true; await router.push('/create')
    state.google = false; await refreshAccountState(); await settle()
    expect(router.currentRoute.value.name).toBe('home')
  })
})
