/** Agent 创作助手的本地意图解析：不依赖后端也能给出可直接使用的创作方案。 */
import { styleSuggestions } from '../../studio/model/suggestions'

export type AgentMode = 'write' | 'rewrite' | 'extend' | 'style' | 'more'

export interface AgentBrief {
  /** 创作描述，直接填入创作页的灵感输入框。 */
  story: string
  style: string
  mood: string
  tags: string[]
}

export interface AgentReply {
  topic: string
  style: string
  mood: string
  brief: AgentBrief
  suggestions: string[]
}

export interface ModeOption { id: AgentMode; label: string; hint: string; placeholder: string; placeholderEn: string }
export const modeOptions: ModeOption[] = [
  { id: 'write', label: 'AI 写歌', hint: '好的，告诉我一个心情、场景或小故事，我来帮你写成歌。', placeholder: '告诉我你的创作需求...', placeholderEn: 'Tell me what you want to create...' },
  { id: 'rewrite', label: '歌词改写', hint: '把想改的歌词或主题发给我，我帮你换一种表达方式。', placeholder: '粘贴想改写的歌词或描述...', placeholderEn: 'Paste the lyrics or idea to rewrite...' },
  { id: 'extend', label: '音乐延长', hint: '想让哪首歌再长一点？告诉我歌曲和想延续的情绪。', placeholder: '想延长哪一段，延续什么情绪...', placeholderEn: 'Which part should continue, and how should it feel...' },
  { id: 'style', label: '风格推荐', hint: '说说你的心情，我从曲风库里挑几种适合的风格。', placeholder: '现在是什么心情？', placeholderEn: 'How are you feeling right now?' },
  { id: 'more', label: '更多玩法', hint: '还可以写生日歌、告白歌、旅行日记，或直接给我一段故事。', placeholder: '随便说点什么，我来找灵感...', placeholderEn: 'Say anything and I will find the inspiration...' },
]

export interface QuickPrompt { icon: string; color: string; text: string; en: string; prompt: string }
export const quickPrompts: QuickPrompt[] = [
  { icon: 'heart', color: '#f36b5c', text: '写一首关于旅行的歌', en: 'Write a song about travelling', prompt: '写一首关于旅行的歌' },
  { icon: 'film', color: '#8a6bd1', text: '给我一首电影风格的歌曲', en: 'A cinematic song for me', prompt: '给我一首电影风格的歌曲' },
  { icon: 'sun', color: '#e8a33d', text: '我想要治愈系的音乐', en: 'Something healing and calm', prompt: '我想要治愈系的音乐' },
  { icon: 'star', color: '#e5a52f', text: '用粤语唱一首情歌', en: 'A Cantonese love song', prompt: '用粤语唱一首情歌' },
  { icon: 'palm', color: '#4fa87a', text: '创作一首夏日海岛风', en: 'A summer island song', prompt: '创作一首夏日海岛风' },
  { icon: 'gift', color: '#e2734f', text: '帮我写一首生日快乐歌', en: 'Write a birthday song', prompt: '帮我写一首生日快乐歌' },
]

export interface InspirationCard { title: string; caption: string; cover: string; prompt: string }
export const inspirationBoards: InspirationCard[][] = [
  [
    { title: '治愈系生活', caption: '温柔慢调，描画日常', cover: '/media/explore-family-v1.webp', prompt: '写一首关于治愈系生活的温柔慢歌' },
    { title: '都市情感', caption: '在城市中寻找共鸣', cover: '/media/explore-disco-v1.jpg', prompt: '写一首关于都市情感的歌' },
    { title: '旅行日记', caption: '用音乐记录沿途', cover: '/media/explore-travel-v1.webp', prompt: '写一首关于旅行的歌' },
  ],
  [
    { title: '国风意境', caption: '东方气韵，留白很美', cover: '/media/explore-guofeng-v1.svg', prompt: '写一首国风古韵的歌' },
    { title: '复古律动', caption: '跟着节拍回到旧时光', cover: '/media/explore-electronic-v1.jpg', prompt: '写一首复古电子风格的歌' },
    { title: '山海之间', caption: '把远方唱成一首歌', cover: '/media/explore-rock-v1.jpg', prompt: '写一首关于山海远方的摇滚' },
  ],
]

export const interestTags = ['时尚', '旅行', '电影', '美食', '音乐', '游戏', '动漫', '生活']

interface Theme { keywords: string[]; topic: string; mood: string; style: string }
const themes: Theme[] = [
  { keywords: ['粤语', '港风', '粤语歌'], topic: '粤语情歌', mood: '复古深情', style: '复古港风' },
  { keywords: ['旅行', '远方', '海风', '公路', '出发', '旅途', '沿途'], topic: '旅行与远方', mood: '自由随性', style: '温暖民谣' },
  { keywords: ['电影', '配乐', '画面', '大片', '叙事'], topic: '电影感叙事', mood: '有画面感', style: '电影配乐' },
  { keywords: ['治愈', '放松', '安静', '疗愈', '慢一点', '平静'], topic: '治愈与放松', mood: '温柔平静', style: '治愈轻音乐' },
  { keywords: ['情歌', '爱情', '恋人', '告白', '喜欢', '暗恋', '相思'], topic: '爱与陪伴', mood: '甜蜜真挚', style: '甜蜜情歌' },
  { keywords: ['生日', '祝福', '庆生', '快乐歌'], topic: '生日祝福', mood: '轻快明亮', style: '轻快流行' },
  { keywords: ['夏日', '海岛', '夏天', '阳光', '海边'], topic: '夏日海岛', mood: '清爽明快', style: '梦幻电子' },
  { keywords: ['摇滚', '热血', '态度', '自由'], topic: '自由态度', mood: '热烈有力', style: '独立摇滚' },
  { keywords: ['国风', '古风', '东方', '古韵', '山水'], topic: '东方意境', mood: '悠远留白', style: '国风古韵' },
  { keywords: ['家人', '妈妈', '爸爸', '陪伴', '回忆'], topic: '家人的陪伴', mood: '温暖怀念', style: '抒情流行' },
]
const fallback: Theme = { keywords: [], topic: '日常里的小情绪', mood: '真诚温暖', style: '温暖民谣' }

/** 关键词命中优先按列表顺序；未命中时把用户原话作为主题。 */
export function matchTheme(input: string): Theme {
  return themes.find(theme => theme.keywords.some(keyword => input.includes(keyword))) || { ...fallback, topic: input.slice(0, 24) || fallback.topic }
}

export function analyzeRequest(input: string, mode: AgentMode = 'write', interests: string[] = []): AgentReply {
  const clean = input.trim().slice(0, 400)
  const theme = matchTheme(clean)
  const suggestions = mode === 'style' ? [theme.style, ...styleSuggestions.filter(style => style !== theme.style).slice(0, 2)] : []
  const prefix = mode === 'rewrite' ? '改写这段歌词，保留原意但换一种表达：' : mode === 'extend' ? '把这首歌延长一段，延续原有情绪：' : ''
  const interestLine = interests.length ? `可以融入我喜欢的${interests.join('、')}元素。` : ''
  const story = `${prefix}以「${clean || theme.topic}」为灵感，写一首${theme.style}风格的歌，突出${theme.mood}的情绪。${interestLine}`
  return {
    topic: theme.topic,
    style: theme.style,
    mood: theme.mood,
    suggestions,
    brief: { story, style: theme.style, mood: theme.mood, tags: [...interests] },
  }
}
