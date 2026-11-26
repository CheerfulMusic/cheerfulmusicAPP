export const siteName = 'Cheerful Music'
const creatorHeadline = 'Everyone Can Be a Creator'
const chineseBrand = '青妙Ai'
const englishSearchName = 'cheerfulmusic Ai'
const compactEnglishName = 'cheerfulmusicai'
const englishTabTitle = 'cheerfulmusicAi'

export const siteFavicon = {
  path: '/brand/favicon-symbol-96.png',
  type: 'image/png',
  sizes: '96x96',
} as const

export const homeSeo = {
  'zh-CN': {
    path: '/zh/',
    brand: chineseBrand,
    searchName: chineseBrand,
    title: chineseBrand + '｜人人都能成为创作者',
    tabTitle: chineseBrand,
    searchDescription: '只需几步，就能创作自己的歌曲。无需音乐基础。',
    aliases: ['青风音乐'],
    lastModified: '2026-11-26',
    heading: '做自己的歌',
    tagline: '把你的歌词做成歌',
    invitePrompt: '输入邀请码即可开始创作。',
    description: `青妙Ai 是青风音乐（Cheerful Music）推出的 AI 音乐创作平台，英文名为 ${englishSearchName}（${compactEnglishName}）。写下歌词或故事，选好曲风和人声，就能创作、试听和分享自己的歌。`,
  },
  en: {
    path: '/',
    brand: 'cheerfulmusic',
    searchName: englishSearchName,
    title: englishSearchName + ' | ' + creatorHeadline,
    tabTitle: englishTabTitle,
    searchDescription: 'Create your own song in just a few clicks. No music skills needed.',
    aliases: [
      siteName, 'CheerfulMusic', 'Cheerful Music Ai',
      compactEnglishName, englishTabTitle, 'cheerfulmusicaI', 'cheerfulmusicAI',
    ],
    lastModified: '2026-11-26',
    heading: creatorHeadline,
    tagline: creatorHeadline,
    invitePrompt: 'Enter your invitation code to start creating.',
    description: `Create a song with ${englishSearchName} (${compactEnglishName}), the AI music studio from Cheerful Music (青风音乐), known in Chinese as 青妙Ai. Start with lyrics or a story, choose a style and voice, then listen and share.`,
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

export const homePaths = { 'zh-CN': homeSeo['zh-CN'].path, en: homeSeo.en.path }

export const homeRedirects: Readonly<Record<string, string>> = {
  '/en': homePaths.en,
  '/en/': homePaths.en,
  '/en/index.html': homePaths.en,
  '/zh': homePaths['zh-CN'],
  '/zh/index.html': homePaths['zh-CN'],
  '/zh-CN': homePaths['zh-CN'],
  '/zh-CN/': homePaths['zh-CN'],
  '/zh-CN/index.html': homePaths['zh-CN'],
}

export function homeLocale(path: string): Locale | undefined {
  if (path === homePaths.en) return 'en'
  if (path === homePaths['zh-CN']) return 'zh-CN'
}

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
