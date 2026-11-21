// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { renderSitePage } from '../build/site-seo'
import { siteOrigin } from '../src/shared/i18n/home-seo'
import { sitePage, sitePagePaths } from '../src/shared/i18n/site-seo'
import { setLocale, updatePageLocale } from '../src/shared/i18n'

const shell = readFileSync('index.html', 'utf8')
const parsePage = (path: string) => new DOMParser().parseFromString(renderSitePage(shell, sitePage(path)!), 'text/html')

describe('crawlable site pages', () => {
  it.each(sitePagePaths)('serves unique page metadata and readable content at %s', path => {
    const metadata = sitePage(path)!
    const page = parsePage(path)
    expect(page.title).toBe(metadata.title)
    expect(page.querySelectorAll('meta[name="description"]')).toHaveLength(1)
    expect(page.querySelector('meta[name="description"]')?.getAttribute('content')).toBe(metadata.description)
    expect(page.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(siteOrigin + path)
    expect(page.querySelector('meta[property="og:url"]')?.getAttribute('content')).toBe(siteOrigin + path)
    expect(page.querySelector('meta[name="twitter:card"]')?.getAttribute('content')).toBe('summary_large_image')
    expect(page.querySelector('h1')?.textContent).toBe(metadata.heading)
    expect(page.querySelector('a[href="/zh-CN/"]')?.getAttribute('hreflang')).toBe('zh-CN')
    expect(page.querySelectorAll('script[type="application/ld+json"]')).toHaveLength(path === '/' ? 1 : 0)
  })

  it.each(['/', '/en/', '/zh-CN/'])('publishes the same three alternates at %s', path => {
    const page = parsePage(path)
    const alternates = Object.fromEntries(Array.from(page.querySelectorAll('link[hreflang]'),
      link => [link.getAttribute('hreflang'), link.getAttribute('href')]))
    expect(alternates).toEqual({ en: siteOrigin + '/en/', 'zh-CN': siteOrigin + '/zh-CN/', 'x-default': siteOrigin + '/' })
  })

  it('leaves the shared account and gift shell free of site metadata', () => {
    expect(shell).not.toContain('data-site-seo')
    expect(sitePage('/account')).toBeUndefined()
    expect(sitePage('/gift/example')).toBeUndefined()
  })

  it('uses a distinct title and description at every canonical URL', () => {
    const pages = sitePagePaths.map(path => sitePage(path)!)
    expect(new Set(pages.map(page => page.title)).size).toBe(pages.length)
    expect(new Set(pages.map(page => page.description)).size).toBe(pages.length)
  })

  it.each(['en', 'zh-CN'] as const)('uses only the brand in the root homepage title for %s', language => {
    const html = renderSitePage(shell, sitePage('/', language)!)
    const page = new DOMParser().parseFromString(html, 'text/html')
    expect(page.title).toBe('Cheerful Music')

    setLocale(language)
    updatePageLocale('/')
    expect(document.documentElement.lang).toBe(language)
    expect(document.title).toBe(page.title)
  })

  it('replaces metadata on client navigation and clears it on private destinations', () => {
    document.head.innerHTML = '<title>青风音乐</title><meta name="description">'
    updatePageLocale('/en/')
    expect(document.title).toBe(sitePage('/en/')!.title)
    updatePageLocale('/zh-CN/')
    expect(document.title).toBe(sitePage('/zh-CN/')!.title)
    expect(document.querySelectorAll('link[rel="canonical"]')).toHaveLength(1)
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(siteOrigin + '/zh-CN/')
    updatePageLocale('/account')
    expect(document.querySelectorAll('[data-site-seo]')).toHaveLength(0)
  })
})
