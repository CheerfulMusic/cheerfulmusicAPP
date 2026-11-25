export const siteName = 'Cheerful Music'
const creatorHeadline = 'Everyone Can Be a Creator'

export const homeSeo = {
  'zh-CN': {
    path: '/',
    brand: '青妙Ai',
    heading: '青妙Ai',
    tagline: '把你的歌词做成歌',
    invitePrompt: '输入邀请码即可开始创作。',
    description: '青妙Ai 是青风音乐（Cheerful Music）推出的 AI 音乐创作平台，英文名是 cheerfulmusic。写下歌词或故事，选好曲风和人声，就能创作、试听和分享自己的歌。',
  },
  en: {
    path: '/en/',
    brand: 'cheerfulmusic',
    heading: creatorHeadline,
    tagline: creatorHeadline,
    invitePrompt: 'Enter your invitation code to start creating.',
    description: 'Create a song with cheerfulmusic (青妙Ai), the AI music studio from Cheerful Music (青风音乐). Start with lyrics or a story, choose a style and voice, then listen and share.',
  },
} as const

export type Locale = keyof typeof homeSeo
export const siteOrigin = 'https://cheerfulmusic.com'

export const homePublicInfo = {
  'zh-CN': {
    how: '怎样用青妙Ai做一首歌？',
    steps: [
      '写下想唱的故事，或直接填入自己的歌词。',
      '挑选适合这首歌的曲风和人声，确认歌词后开始生成。',
      '到「我的音乐」试听成品，满意后再选择保存或分享。',
    ],
    guide: '查看创作步骤', guidePath: '/guide/create-song',
    faq: '创作常见问题',
    answers: [
      ['没有写好歌词，可以开始吗？', '可以。先写下主题、画面或心情，让平台生成歌词草稿，再把想改的词句换成自己的表达。'],
      ['生成的歌会自动展示在广场吗？', '你可以先在「我的音乐」里自己试听，再决定是否将作品发布到广场。'],
    ],
    examples: '试听公开作品',
  },
  en: {
    how: 'How do I make a song?',
    steps: [
      'Describe a story you want to sing about, or enter lyrics you have already written.',
      'Choose a style and voice, review your lyrics, then generate the song.',
      'Listen in My Music before you decide whether to save or share it.',
    ],
    guide: 'See the song creation guide', guidePath: '/en/guide/create-song',
    faq: 'Songwriting questions',
    answers: [
      ['Can I start without lyrics?', 'Yes. Describe a theme, a scene or a feeling to get a first draft. Edit the lines you want to sing before generating your track.'],
      ['Will my song appear in Discover automatically?', 'You can listen to the song privately in My Music and decide whether to publish it to Discover.'],
    ],
    examples: 'Listen to public songs',
  },
} as const

export function homeLocale(path: string): Locale | undefined {
  // The root defaults to Chinese and allows visitors to switch in place.
  if (path === homeSeo.en.path || path === homeSeo.en.path.slice(0, -1)) return 'en'
}

export const homePaths = { 'zh-CN': homeSeo['zh-CN'].path, en: homeSeo.en.path }

export function homeBrand(path: string) {
  return homeSeo[homeLocale(path) ?? 'zh-CN'].brand
}

export function localizedSeoLinks(path: string, translations: Record<Locale, string>, defaultLanguage: Locale = 'zh-CN') {
  return [
    { rel: 'canonical', href: siteOrigin + path, hreflang: undefined },
    ...Object.entries(translations).map(([language, href]) => ({
      rel: 'alternate', hreflang: language, href: siteOrigin + href,
    })),
    { rel: 'alternate', hreflang: 'x-default', href: siteOrigin + translations[defaultLanguage] },
  ]
}
