<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { t } from '../i18n'
import { accountLoggingOut, accountState, logoutAccount } from '../identity/account-state'
import { logtoState } from '../identity/logto-state'
import { maskedPhoneLabel } from '../identity/phone-label'
import { showLoginDialog } from '../identity/login-dialog'
import NavIcon from './NavIcon.vue'

const props = defineProps<{ navigationOpen?: boolean }>()
const emit = defineEmits<{ opened: [] }>()
const route = useRoute()
const root = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>(), panel = ref<HTMLElement>()
const open = ref(false), error = ref('')
const authenticated = computed(() => accountState.value.authenticated || logtoState.value.authenticated)
const contact = computed(() => logtoState.value.authenticated
  ? logtoState.value.email || maskedPhoneLabel(logtoState.value.phoneMasked)
  : accountState.value.email)
const displayName = computed(() => (logtoState.value.authenticated ? logtoState.value.name?.trim() : accountState.value.name?.trim()) || contact.value?.split('@')[0] || t('我的账号'))
const initials = computed(() => displayName.value.split(/\s+/).slice(0, 2).map(part => Array.from(part)[0]).join('').toLocaleUpperCase())

async function close(returnFocus = false) {
  open.value = false
  if (returnFocus) { await nextTick(); trigger.value?.focus() }
}
async function toggle() {
  if (open.value) { await close(); return }
  error.value = ''; open.value = true; emit('opened')
  await nextTick(); panel.value?.querySelector<HTMLElement>('a')?.focus()
}
function outside(event: PointerEvent) { if (!root.value?.contains(event.target as Node)) void close() }
function focusOut(event: FocusEvent) { if (!root.value?.contains(event.relatedTarget as Node | null)) void close() }
function keyboard(event: KeyboardEvent) {
  if (!open.value) return
  if (event.key === 'Escape') { event.preventDefault(); void close(true); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = Array.from(panel.value?.querySelectorAll<HTMLElement>('a,button:not([disabled])') || [])
  if (!items.length) return
  event.preventDefault()
  const index = items.indexOf(document.activeElement as HTMLElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length
  items[next]?.focus()
}
async function signOut() {
  error.value = ''
  try { await logoutAccount(); window.location.replace('/account?session=signed-out') }
  catch { error.value = '暂时无法退出登录，请稍后重试。' }
}
watch(() => route.fullPath, () => { void close() })
watch(() => props.navigationOpen, value => { if (value) void close() })
watch(authenticated, value => { if (!value) void close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
  <div ref="root" class="user-account" @focusout="focusOut" @keydown="keyboard">
    <button v-if="!authenticated" type="button" class="account-login" :class="{ 'is-active': route.name === 'identity' }" :aria-label="t('登录 / 账号')" @click="showLoginDialog">
      <NavIcon name="account" /><span class="account-login-full">{{ t('登录 / 账号') }}</span><span class="account-login-short">{{ t('账号') }}</span>
    </button>
    <template v-else>
      <button ref="trigger" type="button" class="account-trigger" :class="{ 'is-active': route.name === 'identity' }" :aria-label="t('账号与登录')" :aria-expanded="open" aria-controls="user-account-panel" @click="toggle" @keydown.down.prevent="!open && toggle()">
        <span class="account-trigger-avatar" aria-hidden="true">{{ initials }}</span><span class="account-trigger-name">{{ displayName }}</span><svg class="account-chevron" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m4 6 4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      <div v-if="open" id="user-account-panel" ref="panel" class="account-popover">
        <div class="account-popover-identity"><strong>{{ displayName }}</strong><span v-if="contact">{{ contact }}</span><small>{{ t(logtoState.authenticated ? 'Logto 账号' : 'Google 账号') }}</small></div>
        <nav :aria-label="t('账号菜单')">
          <RouterLink to="/account"><NavIcon name="account" />{{ t('账号与登录') }}</RouterLink>
          <RouterLink to="/works"><NavIcon name="music" />{{ t('我的音乐') }}</RouterLink>
          <button type="button" class="account-sign-out" :disabled="accountLoggingOut" @click="signOut"><NavIcon name="logout" />{{ accountLoggingOut ? t('正在处理…') : t('退出登录') }}</button>
        </nav>
        <p v-if="error" class="account-menu-error" role="alert">{{ t(error) }}</p>
      </div>
    </template>
  </div>
</template>
<style scoped>
.user-account { position: relative; flex: 0 0 auto; }
.account-login, .account-trigger { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 40px; padding: 6px 10px; border: 1px solid #333944; border-radius: 24px; background: #14171d; color: #d7dce5; font-size: 12px; white-space: nowrap; }
.account-login:hover, .account-trigger:hover, .account-login.is-active, .account-trigger.is-active { color: var(--ink); background: #20242c; border-color: #616976; }
.account-login > svg { width: 17px; height: 17px; }
.account-login-short { display: none; }
.account-trigger { padding: 4px 9px 4px 4px; }
.account-trigger-avatar { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 auto; border: 1px solid #f294784f; border-radius: 50%; background: linear-gradient(145deg, #ff987b, #b7352b); color: #fff3e9; font-size: 11px; font-weight: 650; }
.account-trigger-name { max-width: 95px; overflow: hidden; text-overflow: ellipsis; }
.account-chevron { width: 13px; height: 13px; flex: 0 0 auto; color: #99a4b3; }
.account-popover { position: absolute; z-index: 100; right: 0; top: calc(100% + 12px); width: 260px; max-width: calc(100vw - 32px); max-height: min(420px, calc(100svh - 100px)); overflow-y: auto; padding: 9px; border: 1px solid #363e4b; border-radius: 15px; background: #14171dfc; box-shadow: 0 16px 45px #0008; }
.account-popover-identity { display: grid; gap: 4px; padding: 10px 12px 13px; margin-bottom: 6px; border-bottom: 1px solid #2d333e; min-width: 0; }
.account-popover-identity strong { color: #edf0f5; font-size: 13px; overflow-wrap: anywhere; }
.account-popover-identity span { color: #a3aebb; font-size: 11px; overflow-wrap: anywhere; }
.account-popover-identity small { color: #848e9d; font-size: 10px; }
.account-popover nav { display: grid; gap: 3px; }
.account-popover nav a, .account-popover nav button { display: flex; align-items: center; gap: 11px; width: 100%; min-height: 42px; padding: 10px 12px; border-radius: 8px; color: #d1d8e3; font-size: 12px; text-align: left; }
.account-popover nav a:hover, .account-popover nav button:hover { background: #252b35; color: white; }
.account-popover nav svg { width: 17px; height: 17px; flex: 0 0 auto; color: #a5b0bf; }
.account-popover nav .account-sign-out { margin-top: 4px; color: #efa99a; }
.account-sign-out:disabled { opacity: .5; }
.account-menu-error { margin: 6px 10px; color: #ffaca2; font-size: 11px; line-height: 1.7; }
@media (max-width: 600px) {
  .account-trigger-name, .account-login-full { display: none; }
  .account-login-short { display: inline; }
  .account-login { gap: 5px; padding-inline: 8px; font-size: 11px; }
  .account-trigger { gap: 4px; padding-right: 7px; }
  .account-popover { position: fixed; top: 80px; right: 16px; }
}
</style>
