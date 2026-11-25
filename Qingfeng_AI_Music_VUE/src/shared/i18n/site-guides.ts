import type { Locale } from './home-seo'

const guides = {
  'create-song': {
    appPath: '/create',
    'zh-CN': {
      label: 'AI 创作歌曲',
      heading: '用 AI 做自己的歌',
      title: 'AI 创作歌曲 | Cheerful Music 青风音乐',
      description: '在青风音乐的青妙Ai里，用一段故事或自己的歌词开始创作。选择曲风和人声，修改歌词后生成歌曲。',
      cta: '打开创作工作台',
      sections: [
        ['写下灵感', '用一两句话描述想唱的故事、场景或情绪。已有歌词也可以直接填写。'],
        ['选择风格和人声', '选择适合故事的曲风和演唱声音。检查歌词和页面显示的积分消耗后，再开始生成。'],
        ['试听并保存', '在我的音乐里查看生成进度。生成完成后试听歌曲，再决定是否保存、分享或公开发布。'],
      ],
    },
    en: {
      label: 'Create a Song',
      heading: 'Create Your Own Song with AI',
      title: 'Create an AI Song | Cheerful Music',
      description: 'Turn an idea into a song with Cheerful Music. Choose a style and voice, review the lyrics, and listen to the track you create.',
      cta: 'Open the Song Studio',
      sections: [
        ['Start with an idea', 'Describe a scene, feeling, or story you want to sing about. You can also enter lyrics you have already written.'],
        ['Choose a style and voice', 'Pick a sound that suits your idea. Review the lyrics and check the credit cost shown in the studio before generating.'],
        ['Listen and save', 'Follow the progress in My Music. When the song is ready, listen before deciding whether to save, share, or publish it.'],
      ],
    },
  },
  'edit-lyrics': {
    appPath: '/create',
    'zh-CN': {
      label: '歌词编辑',
      heading: '修改歌词',
      title: '歌词编辑与歌曲生成 | Cheerful Music 青风音乐',
      description: '在生成歌曲前修改歌词。了解怎样从故事起草歌词，调整主歌和副歌，并检查适合演唱的换行。',
      cta: '打开创作工作台',
      sections: [
        ['准备歌词', '在创作页写下主题，或者填写自己的歌词。用能表达故事的细节代替空泛的描述。'],
        ['调整词句', '逐行检查语气和节奏。主歌可以推进故事，副歌可以重复最想唱的一句话。'],
        ['确认后生成', '先核对人名、语言和换行，再选择曲风和人声。提交前查看页面显示的积分消耗。'],
      ],
    },
    en: {
      label: 'Edit Lyrics',
      heading: 'Edit Your Song Lyrics',
      title: 'Edit Lyrics Before Generating a Song | Cheerful Music',
      description: 'Prepare and edit lyrics before generating your song. Review your verses, chorus, and line breaks in the Cheerful Music studio.',
      cta: 'Open the Song Studio',
      sections: [
        ['Prepare your lyrics', 'Start with a topic in the studio or bring lyrics you wrote. Use concrete details to show what happened and how it felt.'],
        ['Refine the lines', 'Read each line aloud. Use the verses to move the story forward and repeat the central idea in the chorus.'],
        ['Review before generating', 'Check names, language, and line breaks. Choose the style and voice, and review the displayed credit cost before submitting.'],
      ],
    },
  },
  'my-music': {
    appPath: '/works',
    'zh-CN': {
      label: '我的音乐',
      heading: '查看和管理我的音乐',
      title: '我的音乐使用指南 | Cheerful Music 青风音乐',
      description: '了解怎样在青风音乐查看自己的草稿、生成进度和完成的歌曲，以及如何试听、管理和分享作品。',
      cta: '打开我的音乐',
      sections: [
        ['找到之前的作品', '用创作时的同一个账号登录，再进入我的音乐。草稿和正在生成的歌曲都可以在这里查看。'],
        ['打开作品详情', '生成完成后打开歌曲详情试听。根据页面提供的操作编辑信息、下载或分享歌曲。'],
        ['选择分享方式', '检查标题和歌词后再分享。选择公开发布时，先确认有权使用作品中的素材。'],
      ],
    },
    en: {
      label: 'My Music',
      heading: 'Find and Manage Your Music',
      title: 'My Music Guide | Cheerful Music',
      description: 'Find your drafts, check a song’s progress, and learn how to listen to, manage, and share your completed songs in Cheerful Music.',
      cta: 'Open My Music',
      sections: [
        ['Find earlier work', 'Sign in with the account you used to create the song. Open My Music to see drafts and songs still being generated.'],
        ['Open a song', 'Once it is ready, open the song details to listen. Use the available controls to edit the details, download, or share.'],
        ['Choose how to share', 'Review the title and lyrics before sharing. If you publish publicly, make sure you have permission to use the material in the song.'],
      ],
    },
  },
  credits: {
    appPath: '/recharge',
    'zh-CN': {
      label: '积分充值',
      heading: '创作积分与充值',
      title: '创作积分与充值指南 | Cheerful Music 青风音乐',
      description: '了解怎样查看创作积分、选择充值套餐、核对付款结果，以及在哪里查找订单或积分到账情况。',
      cta: '查看充值套餐',
      sections: [
        ['查看积分与费用', '登录后查看当前积分余额。使用创作功能前，核对页面显示的积分消耗。'],
        ['选择套餐', '打开充值页，选择可用的支付方式和套餐。在确认订单前核对币种及总额。'],
        ['核对到账', '付款确认后积分会发放。若余额未更新，先在账号页核对订单状态，再通过帮助页联系支持。'],
      ],
    },
    en: {
      label: 'Top Up',
      heading: 'Creation Credits and Top-Ups',
      title: 'Creation Credits and Top-Up Guide | Cheerful Music',
      description: 'Check your creation credit balance, choose a top-up package, and see where to find the order status after paying.',
      cta: 'See Credit Packages',
      sections: [
        ['Check your balance and costs', 'Sign in to see your available credits. Review the cost displayed before using a creation feature.'],
        ['Choose a package', 'Open the recharge page and pick an available payment method and package. Check the currency and total before confirming an order.'],
        ['Check the payment status', 'Credits are added after payment is confirmed. If your balance has not updated, check the order in your account and use the Help page to contact support if needed.'],
      ],
    },
  },
} as const

type Topic = keyof typeof guides
const topics = Object.keys(guides) as Topic[]

function guidePath(topic: Topic, language: Locale) {
  return (language === 'en' ? '/en' : '') + '/guide/' + topic
}

export const siteGuidePaths = topics.flatMap(topic => [guidePath(topic, 'zh-CN'), guidePath(topic, 'en')])

export function siteGuide(path: string) {
  const canonical = path.replace(/\/$/, '')
  const topic = topics.find(key => canonical === guidePath(key, 'zh-CN') || canonical === guidePath(key, 'en'))
  if (!topic) return
  const language = canonical.startsWith('/en/') ? 'en' : 'zh-CN'
  return {
    ...guides[topic][language],
    appPath: guides[topic].appPath,
    path: canonical,
    language,
    translations: { 'zh-CN': guidePath(topic, 'zh-CN'), en: guidePath(topic, 'en') },
  } as const
}

export function siteGuideLinks(language: Locale) {
  return topics.map(topic => ({ href: guidePath(topic, language), text: guides[topic][language].label }))
}
