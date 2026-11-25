// @vitest-environment happy-dom
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { renderSitePage } from '../build/site-seo'
import { siteOrigin } from '../src/shared/i18n/home-seo'
import { sitePage, sitePagePaths } from '../src/shared/i18n/site-seo'
import { siteGuidePaths } from '../src/shared/i18n/site-guides'
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
    expect(page.querySelector('meta[property="og:site_name"]')?.getAttribute('content')).toBe(path === '/en/' ? 'cheerfulmusic' : '青妙Ai')
    expect(page.querySelector('meta[name="twitter:card"]')?.getAttribute('content')).toBe('summary_large_image')
    expect(page.querySelector('h1')?.textContent).toBe(metadata.heading)
    for (const alternate of page.querySelectorAll('link[hreflang="en"], link[hreflang="zh-CN"]')) {
      const href = new URL(alternate.getAttribute('href')!).pathname
      expect(page.querySelectorAll(`nav a[href="${href}"][hreflang="${alternate.getAttribute('hreflang')}"]`)).toHaveLength(1)
    }
    expect(page.querySelectorAll('script[type="application/ld+json"]')).toHaveLength(path === '/' ? 1 : 0)
  })

  it.each(['/', '/en/'])('publishes the same three alternates at %s', path => {
    const page = parsePage(path)
    const alternates = Object.fromEntries(Array.from(page.querySelectorAll('link[hreflang]'),
      link => [link.getAttribute('hreflang'), link.getAttribute('href')]))
    expect(alternates).toEqual({ en: siteOrigin + '/en/', 'zh-CN': siteOrigin + '/', 'x-default': siteOrigin + '/en/' })
  })

  it.each(['/', '/en/'])('shows one heading and the guide links in the initial invitation card at %s', path => {
    const page = parsePage(path)
    page.querySelectorAll('noscript').forEach(node => node.remove())
    const card = page.querySelector('#app > main.beta-access-entry')
    expect(card?.querySelector('h1')?.textContent).toBe(sitePage(path)!.heading)
    const productCopy = card?.querySelector('.beta-access-intro')
    expect(productCopy?.textContent).toBe(sitePage(path)!.description)
    expect(productCopy?.textContent).toContain('Cheerful Music')
    expect(productCopy?.textContent).toContain('cheerfulmusic')
    expect(productCopy?.textContent).toContain('青风音乐')
    expect(productCopy?.textContent).toContain('青妙Ai')
    expect(productCopy?.closest('[data-nosnippet]')).toBeNull()
    expect(card?.querySelectorAll('.home-public-info h2')).toHaveLength(2)
    expect(card?.querySelectorAll('.home-public-info ol li')).toHaveLength(3)
    expect(card?.querySelectorAll('.home-public-info details summary')).toHaveLength(2)
    for (const publicPath of [path, path === '/' ? '/discover' : '/en/discover']) {
      expect(renderSitePage(shell, sitePage(publicPath)!).split('<!--public-discover-links-->')).toHaveLength(2)
    }
    expect(card?.querySelector('[data-nosnippet]')?.textContent).toMatch(/邀请码|invitation code/)
    expect(page.querySelectorAll('h1')).toHaveLength(1)
    expect(card?.querySelectorAll('nav a[href*="/guide/"]')).toHaveLength(4)
    expect(card?.querySelector('nav')?.closest('[data-nosnippet]')).not.toBeNull()
    expect(card?.querySelector('footer')?.closest('[data-nosnippet]')).not.toBeNull()
    expect(card?.querySelector('form')).toBeNull()
  })

  it('publishes one site entity and the regional brand on each homepage', () => {
    expect(parsePage('/').documentElement.lang).toBe('zh-CN')
    expect(parsePage('/').querySelector('h1')?.textContent).toBe('青妙Ai')
    expect(parsePage('/').querySelector('meta[property="og:title"]')?.getAttribute('content')).toBe('青妙Ai')
    const website = JSON.parse(parsePage('/').querySelector('script[type="application/ld+json"]')!.textContent!)
    expect(website.name).toBe('青妙Ai')
    expect(website.url).toBe(siteOrigin + '/')
    expect(website.alternateName)
      .toEqual(expect.arrayContaining(['cheerfulmusic', 'Cheerful Music', '青风音乐', 'CheerfulMusic', 'cheerfulmusicAI', 'cheerfulmusicai']))
    expect(parsePage('/').querySelector('link[rel="icon"][sizes="96x96"]')?.getAttribute('href')).toBe('/brand/favicon-96.png')
    for (const [path, brand] of [['/', '青妙Ai'], ['/en/', 'cheerfulmusic']] as const) {
      const page = parsePage(path)
      expect(page.title).toBe(brand)
      expect(page.querySelector('.beta-access-brand')?.textContent).toBe(brand)
      expect(page.querySelector('meta[property="og:title"]')?.getAttribute('content')).toBe(brand)
      expect(page.querySelector('meta[name="twitter:title"]')?.getAttribute('content')).toBe(brand)
    }
    expect(sitePage('/zh-CN/')).toBeUndefined()
  })

  it.each([
    ['/discover', 'zh-CN', '青风音乐｜原创歌曲广场', '/'],
    ['/en/discover', 'en', 'Cheerful Music | Discover Original AI Songs', '/en/'],
  ] as const)('serves the linked music community in its URL language at %s', (path, language, title, home) => {
    const page = parsePage(path)
    page.querySelectorAll('noscript').forEach(node => node.remove())
    expect(page.documentElement.lang).toBe(language)
    expect(page.title).toBe(title)
    expect(page.querySelector('main h1')?.textContent).toBe(sitePage(path)!.heading)
    expect(page.querySelector('main p.beta-access-intro')?.textContent).toBe(sitePage(path)!.description)
    expect(parsePage(home).querySelector(`nav a[href="${path}"]`)).not.toBeNull()
    expect(Object.fromEntries(Array.from(page.querySelectorAll('link[hreflang]'), link =>
      [link.getAttribute('hreflang'), link.getAttribute('href')]))).toEqual({
      en: siteOrigin + '/en/discover', 'zh-CN': siteOrigin + '/discover', 'x-default': siteOrigin + '/discover',
    })
  })

  it('leaves the shared account and gift shell free of site metadata', () => {
    expect(shell).not.toContain('data-site-seo')
    expect(sitePage('/account')).toBeUndefined()
    expect(sitePage('/gift/example')).toBeUndefined()
    expect(sitePage('/works')).toBeUndefined()
    expect(sitePage('/recharge')).toBeUndefined()
  })

  it.each(siteGuidePaths)('publishes a readable guide with its paired translation at %s', path => {
    const guide = sitePage(path)!.guide!
    const page = parsePage(path)
    expect(page.documentElement.lang).toBe(guide.language)
    expect(page.querySelectorAll('main section h2')).toHaveLength(3)
    expect(page.querySelector(`main a[href="${guide.appPath}"]`)?.textContent).toBe(guide.cta)
    expect(page.querySelector('link[hreflang="zh-CN"]')?.getAttribute('href')).toBe(siteOrigin + guide.translations['zh-CN'])
    expect(page.querySelector('link[hreflang="en"]')?.getAttribute('href')).toBe(siteOrigin + guide.translations.en)
    const home = guide.language === 'en' ? '/en/' : '/'
    expect(parsePage(home).querySelector(`nav a[href="${path}"]`)?.textContent).toBe(guide.label)
    expect(page.querySelectorAll('nav a[hreflang]')).toHaveLength(2)
    expect(page.querySelector(`nav a[href="${path}"]:not([hreflang])`)).toBeNull()
  })

  it.each([...siteGuidePaths, '/discover', '/en/discover', '/help'])('links back to the brand homepage at %s', path => {
    const page = parsePage(path)
    const english = page.documentElement.lang === 'en'
    const home = english ? '/en/' : '/'
    const links = page.querySelectorAll(`nav a[href="${home}"]`)
    expect(links).toHaveLength(1)
    expect(links[0]?.textContent).toContain(english ? 'cheerfulmusic' : '青妙Ai')
    expect(links[0]?.textContent).toContain('Cheerful Music')
    if (!english) expect(links[0]?.textContent).toContain('青风音乐')
    if (path === '/help') {
      expect(page.querySelector('nav a[href="/"]')?.textContent).toContain('青风音乐')
      expect(page.querySelector('nav a[href="/help"]')).toBeNull()
    }
  })

  it('gives every public page a distinct title and description', () => {
    const pages = sitePagePaths.map(path => sitePage(path)!)
    expect(new Set(pages.map(page => page.title)).size).toBe(pages.length)
    expect(new Set(pages.map(page => page.description)).size).toBe(pages.length)
  })

  it.each(['en', 'zh-CN'] as const)('uses only the brand in the root homepage title for %s', language => {
    const html = renderSitePage(shell, sitePage('/', language)!)
    const page = new DOMParser().parseFromString(html, 'text/html')
    expect(page.title).toBe('青妙Ai')

    document.head.innerHTML = parsePage('/').head.innerHTML
    setLocale(language)
    updatePageLocale('/')
    expect(document.documentElement.lang).toBe(language)
    expect(document.title).toBe(page.title)
    const description = document.querySelector('meta[name="description"]')?.getAttribute('content')
    expect(description).toBe(sitePage(language === 'en' ? '/en/' : '/')!.description)
    expect(page.querySelector('main .beta-access-intro')?.textContent).toBe(description)
  })

  it('replaces metadata on client navigation and clears it on private destinations', () => {
    document.head.innerHTML = '<title>青风音乐</title><meta name="description">'
    updatePageLocale('/en/')
    expect(document.title).toBe(sitePage('/en/')!.title)
    updatePageLocale('/')
    expect(document.title).toBe('青妙Ai')
    expect(document.querySelectorAll('link[rel="canonical"]')).toHaveLength(1)
    expect(document.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(siteOrigin + '/')
    updatePageLocale('/account')
    expect(document.querySelectorAll('[data-site-seo]')).toHaveLength(0)
  })
})
