import { watch } from 'vue'
import type { Router } from 'vue-router'
import { accountState, refreshAccountState } from './account-state'
import { logtoState, refreshLogtoState } from './logto-state'
import { loginDialogOpen, showLoginDialog } from './login-dialog'

const destinationKey = 'qingfeng:creation-login-destination'
const signedIn = () => accountState.value.authenticated || logtoState.value.authenticated
const creationRoute = (name: unknown) => name === 'studio' || name === 'photo-studio'

/** Gate every entry to the studio, including direct URLs and style links. */
export function installCreationLoginGate(router: Router) {
  let destination = ''
  try {
    const saved = sessionStorage.getItem(destinationKey)
    if (saved && /^\/create(?:\/photo)?(?:[?#]|$)/.test(saved)) destination = saved
  } catch { /* In-memory continuation still works without browser storage. */ }
  function remember(value: string) {
    destination = value
    try {
      if (value) sessionStorage.setItem(destinationKey, value)
      else sessionStorage.removeItem(destinationKey)
    } catch { /* Storage availability must not block login. */ }
  }
  const removeGuard = router.beforeEach(async to => {
    if (!creationRoute(to.name)) return true
    // Refresh both identity providers: a guest creator cookie alone is not a login.
    const results = await Promise.allSettled([refreshAccountState(), refreshLogtoState()])
    if (results.some(result => result.status === 'fulfilled' && result.value.authenticated)) {
      remember('')
      return true
    }
    remember(to.fullPath)
    showLoginDialog()
    return { name: 'home' }
  })
  const stop = watch([() => accountState.value.authenticated, () => logtoState.value.authenticated, loginDialogOpen], () => {
    if (signedIn() && !loginDialogOpen.value && destination) {
      const target = destination
      remember('')
      void router.push(target)
    } else if (!signedIn() && !loginDialogOpen.value) {
      remember('')
      if (creationRoute(router.currentRoute.value.name)) void router.replace({ name: 'home' })
    }
  }, { flush: 'post' })
  // OAuth can return to the homepage after a full page navigation.
  void router.isReady().then(async () => {
    if (!destination || loginDialogOpen.value) return
    const results = await Promise.allSettled([refreshAccountState(), refreshLogtoState()])
    if (results.some(result => result.status === 'fulfilled' && result.value.authenticated) && destination) {
      const target = destination
      remember('')
      await router.push(target)
    }
  })
  return () => { removeGuard(); stop() }
}
