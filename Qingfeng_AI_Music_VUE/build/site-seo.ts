import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import type { Connect, Plugin } from 'vite'
import { homeLocale, homePublicInfo, homeRedirects, homeSeo, siteFavicon, siteName, siteOrigin, type Locale } from '../src/shared/i18n/home-seo'
import { discoverLocale, landingLinks, sitePage, sitePagePaths, socialMetadata, websiteSchema } from '../src/shared/i18n/site-seo'

type SitePage = NonNullable<ReturnType<typeof sitePage>>

function escapeHtml(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
}

function seoTag(name: 'link' | 'meta', attributes: Record<string, string | undefined>) {
  const markup = Object.entries(attributes)
    .filter((entry): entry is [string, string] => entry[1] !== undefined)
    .map(([key, value]) => `${key}="${escapeHtml(value)}"`).join(' ')
  return `<${name} data-site-seo ${markup}>`
}

function publicHomeDetails(language: Locale) {
  const copy = homePublicInfo[language]
  const steps = copy.steps.map(step => `<li>${escapeHtml(step)}</li>`).join('')
  const answers = copy.answers.map(([question, answer]) =>
    `<details><summary>${escapeHtml(question)}</summary><p>${escapeHtml(answer)}</p></details>`,
  ).join('')
  return `<div class="home-public-info">
    <section><h2>${escapeHtml(copy.how)}</h2><ol>${steps}</ol><a href="${copy.guidePath}">${escapeHtml(copy.guide)}</a></section>
    <!--public-discover-links-->
    <section><h2>${escapeHtml(copy.faq)}</h2>${answers}</section></div>`
}

export function renderSitePage(html: string, page: SitePage) {
  const metadata = [
    ...page.links.map(attributes => seoTag('link', attributes)),
    ...socialMetadata(page).map(attributes => seoTag('meta', attributes)),
  ]
  if (page.path === '/') {
    metadata.push(`<script data-site-seo type="application/ld+json">${JSON.stringify(websiteSchema)}</script>`)
  }
  const links = landingLinks(page.language, page.path).map(({ href, text, lang }) =>
    `<a href="${href}"${lang ? ` lang="${lang}" hreflang="${lang}"` : ''}>${escapeHtml(text)}</a>`,
  ).join(' ')
  const guide = page.guide
  const guideContent = guide ? guide.sections.map(([heading, body]) =>
    `<section><h2>${escapeHtml(heading)}</h2><p>${escapeHtml(body)}</p></section>`,
  ).join('')
    + `<p><a href="${guide.appPath}">${escapeHtml(guide.cta)}</a></p>` : ''
  const heading = `<h1>${escapeHtml(page.heading)}</h1>`
  const filing = '<a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer" lang="zh-CN">粤ICP备2023023949号</a>'
  const home = homeLocale(page.path) !== undefined
  const intro = escapeHtml(page.description)
  const navigation = `<nav>${links}</nav><footer>${filing}</footer>`
  const introClass = home ? ' class="beta-access-intro"' : ''
  const brandIntro = page.intro ? `<p><span data-nosnippet>${escapeHtml(page.intro)}</span></p>` : ''
  const content = home ? `${brandIntro}${publicHomeDetails(page.language)}<div data-nosnippet>${navigation}</div>` : guideContent + navigation
  let initialContent = `<main class="seo-landing">${heading}<p${introClass}>${intro}</p>${content}</main>`
  if (discoverLocale(page.path)) {
    initialContent = `<main class="beta-access-entry"><section class="beta-access-dialog beta-access-card">
      <img class="beta-access-logo" src="/brand/logo-symbol.svg" alt="" width="56" height="56">
      <p class="beta-access-brand">${escapeHtml(siteName)}</p>
      ${heading}
      <p class="beta-access-intro">${intro}</p>
      <!--public-discover-links-->
      <div data-nosnippet><nav class="beta-access-guides">${links}</nav><footer class="beta-access-filing">${filing}</footer></div>
    </section></main>`
  }
  return html
    .replace(/<html lang="[^"]*">/, `<html lang="${page.language}">`)
    .replace(/<title>[^<]*<\/title>/, `<title>${escapeHtml(page.title)}</title>`)
    .replace(/<meta name="description" content="[^"]*">/, `<meta name="description" content="${escapeHtml(page.description)}">`)
    .replace('</head>', `${metadata.join('\n')}\n</head>`)
    .replace('<div id="app"></div>', `<div id="app">${initialContent}</div>`)
}

const homeReleasePages = (['en', 'zh-CN'] as const).map(language => {
  const copy = homeSeo[language]
  const page = sitePage(copy.path)!
  return {
    path: page.path,
    language,
    title: page.title,
    description: page.description,
    intro: page.description,
    brand: copy.searchName,
    ...('lastModified' in copy ? { lastModified: copy.lastModified } : {}),
    ...(page.path === '/' ? { aliases: websiteSchema.alternateName } : {}),
  }
})

export function seoReleaseManifest(icon: Uint8Array, legacyIcon: Uint8Array) {
  return {
    version: 1,
    origin: siteOrigin,
    icon: { path: siteFavicon.path, sha256: createHash('sha256').update(icon).digest('hex') },
    legacyIcon: { path: '/favicon.ico', sha256: createHash('sha256').update(legacyIcon).digest('hex') },
    pages: homeReleasePages,
  }
}

const crawlFiles = {
  '/robots.txt': {
    type: 'text/plain',
    content: [
      'User-agent: *',
      'Disallow: /api/',
      'Allow: /api/public/discover',
      'Allow: /api/public/artists/',
      'Allow: /api/public/invite/status',
      'Disallow: /actuator/',
      `Sitemap: ${siteOrigin}/sitemap.xml`,
      '',
    ].join('\n'),
  },
  '/sitemap.xml': {
    type: 'application/xml',
    content: `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n`
      + sitePagePaths.map(path => {
        const lastModified = homeReleasePages.find(page => page.path === path)?.lastModified
        return `  <url><loc>${siteOrigin}${path}</loc>${lastModified ? `<lastmod>${lastModified}</lastmod>` : ''}</url>`
      }).join('\n')
      + '\n</urlset>\n',
  },
} as const

function htmlFile(path: string) {
  return path === '/' ? '/home.html' : `${path.replace(/\/$/, '')}/index.html`
}

const redirectLegacyHome: Connect.NextHandleFunction = (request, response, next) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') return next()
  const url = request.url ?? ''
  const path = url.split('?')[0] ?? ''
  if (!Object.hasOwn(homeRedirects, path)) return next()
  response.statusCode = 308
  response.setHeader('Location', homeRedirects[path] + url.slice(path.length))
  response.end()
}

export function siteSeoPlugin(): Plugin {
  return {
    name: 'site-seo',
    configureServer(server) {
      server.middlewares.use(redirectLegacyHome)
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split('?')[0]
        const file = Object.entries(crawlFiles).find(([url]) => url === path)?.[1]
        if (!file || (request.method !== 'GET' && request.method !== 'HEAD')) return next()
        response.setHeader('Content-Type', `${file.type}; charset=UTF-8`)
        response.end(request.method === 'HEAD' ? undefined : file.content)
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use(redirectLegacyHome)
      server.middlewares.use((request, _response, next) => {
        const path = request.url?.split('?')[0]
        const page = path && sitePage(path)
        if (page) request.url = htmlFile(page.path)
        next()
      })
    },
    transformIndexHtml(html, { server, originalUrl }) {
      const page = server && sitePage(originalUrl?.split('?')[0] ?? '')
      return {
        html: page ? renderSitePage(html, page) : html,
        tags: [{ tag: 'link', attrs: { rel: 'icon', type: siteFavicon.type, sizes: siteFavicon.sizes, href: siteFavicon.path }, injectTo: 'head' }],
      }
    },
    writeBundle({ dir }, bundle) {
      const index = bundle['index.html']
      if (!dir || index?.type !== 'asset') return
      // Discover loads on its own route. Fetch its module while the browser reads the public catalog.
      const discoverModule = Object.values(bundle).find(asset =>
        asset.type === 'chunk' && asset.isDynamicEntry && asset.name === 'DiscoverPage',
      )
      for (const path of sitePagePaths) {
        const page = sitePage(path)
        if (!page) throw new Error(`Missing SEO content for ${path}`)
        const filename = resolve(dir, `.${htmlFile(path)}`)
        mkdirSync(dirname(filename), { recursive: true })
        let shell = index.source.toString()
        if (discoverLocale(path) && discoverModule) {
          shell = shell.replace('</head>', `<link rel="modulepreload" crossorigin href="/${escapeHtml(discoverModule.fileName)}">\n</head>`)
        }
        writeFileSync(filename, renderSitePage(shell, page))
      }
      const icon = readFileSync(resolve(dir, `.${siteFavicon.path}`))
      const legacyIcon = readFileSync(resolve(dir, 'favicon.ico'))
      writeFileSync(resolve(dir, 'seo-release.json'), JSON.stringify(seoReleaseManifest(icon, legacyIcon), null, 2) + '\n')
      for (const [path, file] of Object.entries(crawlFiles)) {
        writeFileSync(resolve(dir, `.${path}`), file.content)
      }
    },
  }
}
