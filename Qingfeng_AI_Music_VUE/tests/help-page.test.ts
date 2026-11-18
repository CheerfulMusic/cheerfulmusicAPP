// @vitest-environment happy-dom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import HelpPage from '../src/app/pages/HelpPage.vue'
import { readFileSync } from 'node:fs'
const global = { stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } }
describe('help and support', () => {
  it('connects every footer support link to an existing section', () => {
    const footer = readFileSync('src/app/layouts/SiteFooter.vue', 'utf8')
    const help = mount(HelpPage, { global })
    for (const anchor of ['guide', 'faq', 'contact', 'feedback']) {
      expect(footer).toContain(`/help#${anchor}`)
      expect(help.find(`#${anchor}`).exists()).toBe(true)
    }
    help.unmount()
  })
  it('opens editable email drafts rather than claiming to submit feedback', () => {
    const page = mount(HelpPage, { global })
    const link = page.get('#feedback a')
    const url = new URL(link.attributes('href') ?? '')
    expect(url.protocol).toBe('mailto:')
    expect(url.pathname).toBe('labelmanager@cheerfulmusic.co.uk')
    expect(url.searchParams.get('body')).toContain('复现步骤')
    expect(page.get('#feedback').text()).toContain('不代表反馈已提交')
    expect(page.findAll('#faq details').length).toBe(8)
    page.unmount()
  })
})
