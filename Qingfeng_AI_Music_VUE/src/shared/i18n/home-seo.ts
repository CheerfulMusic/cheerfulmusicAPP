export const siteName = 'Cheerful Music'
const creatorHeadline = 'Everyone Can Be a Creator'

export const homeSeo = {
  'zh-CN': {
    path: '/',
    heading: '青风音乐 AI 音乐创作平台',
    inviteDescription: '把灵感写成歌词，用 AI 生成专属歌曲。输入邀请码即可体验。',
    searchTitle: 'Cheerful Music 青风音乐 - 做自己的歌',
    description: 'Cheerful Music 青风音乐是 AI 音乐创作平台。无需音乐基础，写下灵感、选择风格、生成歌曲、修改歌词，再保存或分享。',
  },
  en: {
    path: '/en/',
    heading: creatorHeadline,
    inviteDescription: 'Turn your ideas into lyrics and create your own song with AI. Enter your invitation code to get started.',
    title: `${creatorHeadline} | AI Music Maker – Cheerful Music`,
    searchTitle: `Cheerful Music - ${creatorHeadline}`,
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
