export const siteName = 'Cheerful Music'

export const homeSeo = {
  'zh-CN': {
    path: '/',
    heading: '青风音乐 AI 音乐创作平台',
    description: '青风音乐 AI 音乐创作平台。无需音乐基础，简单几步即可生成专属歌曲，发布作品，分享音乐，让人人都能成为歌手。',
  },
  en: {
    path: '/en/',
    heading: 'Everyone Can Be a Singer',
    title: 'Everyone Can Be a Singer | AI Music Maker – Cheerful Music',
    description: 'Create your own songs with AI in just a few clicks. No music skills needed. Create, share and discover music with Cheerful Music.',
  },
} as const

export type Locale = keyof typeof homeSeo
export const siteOrigin = 'https://cheerfulmusic.com'

export function homeLocale(path: string): Locale | undefined {
  // The root follows the visitor's saved interface language.
  if (path === homeSeo.en.path || path === homeSeo.en.path.slice(0, -1)) return 'en'
}

export function homeSeoLinks(path: string): { rel: string; href: string; hreflang?: string }[] {
  return [
    { rel: 'canonical', href: siteOrigin + path },
    ...Object.entries(homeSeo).map(([language, page]) => ({
      rel: 'alternate', hreflang: language, href: siteOrigin + page.path,
    })),
    { rel: 'alternate', hreflang: 'x-default', href: siteOrigin + '/' },
  ]
}
