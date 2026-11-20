import { homeLocale, homeSeo, homeSeoLinks, siteOrigin, type Locale } from './home-seo'

const defaultHome = {
  en: {
    heading: 'Cheerful Music',
    title: 'Cheerful Music | AI Song Maker & Music Community',
    description: 'Make a song from your ideas with Cheerful Music. Explore songs from other creators and choose the English or Simplified Chinese site.',
  },
  'zh-CN': {
    heading: '青风音乐',
    title: '青风音乐 | AI 歌曲创作与音乐社区',
    description: '用青风音乐把灵感变成歌曲，聆听社区创作者的作品。网站提供简体中文和英文版本。',
  },
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

export const sitePagePaths = ['/', homeSeo.en.path, homeSeo['zh-CN'].path, '/help'] as const

export function sitePage(path: string, preferredLocale: Locale = 'en') {
  const localized = homeLocale(path)
  const language = localized ?? preferredLocale
  if (path === '/' || localized) {
    const canonical = localized ? homeSeo[localized].path : '/'
    const copy = localized ? homeSeo[localized] : defaultHome[language]
    return {
      path: canonical,
      language,
      heading: copy.heading,
      title: copy.title,
      description: copy.description,
      links: homeSeoLinks(canonical),
    }
  }
  const canonical = path.replace(/\/$/, '')
  if (canonical !== '/help') return
  return {
    ...sections[canonical][language],
    path: canonical,
    language,
    links: [{ rel: 'canonical', href: siteOrigin + canonical, hreflang: undefined }],
  }
}

export function landingLinks(language: Locale) {
  return [
    { href: homeSeo.en.path, text: 'English', lang: 'en' },
    { href: homeSeo['zh-CN'].path, text: '简体中文', lang: 'zh-CN' },
    { href: '/help', text: sections['/help'][language].heading },
  ]
}

export function socialMetadata(page: NonNullable<ReturnType<typeof sitePage>>) {
  const image = siteOrigin + '/brand/share-default-cover.png'
  const siteName = page.language === 'en' ? 'Cheerful Music' : '青风音乐'
  return [
    { property: 'og:type', content: 'website' },
    { property: 'og:site_name', content: siteName },
    { property: 'og:title', content: page.title },
    { property: 'og:description', content: page.description },
    { property: 'og:url', content: siteOrigin + page.path },
    { property: 'og:locale', content: page.language === 'en' ? 'en_US' : 'zh_CN' },
    { property: 'og:image', content: image },
    { property: 'og:image:alt', content: siteName },
    { name: 'twitter:card', content: 'summary_large_image' },
    { name: 'twitter:title', content: page.title },
    { name: 'twitter:description', content: page.description },
    { name: 'twitter:image', content: image },
    { name: 'twitter:image:alt', content: siteName },
  ]
}

export const websiteSchema = {
  '@context': 'https://schema.org',
  '@type': 'WebSite',
  name: 'Cheerful Music',
  alternateName: ['青风音乐', '人人是歌手'],
  url: siteOrigin + '/',
}
