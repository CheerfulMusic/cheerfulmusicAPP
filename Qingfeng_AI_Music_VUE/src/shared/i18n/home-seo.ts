export const siteName = 'Cheerful Music'
export const homeSearchTitle = 'Cheerful Music Ai'
const creatorHeadline = 'Everyone Can Be a Creator'
const chineseHomeHeadline = '青风音乐｜把你的歌词做成歌'
const englishHomeTitle = 'Cheerful Music | Turn Your Lyrics into a Song with AI'

export const homeSeo = {
  'zh-CN': {
    path: '/',
    heading: chineseHomeHeadline,
    invitePrompt: '输入邀请码即可开始创作。',
    searchTitle: homeSearchTitle,
    description: '青风音乐（Cheerful Music）是 AI 音乐创作平台。写下歌词或故事，选好曲风和人声，就能生成、试听和分享自己的歌。',
  },
  en: {
    path: '/en/',
    heading: creatorHeadline,
    invitePrompt: 'Enter your invitation code to start creating.',
    title: englishHomeTitle,
    searchTitle: homeSearchTitle,
    description: 'Turn your lyrics or a story into a song with Cheerful Music. Choose a style and voice, then listen to your AI-generated track and share it with friends.',
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
