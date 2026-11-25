import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import type { Connect, Plugin } from 'vite'
import { homeLocale, homeSeo, siteName, siteOrigin } from '../src/shared/i18n/home-seo'
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
  const landing = `<main class="seo-landing">${heading}<p>${escapeHtml(page.description)}</p>${guideContent}<nav>${links}</nav><footer>${filing}</footer></main>`
  let initialContent = landing
  const home = page.path === '/' || homeLocale(page.path) !== undefined
  if (home || discoverLocale(page.path)) {
    initialContent = `<main class="beta-access-entry"><section class="beta-access-dialog beta-access-card">
      <img class="beta-access-logo" src="/brand/logo-symbol.svg" alt="" width="56" height="56">
      <p class="beta-access-brand">${escapeHtml(page.siteName ?? siteName)}</p>${heading}
      <p class="beta-access-intro">${escapeHtml(page.description)}</p>
      ${home ? `<div data-nosnippet><p class="beta-access-prompt">${escapeHtml(homeSeo[page.language].invitePrompt)}</p></div>` : ''}
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
      + sitePagePaths.map(path => `  <url><loc>${siteOrigin}${path}</loc></url>`).join('\n')
      + '\n</urlset>\n',
  },
} as const

function htmlFile(path: string) {
  return path === '/' ? '/home.html' : `${path.replace(/\/$/, '')}/index.html`
}

const redirectLegacyChineseHome: Connect.NextHandleFunction = (request, response, next) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') return next()
  const url = request.url ?? ''
  const path = url.split('?')[0] ?? ''
  if (path !== '/zh-CN' && path !== '/zh-CN/' && path !== '/zh-CN/index.html') return next()
  response.statusCode = 308
  response.setHeader('Location', '/' + url.slice(path.length))
  response.end()
}

export function siteSeoPlugin(): Plugin {
  return {
    name: 'site-seo',
    configureServer(server) {
      server.middlewares.use(redirectLegacyChineseHome)
      server.middlewares.use((request, response, next) => {
        const path = request.url?.split('?')[0]
        const file = Object.entries(crawlFiles).find(([url]) => url === path)?.[1]
        if (!file || (request.method !== 'GET' && request.method !== 'HEAD')) return next()
        response.setHeader('Content-Type', `${file.type}; charset=UTF-8`)
        response.end(request.method === 'HEAD' ? undefined : file.content)
      })
    },
    configurePreviewServer(server) {
      server.middlewares.use(redirectLegacyChineseHome)
      server.middlewares.use((request, _response, next) => {
        const path = request.url?.split('?')[0]
        const page = path && sitePage(path)
        if (page) request.url = htmlFile(page.path)
        next()
      })
    },
    transformIndexHtml(html, { server, originalUrl }) {
      const page = server && sitePage(originalUrl?.split('?')[0] ?? '')
      return page ? renderSitePage(html, page) : html
    },
    writeBundle({ dir }, bundle) {
      const index = bundle['index.html']
      if (!dir || index?.type !== 'asset') return
      for (const path of sitePagePaths) {
        const page = sitePage(path)
        if (!page) throw new Error(`Missing SEO content for ${path}`)
        const filename = resolve(dir, `.${htmlFile(path)}`)
        mkdirSync(dirname(filename), { recursive: true })
        writeFileSync(filename, renderSitePage(index.source.toString(), page))
      }
      for (const [path, file] of Object.entries(crawlFiles)) {
        writeFileSync(resolve(dir, `.${path}`), file.content)
      }
    },
  }
}
