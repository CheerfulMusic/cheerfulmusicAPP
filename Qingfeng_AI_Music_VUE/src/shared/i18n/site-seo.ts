import { homeLocale, homePaths, homeSeo, localizedSeoLinks, siteOrigin, type Locale } from './home-seo'
import { siteGuide, siteGuideLinks, siteGuidePaths } from './site-guides'

export const discoverPaths = { 'zh-CN': '/discover', en: '/en/discover' } as const

export const discoverSeo = {
  'zh-CN': {
    heading: '青风音乐原创歌曲广场',
    title: '青风音乐｜原创歌曲广场',
    description: '在青风音乐广场试听创作者发布的 AI 原创歌曲。遇到喜欢的声音，点进歌手主页，听听对方的其他作品。',
  },
  en: {
    heading: 'Discover Original AI Songs',
    title: 'Cheerful Music | Discover Original AI Songs',
    description: 'Listen to original AI songs from the Cheerful Music community. Find a track you like, then visit the artist’s page to hear more of their music.',
  },
} as const

export function discoverLocale(path: string): Locale | undefined {
  const canonical = path.replace(/\/$/, '')
  if (canonical === discoverPaths['zh-CN']) return 'zh-CN'
  if (canonical === discoverPaths.en) return 'en'
}

const helpSeo = {
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
} as const

export const sitePagePaths = [homeSeo.en.path, homeSeo['zh-CN'].path, ...Object.values(discoverPaths), '/help', ...siteGuidePaths] as const

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
      links: localizedSeoLinks(guide.path, guide.translations),
    }
  }
  const homeLanguage = homeLocale(path)
  if (homeLanguage) {
    const copy = homeSeo[homeLanguage]
    return {
      path: copy.path,
      language: homeLanguage,
      heading: homeLanguage === 'en' ? `${copy.searchName} — ${copy.heading}` : copy.heading,
      title: copy.title,
      siteName: copy.searchName,
      description: copy.searchDescription,
      intro: copy.description,
      links: localizedSeoLinks(copy.path, homePaths, 'en'),
    }
  }
  const language = preferredLocale ?? 'en'
  const canonical = path.replace(/\/$/, '')
  const discoverLanguage = discoverLocale(path)
  if (discoverLanguage) {
    return {
      ...discoverSeo[discoverLanguage],
      path: discoverPaths[discoverLanguage],
      language: discoverLanguage,
      links: localizedSeoLinks(canonical, discoverPaths),
    }
  }
  if (canonical !== '/help') return
  return {
    ...helpSeo[language],
    path: canonical,
    language,
    links: [{ rel: 'canonical', href: siteOrigin + canonical, hreflang: undefined }],
  }
}

export function landingLinks(language: Locale, path = '/'): { href: string; text: string; lang?: Locale }[] {
  const guide = siteGuide(path)
  const onDiscover = discoverLocale(path) !== undefined
  const translations = guide?.translations ?? (onDiscover ? discoverPaths : homePaths)
  const homeLabels = {
    en: `${homeSeo.en.searchName} Home`,
    'zh-CN': `${homeSeo['zh-CN'].brand} 首页（青风音乐 · Cheerful Music）`,
  }
  const topics: { href: string; text: string }[] = [
    { href: discoverPaths[language], text: discoverSeo[language].heading },
    { href: '/help', text: helpSeo[language].heading },
    ...siteGuideLinks(language),
  ].filter(link => link.href !== path)
  if (guide || onDiscover) topics.unshift({ href: homePaths[language], text: homeLabels[language] })
  return [
    { href: translations.en, text: path === '/zh/' || path === '/help' ? homeLabels.en : 'English', lang: 'en' },
    { href: translations['zh-CN'], text: path === '/' || path === '/help' ? homeLabels['zh-CN'] : '简体中文', lang: 'zh-CN' },
    ...topics,
  ]
}

export function socialMetadata(page: {
  path: string
  language: Locale
  title: string
  description: string
  siteName?: string
}) {
  const image = siteOrigin + '/brand/share-default-cover.png'
  const brand = page.siteName ?? homeSeo['zh-CN'].brand
  const title = page.title
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
  name: homeSeo.en.searchName,
  alternateName: [homeSeo.en.brand, homeSeo['zh-CN'].brand, ...homeSeo['zh-CN'].aliases, ...homeSeo.en.aliases],
  url: siteOrigin + '/',
}
