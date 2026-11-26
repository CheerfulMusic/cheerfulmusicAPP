// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it, vi } from 'vitest'
import HomeHero from '../src/modules/home/components/HomeHero.vue'
import LanguageSwitcher from '../src/shared/i18n/LanguageSwitcher.vue'
import { locale, setLocale, updatePageLocale } from '../src/shared/i18n'

vi.mock('/brand/headline-own-song-regular.svg', () => ({ default: '/brand/headline-own-song-regular.svg' }))

function createTestRouter() {
  const component = { template: '<main />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', alias: '/en/', component },
      { path: '/discover', name: 'discover', alias: '/en/discover', component },
      { path: '/works', name: 'works', component },
    ],
  })
  router.afterEach((to, _from, failure) => { if (!failure) updatePageLocale(to.path) })
  return router
}

it('switches both ways on the root without changing the URL or tab title', async () => {
  const router = createTestRouter()
  setLocale('zh-CN')
  await router.push('/?utm_source=search#how')
  const wrapper = mount(LanguageSwitcher, { global: { plugins: [router] } })
  const hero = mount(HomeHero, { props: { brand: '青妙Ai' }, global: { stubs: { RouterLink: true } } })
  try {
    for (const language of ['en', 'zh-CN']) {
      const button = wrapper.get(`button[lang="${language}"]`)
      await button.trigger('click')
      expect(router.currentRoute.value.fullPath).toBe('/?utm_source=search#how')
      expect(document.documentElement.lang).toBe(language)
      expect(document.title).toBe('青妙Ai')
      expect(document.querySelector('meta[property="og:site_name"]')?.getAttribute('content')).toBe('青妙Ai')
      expect(button.attributes('aria-pressed')).toBe('true')
      expect(hero.get('.p-intro-description').text()).toContain('青妙Ai')
      expect(hero.findAll('h1')).toHaveLength(1)
      expect(hero.get('h1').attributes('id')).toBe('home-hero-title')
      expect(hero.find('.home-product-title').exists()).toBe(false)
      expect(hero.findAll('h1 > span').map(line => line.text())).toEqual(language === 'en'
        ? ['Everyone Can Be a Creator']
        : ['做自己的歌', 'Everyone Can Be a Creator'])
      expect(hero.get('.p-reassurance').text()).toBe(language === 'en'
        ? 'Start with an idea Choose your style Create your own music'
        : '写下灵感 选择风格 创作出属于你的音乐')
    }
  } finally {
    hero.unmount()
    wrapper.unmount()
  }
})

it('returns visitors from the English search landing page to the root when they choose Chinese', async () => {
  const router = createTestRouter()
  setLocale('zh-CN')
  await router.push('/en/?utm_source=search#how')
  const wrapper = mount(LanguageSwitcher, { global: { plugins: [router] } })
  try {
    expect(locale.value).toBe('en')
    expect(document.title).toBe('cheerfulmusic Ai | Everyone Can Be a Creator')
    await wrapper.get('button[lang="zh-CN"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/?utm_source=search#how')
    expect(locale.value).toBe('zh-CN')
    expect(document.title).toBe('青妙Ai')
  } finally {
    wrapper.unmount()
  }
})

it('uses the selected music community URL and preserves a search query and fragment', async () => {
  const router = createTestRouter()
  setLocale('zh-CN')
  await router.push('/en/discover?q=rock#discover-tracks')
  const wrapper = mount(LanguageSwitcher, { global: { plugins: [router] } })
  try {
    expect(locale.value).toBe('en')
    for (const [language, path] of [['zh-CN', '/discover'], ['en', '/en/discover']] as const) {
      await wrapper.get(`button[lang="${language}"]`).trigger('click')
      await flushPromises()
      expect(router.currentRoute.value.fullPath).toBe(path + '?q=rock#discover-tracks')
      expect(document.documentElement.lang).toBe(language)
      expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://cheerfulmusic.com' + path)
    }
  } finally {
    wrapper.unmount()
  }
})

it('changes the interface language in place on other pages', async () => {
  const router = createTestRouter()
  await router.push('/works?filter=all')
  setLocale('zh-CN')
  const wrapper = mount(LanguageSwitcher, { global: { plugins: [router] } })
  try {
    expect(wrapper.find('a').exists()).toBe(false)
    const english = wrapper.get('button[lang="en"]')
    await english.trigger('click')
    expect(router.currentRoute.value.fullPath).toBe('/works?filter=all')
    expect(english.attributes('aria-pressed')).toBe('true')
    expect(locale.value).toBe('en')
  } finally {
    wrapper.unmount()
    setLocale('zh-CN')
  }
})
