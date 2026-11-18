// @vitest-environment jsdom
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import previous from '../src/modules/legal/content/terms-2026-11-13-draft-1.json'
import amended from '../src/modules/legal/content/terms-2026-11-13-draft-2.json'

const documents = [
  {
    page: 'terms',
    pdf: 'terms-of-service-2026-11-13.pdf',
    sha256: '98f1ddae1816a88ea4b7bf4397e300d6fe9cb1070130670630c4480708a69994',
    date: 'Effective Date: 13 November 2026',
  },
  {
    page: 'privacy',
    pdf: 'privacy-notice-2026-11-16.pdf',
    sha256: '881181562f3020dee8d60c0ccda645d5f1ebbaddf439fa6e9929a81dc882c5ff',
    date: 'Last Modified: November 16, 2026',
  },
  {
    page: 'community',
    pdf: 'community-guidelines-2026-11-13-v2.pdf',
    sha256: '4fed28ca604063c8e3f2e230017f51eacfcc441fb42652e75e5b62440ddfe53c',
    date: 'Effective Date: 13 November 2026',
  },
]

describe('published legal documents', () => {
  it.each(documents)('serves the supplied $page PDF through the established page', ({ page, pdf, sha256, date }) => {
    const original = readFileSync(`public/legal/${pdf}`)
    expect(original.subarray(0, 5).toString()).toBe('%PDF-')
    expect(createHash('sha256').update(original).digest('hex')).toBe(sha256)

    const publicPath = `/legal/${pdf}`
    const html = readFileSync(`public/legal/${page}.html`, 'utf8')
    const document = new DOMParser().parseFromString(html, 'text/html')
    expect(document.querySelector('iframe')?.getAttribute('src')).toBe(publicPath)
    expect(document.querySelector(`a[href="${publicPath}"][target="_blank"]`)).not.toBeNull()
    expect(document.querySelector(`a[href="${publicPath}"][download]`)).not.toBeNull()
    expect(document.body.textContent).toContain(date)
    expect(readFileSync('src/app/layouts/SiteFooter.vue', 'utf8')).toContain(`/legal/${page}.html`)
  })

  it('preserves the service terms and privacy route aliases', () => {
    const router = readFileSync('src/app/router/index.ts', 'utf8')
    expect(router).toContain("window.location.replace('/legal/terms.html')")
    expect(router).toContain("window.location.replace('/legal/privacy.html')")
  })

  it('preserves the archived service terms drafts', () => {
    const original = readFileSync('src/modules/legal/content/terms-2026-11-13-draft-1.json', 'utf8')
    expect(amended.amendedFromSha256).toBe(createHash('sha256').update(original.replace(/\r\n/g, '\n')).digest('hex'))
    expect(amended.originalSourceSha256).toBe(previous.sourceSha256)
    expect(amended.paragraphs).toEqual(previous.paragraphs.map(p => p.replace(
      'one-time award of 100 Promotional Credits', 'one-time award of 50 Promotional Credits',
    )))
  })
})
