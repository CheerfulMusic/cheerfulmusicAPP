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

it.each([
  { locale: 'zh-CN', title: '基础版定制', features: ['提供录音棚', '专业修音', 'AI伴奏'] },
  { locale: 'en', title: 'Basic custom production', features: ['Recording studio access', 'Professional vocal tuning', 'AI backing track'] },
] as const)('shows the US$999 plan and uses its details in the consultation email in $locale', async ({ locale, title, features }) => {
  setLocale(locale)
  const wrapper = mount(PremiumStudioDialog, {
    props: { open: true },
    global: { plugins: [createPinia()] },
  })

  try {
    const cards = wrapper.findAll('.premium-plan')
    expect(cards.map(card => card.get('.premium-price').text())).toEqual([
      'US$999', locale === 'zh-CN' ? 'US$4,000起' : 'US$4,000 and up', 'US$20,000',
    ])
    const basic = cards[0]!
    expect(basic.get('.premium-plan-name').text()).toBe(title)
    expect(basic.findAll('.premium-features > span').map(feature => feature.text())).toEqual(features)

    await basic.get('input').setValue(true)
    const href = wrapper.get('a.premium-primary').attributes('href') ?? ''
    expect(href).toMatch(/^mailto:/)
    const email = new URL(href)
    const body = email.searchParams.get('body')
    expect(body).toContain(title)
    expect(body).toContain('US$999')
    expect(body).toContain(features.join(' / '))
  } finally {
    wrapper.unmount()
  }
})
