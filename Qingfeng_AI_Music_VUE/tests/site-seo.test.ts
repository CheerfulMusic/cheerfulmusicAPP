// @vitest-environment happy-dom
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { renderSitePage, seoReleaseManifest } from '../build/site-seo'
import { homeSeo, siteFavicon, siteOrigin } from '../src/shared/i18n/home-seo'
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
    expect(page.querySelector('meta[property="og:site_name"]')?.getAttribute('content')).toBe(path === '/en/' ? 'cheerfulmusic Ai' : '青妙Ai')
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

  it.each(['/', '/en/'])('serves the homepage introduction and guides before invitation status is known at %s', path => {
    const page = parsePage(path)
    page.querySelectorAll('noscript').forEach(node => node.remove())
    const landing = page.querySelector('#app > main.seo-landing')
    expect(landing?.querySelector('h1')?.textContent).toBe(sitePage(path)!.heading)
    const productCopy = landing?.querySelector('.beta-access-intro')
    expect(productCopy?.textContent).toBe(homeSeo[path === '/en/' ? 'en' : 'zh-CN'].description)
    expect(productCopy?.textContent).toContain('Cheerful Music')
    expect(productCopy?.textContent).toContain('cheerfulmusic Ai')
    expect(productCopy?.textContent).toContain('cheerfulmusicai')
    expect(productCopy?.textContent).toContain('青风音乐')
    expect(productCopy?.textContent).toContain('青妙Ai')
    expect(productCopy?.closest('[data-nosnippet]')).toBeNull()
    expect(landing?.querySelectorAll('.home-public-info h2')).toHaveLength(2)
    expect(landing?.querySelectorAll('.home-public-info ol li')).toHaveLength(3)
    expect(landing?.querySelectorAll('.home-public-info details summary')).toHaveLength(2)
    for (const publicPath of [path, path === '/' ? '/discover' : '/en/discover']) {
      expect(renderSitePage(shell, sitePage(publicPath)!).split('<!--public-discover-links-->')).toHaveLength(2)
    }
    expect(page.querySelectorAll('h1')).toHaveLength(1)
    expect(landing?.querySelectorAll('nav a[href*="/guide/"]')).toHaveLength(4)
    expect(landing?.querySelector('nav')?.closest('[data-nosnippet]')).not.toBeNull()
    expect(landing?.querySelector('footer')?.closest('[data-nosnippet]')).not.toBeNull()
    expect(page.querySelector('.beta-access-entry, .beta-access-prompt, form')).toBeNull()
  })

  it('publishes one site entity and the regional brand on each homepage', () => {
    expect(parsePage('/').documentElement.lang).toBe('zh-CN')
    expect(parsePage('/').querySelector('h1')?.textContent).toBe('做自己的歌')
    expect(parsePage('/').querySelector('meta[property="og:title"]')?.getAttribute('content')).toBe('青妙Ai')
    const website = JSON.parse(parsePage('/').querySelector('script[type="application/ld+json"]')!.textContent!)
    expect(website.name).toBe('青妙Ai')
    expect(website.url).toBe(siteOrigin + '/')
    expect(website.alternateName)
      .toEqual(expect.arrayContaining(['cheerfulmusic Ai', 'cheerfulmusic', 'Cheerful Music', '青风音乐', 'CheerfulMusic', 'cheerfulmusicAI', 'cheerfulmusicai']))
    const english = parsePage('/en/')
    const title = 'cheerfulmusic Ai | Everyone Can Be a Creator'
    const description = 'Create your own song in just a few clicks. No music skills needed.'
    expect(english.title).toBe(title)
    expect(english.querySelector('main h1')?.textContent).toBe('Everyone Can Be a Creator')
    expect(english.querySelector('meta[property="og:site_name"]')?.getAttribute('content')).toBe('cheerfulmusic Ai')
    expect(english.querySelector('meta[property="og:title"]')?.getAttribute('content')).toBe(title)
    expect(english.querySelector('meta[name="twitter:title"]')?.getAttribute('content')).toBe(title)
    expect(english.querySelector('meta[name="description"]')?.getAttribute('content')).toBe(description)
    expect(english.querySelector('meta[property="og:description"]')?.getAttribute('content')).toBe(description)
    expect(sitePage('/zh-CN/')).toBeUndefined()
  })

  it('builds a release manifest consistent with the two initial homepage documents', () => {
    const icon = readFileSync('public' + siteFavicon.path)
    const manifest = seoReleaseManifest(icon)
    expect(manifest.icon).toEqual({
      path: '/brand/favicon-symbol-96.png',
      sha256: createHash('sha256').update(icon).digest('hex'),
    })
    expect(icon.readUInt32BE(16)).toBe(96)
    expect(icon.readUInt32BE(20)).toBe(96)
    expect(manifest.pages.map(page => page.path)).toEqual(['/', '/en/'])
    for (const record of manifest.pages) {
      const html = parsePage(record.path)
      expect(record.language).toBe(html.documentElement.lang)
      expect(record.title).toBe(html.title)
      expect(record.brand).toBe(html.querySelector('meta[property="og:site_name"]')?.getAttribute('content'))
      expect(record.description).toBe(html.querySelector('meta[name="description"]')?.getAttribute('content'))
      expect(record.intro).toBe(html.querySelector('main .beta-access-intro')?.textContent)
    }
    expect(manifest.pages[0]?.aliases)
      .toEqual(JSON.parse(parsePage('/').querySelector('script[type="application/ld+json"]')!.textContent!).alternateName)
    expect(manifest.pages[1]?.lastModified).toBe(homeSeo.en.lastModified)
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
    expect(page.querySelector('main .beta-access-intro')?.textContent).toBe(homeSeo[language].description)
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
