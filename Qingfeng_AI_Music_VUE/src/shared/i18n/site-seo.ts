import { homeLocale, homePaths, homeSeo, localizedSeoLinks, siteName, siteOrigin, type Locale } from './home-seo'
import { siteGuide, siteGuideLinks, siteGuidePaths } from './site-guides'

const englishRootSeo = {
  ...homeSeo.en,
  searchTitle: 'Cheerful Music (青风音乐) | Make Your Own Song',
  description: 'Cheerful Music (青风音乐) turns a song idea into original music with AI. Choose a style, edit the lyrics and share your song.',
}

export const discoverPaths = { 'zh-CN': '/discover', en: '/en/discover' } as const

export const discoverSeo = {
  'zh-CN': {
    heading: '青风音乐原创歌曲广场',
    title: '青风音乐原创歌曲广场｜Cheerful Music',
    description: '在青风音乐试听创作者分享的 AI 原创歌曲，发现喜欢的曲风和歌手，走进音乐人的作品主页。',
  },
  en: {
    heading: 'Discover Original AI Songs',
    title: 'Discover Original AI Songs | Cheerful Music',
    description: 'Listen to original AI songs from the Cheerful Music community. Explore featured tracks and meet the artists behind the music.',
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

export const sitePagePaths = [homeSeo['zh-CN'].path, homeSeo.en.path, ...Object.values(discoverPaths), '/help', ...siteGuidePaths] as const

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
  const localized = homeLocale(path)
  const language = localized ?? preferredLocale ?? (path === '/' ? 'zh-CN' : 'en')
  if (path === '/' || localized) {
    const canonical = localized ? homeSeo[localized].path : '/'
    const copy = !localized && language === 'en' ? englishRootSeo : homeSeo[language]
    return {
      path: canonical,
      language,
      heading: copy.heading,
      title: localized ? homeSeo.en.title : siteName,
      searchTitle: copy.searchTitle,
      description: copy.description,
      links: localizedSeoLinks(canonical, homePaths),
    }
  }
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
  const onDiscover = discoverLocale(path) !== undefined
  const translations = onDiscover ? discoverPaths : homePaths
  return [
    { href: translations.en, text: 'English', lang: 'en' },
    { href: translations['zh-CN'], text: '简体中文', lang: 'zh-CN' },
    onDiscover
      ? { href: homeSeo[language].path, text: language === 'en' ? 'Cheerful Music Home' : '青风音乐首页' }
      : { href: discoverPaths[language], text: discoverSeo[language].heading },
    { href: '/help', text: helpSeo[language].heading },
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
