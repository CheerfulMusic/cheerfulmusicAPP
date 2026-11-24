export const siteName = 'Cheerful Music'
const creatorHeadline = 'Everyone Can Be a Creator'

export const homeSeo = {
  'zh-CN': {
    path: '/',
    heading: '青风音乐 AI 音乐创作平台',
    invitePrompt: '输入邀请码即可开始创作。',
    searchTitle: 'Cheerful Music 青风音乐｜做自己的歌',
    description: '在青风音乐（Cheerful Music），你可以把一段故事或自己写的歌词做成一首歌。选好曲风和人声，用 AI 生成歌曲，试听后保存或分享。',
  },
  en: {
    path: '/en/',
    heading: creatorHeadline,
    invitePrompt: 'Enter your invitation code to start creating.',
    title: `${creatorHeadline} | AI Music Maker – Cheerful Music`,
    searchTitle: `Cheerful Music - ${creatorHeadline}`,
    description: 'Make a song from a story or your own lyrics with Cheerful Music (青风音乐). Choose a style and voice, generate your song with AI, then listen, save or share.',
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
