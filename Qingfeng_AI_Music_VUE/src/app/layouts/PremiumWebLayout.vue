<script setup lang="ts">
import { t, locale } from '../../shared/i18n/index'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import GlobalPlayer from '../../shared/player/GlobalPlayer.vue'
import { usePlayer } from '../../shared/player/store'
import BrandLogo from '../../shared/ui/BrandLogo.vue'
import SiteFooter from './SiteFooter.vue'
import NavIcon from '../../shared/ui/NavIcon.vue'
import LanguageSwitcher from '../../shared/i18n/LanguageSwitcher.vue'
import UserAccountMenu from '../../shared/ui/UserAccountMenu.vue'
import { accountState, ensureAccountState, refreshAccountState } from '../../shared/identity/account-state'
import { logtoState, refreshLogtoState } from '../../shared/identity/logto-state'
import { showLoginDialog } from '../../shared/identity/login-dialog'

const route = useRoute(), player = usePlayer()
const screen = window.matchMedia('(max-width: 1200px)')
const mobile = ref(screen.matches), menuOpen = ref(false)
const menuButton = ref<HTMLButtonElement>(), menu = ref<HTMLElement>()
const isGift = computed(() => route.name === 'gift')
const isHome = computed(() => route.name === 'home')
const navigation = [
  { to: '/', label: '首页', icon: 'home' },
  { to: '/create', label: '开始创作', icon: 'home' },
  { to: '/#inspiration', label: '推荐', icon: 'music' },
  { to: '/#how', label: '如何制作', icon: 'guide' },
]
const isActive = (to: string) => to.startsWith('/#') ? route.name === 'home' && route.hash === to.slice(1) : route.path === to && !route.hash
function resize() { mobile.value = screen.matches; if (!mobile.value) menuOpen.value = false }
async function closeMenu() { menuOpen.value = false; await nextTick(); menuButton.value?.focus() }
function onKey(event: KeyboardEvent) {
  if (!menuOpen.value) return
  if (event.key === 'Escape') { event.preventDefault(); void closeMenu(); return }
  if (event.key !== 'Tab') return
  const items = Array.from(menu.value?.querySelectorAll<HTMLElement>('a,button:not([disabled])') || [])
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(menuOpen, async value => {
  document.body.classList.toggle('navigation-open', value)
  if (value) { await nextTick(); menu.value?.querySelector<HTMLElement>('nav a')?.focus() }
})
watch(() => route.fullPath, () => { menuOpen.value = false })
watch(() => !!player.current, value => document.body.classList.toggle('has-preview', value), { immediate: true })
function refreshAccount() { if (isGift.value) return; void refreshAccountState().catch(() => undefined); void refreshLogtoState().catch(() => undefined) }
function visibleAccount() { if (document.visibilityState === 'visible') refreshAccount() }
onMounted(() => {
  screen.addEventListener('change', resize); document.addEventListener('keydown', onKey)
  // The account page owns its initial read after ensuring the creator session.
  if (route.name !== 'identity' && !isGift.value) { void ensureAccountState().catch(() => undefined); void refreshLogtoState().catch(() => undefined) }
  window.addEventListener('qingfeng:identity-changed', refreshAccount)
  document.addEventListener('visibilitychange', visibleAccount)
})
onBeforeUnmount(() => {
  screen.removeEventListener('change', resize); document.removeEventListener('keydown', onKey)
  window.removeEventListener('qingfeng:identity-changed', refreshAccount)
  document.removeEventListener('visibilitychange', visibleAccount)
  document.body.classList.remove('navigation-open', 'has-preview')
})
</script>
<template>
  <div class="music-shell consumer-shell" :class="{'gift-shell':isGift, 'home-light-shell':isHome}">
    <a class="skip-link" href="#main-content">{{ t('跳到主要内容') }}</a>
    <header class="consumer-header">
      <div class="consumer-header-inner">
        <RouterLink class="consumer-brand" to="/" :aria-label="t('Cheerful Music 青风音乐首页')"><BrandLogo :compact="mobile || isGift" standalone/></RouterLink>
        <nav v-if="!isGift" class="consumer-navigation" :aria-label="t('主导航')"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :class="{'is-active':isActive(item.to)}" :aria-current="isActive(item.to)?'page':undefined">{{ t(item.label) }}</RouterLink></nav>
        <div class="consumer-header-actions">
          <LanguageSwitcher/>
          <RouterLink v-if="!isGift" class="consumer-my-music" :class="{'is-active':route.path.startsWith('/works')}" to="/works"><NavIcon name="music"/><span>{{ t('我的音乐') }}</span></RouterLink>
          <RouterLink v-if="!isGift" class="consumer-recharge" to="/recharge">{{ locale === 'en' ? 'Credits' : '充值' }}</RouterLink>
          <UserAccountMenu v-if="!isGift" :navigation-open="menuOpen" @opened="menuOpen=false" />
          <button v-if="!isGift" ref="menuButton" class="mobile-menu-toggle" :aria-expanded="menuOpen" aria-controls="consumer-navigation" :aria-label="menuOpen?t('关闭导航'):t('打开导航')" @click="menuOpen=!menuOpen"><NavIcon name="menu"/></button>
        </div>
      </div>
    </header>
    <template v-if="menuOpen && !isGift">
      <button class="navigation-backdrop" :aria-label="t('关闭导航')" @click="closeMenu"></button>
      <section id="consumer-navigation" ref="menu" class="consumer-mobile-menu" role="dialog" :aria-label="t('音乐网站导航')" aria-modal="true">
        <div class="consumer-menu-heading"><strong>{{ t('想从哪里开始？') }}</strong><button class="consumer-menu-close" :aria-label="t('关闭导航')" @click="closeMenu"><NavIcon name="close"/></button></div>
        <nav :aria-label="t('手机导航')"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :class="{'is-active':isActive(item.to)}"><NavIcon :name="item.icon"/>{{ t(item.label) }}</RouterLink><RouterLink to="/works"><NavIcon name="music"/>{{ t('我的音乐') }}</RouterLink><RouterLink v-if="accountState.authenticated || logtoState.authenticated" to="/account" :class="{'is-active':route.name==='identity'}"><NavIcon name="account"/>{{ t('账号与登录') }}</RouterLink><button v-else type="button" class="mobile-login-link" @click="menuOpen=false; showLoginDialog()"><NavIcon name="account"/>{{ t('登录/注册') }}</button></nav>
        <RouterLink class="consumer-menu-create" to="/create">{{ t('为重要的人，制作一首歌') }} <NavIcon name="arrow"/></RouterLink>
        <RouterLink class="consumer-recovery-link" to="/recharge">{{ locale === 'en' ? 'Recharge credits' : '充值创作积分' }}</RouterLink>
        <RouterLink class="consumer-recovery-link" to="/account">{{ t('找回之前的音乐') }}</RouterLink>
      </section>
    </template>
    <div class="music-shell-body">
      <main id="main-content" :class="{workspace:route.name!=='home'}" tabindex="-1"><slot/></main>
      <SiteFooter/>
    </div>
    <GlobalPlayer/>
  </div>
</template>

<style scoped>
.consumer-recharge { color:#ee5741; font-size:13px; font-weight:700; text-decoration:none; white-space:nowrap; }
.consumer-login { border:0; cursor:pointer; font-family:inherit; }
.mobile-login-link { display:flex; align-items:center; gap:12px; width:100%; padding:13px 14px; border-radius:10px; color:#665259; font-size:14px; text-align:left; }
.mobile-login-link:hover { background:var(--accent-soft); color:var(--accent-text); }
.mobile-login-link svg { width:18px; height:18px; }
@media(max-width:600px) {
  .consumer-header-actions { gap:8px; }
  /* The recharge entry remains available in the mobile menu. */
  .consumer-recharge { display:none; }
}
@media(max-width:380px) {
  .consumer-shell .consumer-header-actions { gap:4px; }
  .consumer-header-inner { gap:6px; }
  .consumer-brand :deep(.brand-logo) { gap:6px; }
  .consumer-brand :deep(.brand-logo > img) { width:30px; height:30px; }
  .consumer-brand :deep(.brand-logo-type strong) { font-size:13px; }
}
</style>
