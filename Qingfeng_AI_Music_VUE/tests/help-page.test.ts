// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SiteFooter from '../src/app/layouts/SiteFooter.vue'
import HelpPage from '../src/app/pages/HelpPage.vue'
import { t } from '../src/shared/i18n'

vi.mock('/brand/logo-symbol.svg', () => ({ default: '/brand/logo-symbol.svg' }))

const global = { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
afterEach(() => vi.restoreAllMocks())
describe('help and support', () => {
  it('links the footer support column to the four help sections', () => {
    const footer = mount(SiteFooter, { global })
    const page = mount(HelpPage, { global })
    const anchors = ['guide', 'faq', 'contact', 'feedback']
    const support = footer.get(`nav[aria-label="${t('帮助与支持')}"]`)
    expect(support.findAll('a').map(link => link.attributes('href')))
      .toEqual(anchors.map(anchor => `/help#${anchor}`))
    for (const anchor of anchors) {
      expect(page.find(`#${anchor}`).exists()).toBe(true)
    }
    footer.unmount()
    page.unmount()
  })
  it('opens an editable draft, preserves edits and explains email handoff', async () => {
    const page = mount(HelpPage, { global })
    expect(page.find('#feedback-draft').exists()).toBe(false)
    await page.get('#feedback > button').trigger('click')
    expect(page.get('#feedback > button').attributes('aria-expanded')).toBe('true')
    const webmail = page.findAll('#feedback-draft .webmail-links a')
    expect(webmail).toHaveLength(4)
    for (const link of webmail) {
      expect(new URL(link.attributes('href')!).protocol).toBe('https:')
      expect(link.attributes('target')).toBe('_blank')
      expect(link.attributes('rel')).toContain('noopener')
    }
    expect((page.get('#feedback-body').element as HTMLTextAreaElement).value).toContain('复现步骤')
    await page.get('#feedback-body').setValue('点击没有反应 & 请帮忙')
    await page.get('#feedback > button').trigger('click')
    const link = page.get('#feedback-draft a[href^="mailto:"]')
    const url = new URL(link.attributes('href') ?? '')
    expect(url.protocol).toBe('mailto:')
    expect(url.pathname).toBe('labelmanager@cheerfulmusic.co.uk')
    expect(url.searchParams.get('body')).toBe('点击没有反应 & 请帮忙')
    await link.trigger('click')
    expect(page.get('[role="status"]').text()).toContain('反馈尚未提交')
    page.unmount()
  })
  it('copies the draft or selects it for manual copying when clipboard access fails', async () => {
    const copy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined)
    const page = mount(HelpPage, { global })
    await page.get('#feedback > button').trigger('click')
    await page.get('#feedback-body').setValue('反馈内容')
    await page.get('#feedback-draft button').trigger('click')
    expect(copy).toHaveBeenCalledWith(expect.stringContaining('反馈内容'))
    expect(copy).toHaveBeenCalledWith(expect.stringContaining('labelmanager@cheerfulmusic.co.uk'))
    expect(page.get('[role="status"]').text()).toContain('已复制')
    copy.mockRejectedValue(new Error('denied'))
    await page.get('#feedback-draft button').trigger('click')
    expect(page.get('[role="status"]').text()).toContain('手动复制')
    const body = page.get('#feedback-body').element as HTMLTextAreaElement
    expect(body.selectionEnd).toBe(body.value.length)
    page.unmount()
  })
})
