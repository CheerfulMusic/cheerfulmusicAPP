// @vitest-environment happy-dom
import { existsSync } from 'node:fs'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { expect, it } from 'vitest'
import PremiumStudioDialog from '../src/modules/works/components/PremiumStudioDialog.vue'
import { setLocale } from '../src/shared/i18n'

it.each([
  { locale: 'en', chinese: 'Chinese PDF', english: 'English PDF', chineseName: 'in Chinese', englishName: 'in English' },
  { locale: 'zh-CN', chinese: '中文版 PDF', english: '英文版 PDF', chineseName: '中文版', englishName: '英文版' },
] as const)('shows both company profile downloads in $locale', ({ locale, chinese, english, chineseName, englishName }) => {
  setLocale(locale)
  const wrapper = mount(PremiumStudioDialog, {
    props: { open: true },
    global: { plugins: [createPinia()] },
    attachTo: document.body,
  })

  try {
    expect(wrapper.findAll('.premium-profile-downloads a')).toHaveLength(2)
    for (const { language, label, name } of [
      { language: 'zh', label: chinese, name: chineseName },
      { language: 'en', label: english, name: englishName },
    ]) {
      const link = wrapper.get(`.premium-profile-downloads a[href*="company-profile-${language}-"]`)
      expect(link.text()).toContain(label)
      expect(link.attributes('aria-label')).toContain(name)
      expect(link.attributes('download')).toMatch(/\.pdf$/)
      const href = link.attributes('href')
      expect(href).toMatch(/^\/media\/premium-studio\/[^/]+\.pdf$/)
      expect(existsSync('public' + href)).toBe(true)
    }
  } finally {
    wrapper.unmount()
  }
})
