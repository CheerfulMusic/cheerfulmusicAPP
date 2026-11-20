// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it } from 'vitest'
import LanguageSwitcher from '../src/shared/i18n/LanguageSwitcher.vue'
import { locale, setLocale } from '../src/shared/i18n'

function createTestRouter() {
  const component = { template: '<main />' }
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', alias: ['/en/', '/zh-CN/'], component },
      { path: '/works', name: 'works', component },
    ],
  })
}

it('links to both localized homepages and follows aliases without losing the query or hash', async () => {
  const router = createTestRouter()
  await router.push('/?utm_source=search#how')
  const wrapper = mount(LanguageSwitcher, { global: { plugins: [router] } })
  try {
    const chinese = wrapper.get('a[hreflang="zh-CN"]')
    const english = wrapper.get('a[hreflang="en"]')
    expect(chinese.attributes('href')).toBe('/zh-CN/?utm_source=search#how')
    expect(english.attributes('href')).toBe('/en/?utm_source=search#how')
    expect(english.attributes('aria-current')).toBeUndefined()

    const openInNewTab = new MouseEvent('click', { button: 0, ctrlKey: true, cancelable: true })
    english.element.dispatchEvent(openInNewTab)
    expect(openInNewTab.defaultPrevented).toBe(false)
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')

    await english.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/en/?utm_source=search#how')
    expect(english.attributes('aria-current')).toBe('page')
    await chinese.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/zh-CN/?utm_source=search#how')
    expect(chinese.attributes('aria-current')).toBe('page')
    expect(english.attributes('aria-current')).toBeUndefined()
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
