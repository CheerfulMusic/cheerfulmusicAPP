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
    bodySha256: '2dab16e2480b5b8a4d211c9c6a1ab6b19a34f652ec58a471cc27696924ed1b28',
    sections: 22,
  },
  {
    page: 'privacy',
    pdf: 'privacy-notice-2026-11-16.pdf',
    sha256: '881181562f3020dee8d60c0ccda645d5f1ebbaddf439fa6e9929a81dc882c5ff',
    date: 'Last Modified: November 16, 2026',
    bodySha256: 'a1978982d9057e0478dcc061c047d1cc50def68ec200b72fc4432a66765ee659',
    sections: 14,
  },
  {
    page: 'community',
    pdf: 'community-guidelines-2026-11-13-v2.pdf',
    sha256: '4fed28ca604063c8e3f2e230017f51eacfcc441fb42652e75e5b62440ddfe53c',
    date: 'Effective Date: 13 November 2026',
    bodySha256: 'a8d7514e95c16b9b00853a511a7b6b1c0bec017646d08a4ba483644495f0dc73',
    sections: 18,
  },
]

describe('published legal documents', () => {
  it.each(documents)('shows the $page text at its established URL and offers the original PDF', ({ page, pdf, sha256, date, bodySha256, sections }) => {
    const original = readFileSync(`public/legal/${pdf}`)
    expect(original.subarray(0, 5).toString()).toBe('%PDF-')
    expect(createHash('sha256').update(original).digest('hex')).toBe(sha256)

    const publicPath = `/legal/${pdf}`
    const html = readFileSync(`public/legal/${page}.html`, 'utf8')
    const document = new DOMParser().parseFromString(html, 'text/html')
    expect(document.querySelector('iframe')).toBeNull()
    expect(document.querySelector(`a[href="${publicPath}"][download]`)).not.toBeNull()
    const body = document.querySelector('article.legal-body')!
    expect(body.querySelector('.legal-meta')?.textContent).toContain(date)
    const headings = [...body.querySelectorAll('h2[id]')]
    expect(headings).toHaveLength(sections)
    expect([...body.querySelectorAll('.legal-toc a')].map(link => link.getAttribute('href')))
      .toEqual(headings.map(heading => '#' + heading.id))

    // Expected digest is from the supplied PDF with print-only headers, footers and list markers removed.
    const text = body.cloneNode(true) as HTMLElement
    text.querySelector('.legal-toc')?.remove()
    const normalized = text.textContent!.replace(/\s+/g, ' ').trim()
    expect(createHash('sha256').update(normalized).digest('hex')).toBe(bodySha256)
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
