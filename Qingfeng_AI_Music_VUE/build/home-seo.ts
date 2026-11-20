import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import type { Plugin } from 'vite'
import { homeLocale, homeSeo, homeSeoLinks, type Locale } from '../src/shared/i18n/home-seo'

function renderHome(html: string, locale: Locale) {
  const { title, description } = homeSeo[locale]
  const links = homeSeoLinks(locale).map(({ rel, href, hreflang }) =>
    `<link data-home-seo rel="${rel}" href="${href}"${hreflang ? ` hreflang="${hreflang}"` : ''}>`,
  ).join('\n')
  return html
    .replace(/<html lang="[^"]*">/, `<html lang="${locale}">`)
    .replace(/<title>[^<]*<\/title>/, `<title>${title}</title>`)
    .replace(/<meta name="description" content="[^"]*">/, `<meta name="description" content="${description}">`)
    .replace('</head>', `${links}\n</head>`)
}

export function homeSeoPlugin(): Plugin {
  return {
    name: 'home-seo',
    transformIndexHtml(html, { server, originalUrl }) {
      const locale = server && homeLocale(originalUrl?.split('?')[0] ?? '')
      return locale ? renderHome(html, locale) : html
    },
    writeBundle({ dir }, bundle) {
      const index = bundle['index.html']
      if (index?.type !== 'asset') return
      for (const locale of Object.keys(homeSeo) as Locale[]) {
        const filename = resolve(dir!, `.${homeSeo[locale].path}index.html`)
        mkdirSync(dirname(filename), { recursive: true })
        writeFileSync(filename, renderHome(index.source.toString(), locale))
      }
    },
  }
}
