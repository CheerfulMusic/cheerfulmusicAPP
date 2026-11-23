// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it } from 'vitest'
import LanguageSwitcher from '../src/shared/i18n/LanguageSwitcher.vue'
import { locale, setLocale, updatePageLocale } from '../src/shared/i18n'

function createTestRouter() {
  const component = { template: '<main />' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', alias: '/en/', component },
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
  try {
    for (const language of ['en', 'zh-CN']) {
      const button = wrapper.get(`button[lang="${language}"]`)
      await button.trigger('click')
      expect(router.currentRoute.value.fullPath).toBe('/?utm_source=search#how')
      expect(document.documentElement.lang).toBe(language)
      expect(document.title).toBe('Cheerful Music')
      expect(document.querySelector('meta[property="og:site_name"]')?.getAttribute('content')).toBe('Cheerful Music')
      expect(button.attributes('aria-pressed')).toBe('true')
    }
  } finally {
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
    await wrapper.get('button[lang="zh-CN"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/?utm_source=search#how')
    expect(locale.value).toBe('zh-CN')
    expect(document.title).toBe('Cheerful Music')
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
