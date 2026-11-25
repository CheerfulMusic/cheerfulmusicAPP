import type { CreationAction } from './conversation'
type BilingualText = readonly [zh: string, en: string]

export interface CreationTool {
  id: 'inspiration' | 'lyrics' | 'music' | 'vocals' | 'refine' | 'mv' | 'cover'
  icon: string
  title: BilingualText
  detail: BilingualText
  question: BilingualText
  prompt: BilingualText
  choices: readonly BilingualText[]
}

export const creationAction: Partial<Record<CreationTool['id'], CreationAction>> = {
  refine: 'POLISH_LYRICS', music: 'GENERATE_SONG', mv: 'PREVIEW_MV',
}

export const creationTools: readonly CreationTool[] = [
  {
    id: 'inspiration', icon: 'create',
    title: ['灵感策划', 'Inspiration'], detail: ['发现创作灵感', 'Find your inspiration'],
    question: ['想从什么故事找灵感？', 'What could inspire your song?'],
    prompt: ['帮我构思一首歌，先聊聊这个灵感。', 'Help me brainstorm song ideas around this.'],
    choices: [['一段旅行回忆', 'A travel memory'], ['平凡的一天', 'An ordinary day'], ['没说出口的话', 'Words left unsaid']],
  },
  {
    id: 'lyrics', icon: 'pen',
    title: ['歌词创作', 'Write lyrics'], detail: ['写出打动人心的歌词', 'Words that move you'],
    question: ['想写什么主题的歌词？', 'What would you like the lyrics to be about?'],
    prompt: ['帮我写一首歌词，主题是', 'Write lyrics about'],
    choices: [['欢快的夏天', 'A carefree summer'], ['写给喜欢的人', 'Someone I love'], ['和朋友去旅行', 'A trip with friends']],
  },
  {
    id: 'music', icon: 'music',
    title: ['音乐制作', 'Make music'], detail: ['定制专属曲风', 'Find your sound'],
    question: ['想试试哪种曲风？', 'What style do you have in mind?'],
    prompt: ['帮我生成音乐，我想要的曲风是', 'Generate music in this style'],
    choices: [['轻快流行', 'Upbeat pop'], ['木吉他民谣', 'Acoustic folk'], ['复古电子', 'Retro electronic']],
  },
  {
    id: 'vocals', icon: 'wave',
    title: ['声音合成', 'Vocals'], detail: ['选择 AI 演唱声音', 'Choose a singing voice'],
    question: ['希望用什么声音演唱？', 'What kind of singing voice would you like?'],
    prompt: ['用当前歌词生成音乐，我希望这样演唱。', 'Generate music from the current lyrics with these vocals.'],
    choices: [['女声', 'Female voice'], ['男声', 'Male voice'], ['男女对唱', 'Male and female duet']],
  },
  {
    id: 'refine', icon: 'sliders',
    title: ['歌曲精修', 'Refine your song'], detail: ['让作品更动人', 'Make every line yours'],
    question: ['想怎么修改这首歌的歌词？', 'How would you like to revise the lyrics?'],
    prompt: ['帮我修改当前作品的歌词。', 'Rewrite the lyrics of my current song.'],
    choices: [['加强副歌', 'Strengthen the chorus'], ['让主歌更有画面感', 'Add imagery to the verses'], ['调整押韵', 'Refine the rhymes']],
  },
  {
    id: 'mv', icon: 'play',
    title: ['MV 制作', 'Create a music video'], detail: ['剧情分镜与动态镜头', 'Bring your song to life'],
    question: ['想拍什么风格的 MV？', 'What style of music video do you want?'],
    prompt: ['帮我设计当前歌曲的 MV，先给我分镜方案和费用，等我确认后再生成。', 'Plan a music video for my current song. Show me the storyboard and cost and wait for my approval before generating the video.'],
    choices: [['剧情短片', 'A short story'], ['舞台现场', 'A live performance'], ['旅行影像', 'A travel film']],
  },
  {
    id: 'cover', icon: 'landscape',
    title: ['封面创作', 'Cover artwork'], detail: ['为音乐描绘画面', 'Give your song a face'],
    question: ['你想要什么样的封面？', 'What do you picture on the cover?'],
    prompt: ['帮我为当前作品设计封面。', 'Design cover art for my current song.'],
    choices: [['暖色插画', 'A warm illustration'], ['黑白人像', 'A black-and-white portrait'], ['霓虹夜景', 'A neon city at night']],
  },
]
