<script setup lang="ts">
import { t } from '../../shared/i18n/index'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import GlobalPlayer from '../../shared/player/GlobalPlayer.vue'
import { usePlayer } from '../../shared/player/store'
import BrandLogo from '../../shared/ui/BrandLogo.vue'
import NavIcon from '../../shared/ui/NavIcon.vue'
import LanguageSwitcher from '../../shared/i18n/LanguageSwitcher.vue'

const route = useRoute(), player = usePlayer()
const screen = window.matchMedia('(max-width: 880px)')
const mobile = ref(screen.matches), menuOpen = ref(false)
const menuButton = ref<HTMLButtonElement>(), menu = ref<HTMLElement>()
const isGift = computed(() => route.name === 'gift')
const navigation = [
  { to: '/', label: '发现音乐', icon: 'home' },
  { to: '/#inspiration', label: '送歌灵感', icon: 'music' },
  { to: '/#how', label: '如何制作', icon: 'guide' },
]
const isActive = (to: string) => to === '/' ? route.name === 'home' && !route.hash : route.name === 'home' && route.hash === to.slice(1)
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
onMounted(() => { screen.addEventListener('change', resize); document.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { screen.removeEventListener('change', resize); document.removeEventListener('keydown', onKey); document.body.classList.remove('navigation-open', 'has-preview') })
</script>
<template>
  <div class="music-shell consumer-shell" :class="{'gift-shell':isGift}">
    <a class="skip-link" href="#main-content">{{ t('跳到主要内容') }}</a>
    <header class="consumer-header">
      <div class="consumer-header-inner">
        <RouterLink class="consumer-brand" to="/" :aria-label="t('Cheerful Music 青风音乐首页')"><BrandLogo :compact="mobile || isGift"/></RouterLink>
        <nav v-if="!isGift" class="consumer-navigation" :aria-label="t('主导航')"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :class="{'is-active':isActive(item.to)}" :aria-current="isActive(item.to)?'page':undefined">{{ t(item.label) }}</RouterLink></nav>
        <div class="consumer-header-actions">
          <LanguageSwitcher/>
          <RouterLink v-if="!isGift" class="consumer-my-music" :class="{'is-active':route.path.startsWith('/works')}" to="/works"><NavIcon name="music"/><span>{{ t('我的音乐') }}</span></RouterLink>
          <RouterLink class="consumer-create" to="/create">{{ isGift ? t("我也想送一首歌") : t("制作一首歌") }}</RouterLink>
          <button v-if="!isGift" ref="menuButton" class="mobile-menu-toggle" :aria-expanded="menuOpen" aria-controls="consumer-navigation" :aria-label="menuOpen?t('关闭导航'):t('打开导航')" @click="menuOpen=!menuOpen"><NavIcon name="menu"/></button>
        </div>
      </div>
    </header>
    <template v-if="menuOpen && !isGift">
      <button class="navigation-backdrop" :aria-label="t('关闭导航')" @click="closeMenu"></button>
      <section id="consumer-navigation" ref="menu" class="consumer-mobile-menu" role="dialog" :aria-label="t('音乐网站导航')" aria-modal="true">
        <div class="consumer-menu-heading"><strong>{{ t('想从哪里开始？') }}</strong><button class="consumer-menu-close" :aria-label="t('关闭导航')" @click="closeMenu"><NavIcon name="close"/></button></div>
        <nav :aria-label="t('手机导航')"><RouterLink v-for="item in navigation" :key="item.to" :to="item.to" :class="{'is-active':isActive(item.to)}"><NavIcon :name="item.icon"/>{{ t(item.label) }}</RouterLink><RouterLink to="/works"><NavIcon name="music"/>{{ t('我的音乐') }}</RouterLink></nav>
        <RouterLink class="consumer-menu-create" to="/create">{{ t('为重要的人，制作一首歌') }} <NavIcon name="arrow"/></RouterLink>
        <RouterLink class="consumer-recovery-link" to="/account">{{ t('找回之前的音乐') }}</RouterLink>
      </section>
    </template>
    <div class="music-shell-body">
      <main id="main-content" :class="{workspace:route.name!=='home'}" tabindex="-1"><slot/></main>
      <footer class="brand-footer"><div class="footer-brand-message"><RouterLink to="/">Cheerful Music <span>{{ t('青风音乐') }}</span></RouterLink><p>{{ t('把心意，唱给你听。') }}</p></div><nav :aria-label="t('页脚导航')"><RouterLink to="/works">{{ t('我的音乐') }}</RouterLink><RouterLink to="/account">{{ t('找回我的音乐') }}</RouterLink><RouterLink to="/#how">{{ t('如何制作') }}</RouterLink></nav><p class="footer-media-note">{{ t('首页封面为 AI 场景示意，试听为原创器乐样曲。') }}</p></footer>
    </div>
    <GlobalPlayer/>
  </div>
</template>
