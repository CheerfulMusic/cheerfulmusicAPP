export const homeSeo = {
  'zh-CN': {
    path: '/zh-CN/',
    title: '人人是歌手 | AI音乐创作平台 - 青风音乐',
    description: '青风音乐 AI 音乐创作平台。无需音乐基础，简单几步即可生成专属歌曲，发布作品，分享音乐，让人人都能成为歌手。',
  },
  en: {
    path: '/en/',
    title: 'Everyone Can Be a Singer | AI Music Maker – Cheerful Music',
    description: 'Create your own songs with AI in just a few clicks. No music skills needed. Create, share and discover music with Cheerful Music.',
  },
} as const

export type Locale = keyof typeof homeSeo

export function homeLocale(path: string): Locale | undefined {
  return (Object.keys(homeSeo) as Locale[]).find(locale => {
    const localizedPath = homeSeo[locale].path
    return path === localizedPath || path === localizedPath.slice(0, -1)
  })
}

export function homeSeoLinks(locale: Locale): { rel: string; href: string; hreflang?: string }[] {
  const origin = 'https://cheerfulmusic.com'
  return [
    { rel: 'canonical', href: origin + homeSeo[locale].path },
    ...Object.entries(homeSeo).map(([language, page]) => ({
      rel: 'alternate', hreflang: language, href: origin + page.path,
    })),
    { rel: 'alternate', hreflang: 'x-default', href: origin + homeSeo.en.path },
  ]
}
