// @vitest-environment jsdom
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('community guidelines original document', () => {
  it('serves the exact supplied PDF and preserves the established entry point', () => {
    const original = readFileSync('public/legal/community-guidelines-2026-11-13.pdf')
    expect(original.subarray(0, 5).toString()).toBe('%PDF-')
    expect(createHash('sha256').update(original).digest('hex')).toBe('6dbe57b89611ae12de2749f40009622579c2b4c517fc0fcbc48b126917cfb840')
    const page = new DOMParser().parseFromString(readFileSync('public/legal/community.html', 'utf8'), 'text/html')
    expect(page.querySelector('iframe')?.getAttribute('src')).toBe('/legal/community-guidelines-2026-11-13.pdf')
    expect(page.querySelectorAll('a[href="/legal/community-guidelines-2026-11-13.pdf"]').length).toBeGreaterThanOrEqual(2)
    expect(page.body.textContent).toContain('13 November 2026')
    expect(readFileSync('src/app/layouts/SiteFooter.vue', 'utf8')).toContain('/legal/community.html')
  })
})
