import { homeLocale, homeSeo, homeSeoLinks, siteName, siteOrigin, type Locale } from './home-seo'
import { siteGuide, siteGuideLinks, siteGuidePaths } from './site-guides'

const defaultHome = {
  en: {
    heading: siteName,
    searchTitle: homeSeo.en.searchTitle,
    description: 'Make a song from your ideas with Cheerful Music. Explore songs from other creators and choose the English or Simplified Chinese site.',
  },
  'zh-CN': homeSeo['zh-CN'],
} as const

const sections = {
  '/discover': {
    en: {
      heading: 'Discover Music',
      title: 'Discover Music | Cheerful Music',
      description: 'Listen to songs shared by the Cheerful Music community. Explore featured tracks, popular songs and public artist profiles.',
    },
    'zh-CN': {
      heading: '音乐广场',
      title: '音乐广场 | 青风音乐',
      description: '在青风音乐广场聆听创作者公开发布的歌曲，探索精选作品、热门音乐和创作者主页。',
    },
  },
  '/help': {
    en: {
      heading: 'Help & Support',
      title: 'Help & Support | Cheerful Music',
      description: 'Learn how to create, listen to and share songs with Cheerful Music, find answers to common questions, and contact support.',
    },
    'zh-CN': {
      heading: '帮助与支持',
      title: '帮助与支持 | 青风音乐',
      description: '了解如何用青风音乐创作、试听和分享歌曲，查找常见问题的解答，并联系支持团队。',
    },
  },
} as const

export const sitePagePaths = [homeSeo['zh-CN'].path, homeSeo.en.path, '/discover', '/help', ...siteGuidePaths] as const

export function sitePage(path: string, preferredLocale?: Locale) {
  const guide = siteGuide(path)
  if (guide) {
    return {
      path: guide.path,
      language: guide.language,
      heading: guide.heading,
      title: guide.title,
      description: guide.description,
      guide,
      links: [
        { rel: 'canonical', href: siteOrigin + guide.path, hreflang: undefined },
        ...Object.entries(guide.translations).map(([hreflang, href]) => ({
          rel: 'alternate', href: siteOrigin + href, hreflang,
        })),
        { rel: 'alternate', href: siteOrigin + guide.translations['zh-CN'], hreflang: 'x-default' },
      ],
    }
  }
  const localized = homeLocale(path)
  const language = localized ?? preferredLocale ?? (path === '/' ? 'zh-CN' : 'en')
  if (path === '/' || localized) {
    const canonical = localized ? homeSeo[localized].path : '/'
    const copy = localized ? homeSeo.en : defaultHome[language]
    return {
      path: canonical,
      language,
      heading: copy.heading,
      title: localized ? homeSeo.en.title : siteName,
      searchTitle: copy.searchTitle,
      description: copy.description,
      links: homeSeoLinks(canonical),
    }
  }
  const canonical = path.replace(/\/$/, '')
  if (canonical !== '/discover' && canonical !== '/help') return
  return {
    ...sections[canonical][language],
    path: canonical,
    language,
    links: [{ rel: 'canonical', href: siteOrigin + canonical, hreflang: undefined }],
  }
}

export function landingLinks(language: Locale): { href: string; text: string; lang?: Locale }[] {
  return [
    { href: homeSeo.en.path, text: 'English', lang: 'en' },
    { href: homeSeo['zh-CN'].path, text: '简体中文', lang: 'zh-CN' },
    { href: '/discover', text: sections['/discover'][language].heading },
    { href: '/help', text: sections['/help'][language].heading },
    ...siteGuideLinks(language),
  ]
}

export function socialMetadata(page: NonNullable<ReturnType<typeof sitePage>>) {
  const image = siteOrigin + '/brand/share-default-cover.png'
  const brand = siteName
  const title = page.searchTitle ?? page.title
  return [
    { property: 'og:type', content: 'website' },
    { property: 'og:site_name', content: brand },
    { property: 'og:title', content: title },
    { property: 'og:description', content: page.description },
    { property: 'og:url', content: siteOrigin + page.path },
    { property: 'og:locale', content: page.language === 'en' ? 'en_US' : 'zh_CN' },
    { property: 'og:image', content: image },
    { property: 'og:image:alt', content: brand },
    { name: 'twitter:card', content: 'summary_large_image' },
    { name: 'twitter:title', content: title },
    { name: 'twitter:description', content: page.description },
    { name: 'twitter:image', content: image },
    { name: 'twitter:image:alt', content: brand },
  ]
}

export const websiteSchema = {
  '@context': 'https://schema.org',
  '@type': 'WebSite',
  name: siteName,
  alternateName: ['青风音乐', 'CheerfulMusic'],
  url: siteOrigin + '/',
}
