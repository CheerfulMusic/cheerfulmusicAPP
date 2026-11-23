export const siteName = 'Cheerful Music'

export const homeSeo = {
  'zh-CN': {
    path: '/',
    heading: '青风音乐 AI 音乐创作平台',
    inviteDescription: '把灵感写成歌词，用 AI 生成专属歌曲。输入邀请码即可体验。',
    searchTitle: 'Cheerful Music 青风音乐｜做自己的歌',
    description: '把一句灵感写成一首歌。在青风音乐选择曲风，用 AI 生成歌曲，编辑歌词，再保存并分享你的原创作品。',
  },
  en: {
    path: '/en/',
    heading: 'Everyone Can Be a Singer',
    inviteDescription: 'Turn your ideas into lyrics and create your own song with AI. Enter your invitation code to get started.',
    title: 'Everyone Can Be a Singer | AI Music Maker – Cheerful Music',
    searchTitle: 'Cheerful Music - Everyone Can Be a Singer',
    description: 'Create your own songs with AI in just a few clicks. No music skills needed. Create, share and discover music with Cheerful Music.',
  },
} as const

export type Locale = keyof typeof homeSeo
export const siteOrigin = 'https://cheerfulmusic.com'

export function homeLocale(path: string): Locale | undefined {
  // The root follows the visitor's saved interface language.
  if (path === homeSeo.en.path || path === homeSeo.en.path.slice(0, -1)) return 'en'
}

export const homePaths = { 'zh-CN': homeSeo['zh-CN'].path, en: homeSeo.en.path }

export function localizedSeoLinks(path: string, translations: Record<Locale, string>) {
  return [
    { rel: 'canonical', href: siteOrigin + path, hreflang: undefined },
    ...Object.entries(translations).map(([language, href]) => ({
      rel: 'alternate', hreflang: language, href: siteOrigin + href,
    })),
    { rel: 'alternate', hreflang: 'x-default', href: siteOrigin + translations['zh-CN'] },
  ]
}
