/** Agent 创作助手的本地意图解析：不依赖后端也能给出可直接使用的创作方案。 */
import { styleFromInspiration, styleSuggestions } from '../../studio/model/suggestions'
import { BPM_CONFIDENCE_THRESHOLD, type AudioFeatures } from './audio-features'

export type AgentMode = 'write' | 'rewrite' | 'extend' | 'style' | 'more'

/** AI 语义化结果：风格与情绪属推断，tempo 只在浏览器端 BPM 可信时才有值。 */
export interface ReferenceInsight {
  style: string
  mood: string
  tempo: number
  tempoVerified: boolean
  instrumentation: string[]
  negativeTags: string[]
  summary: string
  source: 'AI' | 'RULE'
}

/** 结构化创作参数：只记录用户明确说过的内容，用于创作页可编辑的音乐设置。 */
export interface AgentParams {
  /** 0 表示用户没提速度，不要猜。 */
  bpm: number
  vocal: 'AUTO' | 'MALE' | 'FEMALE'
  instruments: string[]
  language: '' | 'zh' | 'en' | 'yue'
  structure: string
}

export const ALLOWED_INSTRUMENTS = ['钢琴', '吉他', '弦乐', '小提琴', '大提琴', '贝斯', '鼓', '打击乐', '合成器', '电子音色', '古筝', '二胡', '琵琶', '长笛', '萨克斯', '手风琴', '口琴']
const STRUCTURES = ['前奏', '主歌', '副歌', '桥段', '间奏', '尾奏']

/** 从自然语言里抽取可校验的参数；抽不到就留空，绝不替用户编造。 */
export function extractParams(text: string): AgentParams {
  const source = text.trim()
  const bpmMatch = source.match(/(\d{2,3})\s*(?:bpm|BPM|拍)/) || source.match(/每分钟\s*(\d{2,3})\s*拍/)
  const bpm = bpmMatch ? Number(bpmMatch[1]) : 0
  const vocal: AgentParams['vocal'] = /女声|女歌手|女主唱|女音/.test(source) ? 'FEMALE'
    : /男声|男歌手|男主唱|男音/.test(source) ? 'MALE' : 'AUTO'
  const language: AgentParams['language'] = /粤语|广东话/.test(source) ? 'yue'
    : /英文|英语|english/i.test(source) ? 'en'
    : /中文|普通话|国语/.test(source) ? 'zh' : ''
  return {
    bpm: Number.isFinite(bpm) && bpm >= 40 && bpm <= 220 ? bpm : 0,
    vocal,
    instruments: ALLOWED_INSTRUMENTS.filter(name => source.includes(name)).slice(0, 5),
    language,
    structure: STRUCTURES.filter(name => source.includes(name)).join('-'),
  }
}

/** 参数是否为空；为空时创作页保持原有默认值。 */
export function hasParams(params: AgentParams | undefined): boolean {
  return !!params && (params.bpm > 0 || params.vocal !== 'AUTO' || params.instruments.length > 0 || !!params.language || !!params.structure)
}

/** 本机选择的参考音乐；只把可读的音乐信息写进创作描述，音频文件本身不上传。 */
export interface AgentReference {
  name: string
  duration: number
  size: number
  type: string
  /** 浏览器端实测特征，用于描述节奏、频段与明亮度。 */
  features?: AudioFeatures
  /** DeepSeek 语义化结果；缺失时只写实测特征。 */
  insight?: ReferenceInsight
}

export interface AgentBrief {
  /** 创作描述，直接填入创作页的灵感输入框。 */
  story: string
  style: string
  mood: string
  tags: string[]
  reference?: AgentReference
  /** 结构化参数：用户明确提出的内容原样保留；人声在用户未提出时按方案推荐补齐。 */
  params?: AgentParams
}

export interface AgentReply {
  topic: string
  /** 助手消息里的完整短句，例如「关于离别的温柔抒情歌曲」。 */
  phrase: string
  /** 用户原话，用于推导作品名。 */
  input: string
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

interface Theme {
  /** 命中关键词；支持正则，避免「抒情歌」被「情歌」误命中。 */
  keywords: Array<string | RegExp>
  /** 主题短名，用于内部展示。 */
  topic: string
  /** 助手消息里的完整短句，例如「关于离别的温柔抒情歌曲」。 */
  phrase: string
  mood: string
  style: string
  /** 方案推荐的人声；用户明确提出时以用户为准。 */
  vocal: 'MALE' | 'FEMALE'
}
const themes: Theme[] = [
  { keywords: ['末班车', '离别', '告别', '分别', '离歌', '散伙', '再见', '不舍', '思念', '想念'], topic: '离别', phrase: '关于离别的温柔抒情歌曲', mood: '不舍与怀念', style: '抒情流行', vocal: 'FEMALE' },
  { keywords: ['粤语', '港风', '粤语歌'], topic: '粤语情歌', phrase: '关于一段粤语的深情旋律', mood: '复古深情', style: '复古港风', vocal: 'FEMALE' },
  { keywords: ['旅行', '远方', '海风', '公路', '出发', '旅途', '沿途'], topic: '旅行与远方', phrase: '关于旅行与远方的自由在路上', mood: '自由随性', style: '温暖民谣', vocal: 'MALE' },
  { keywords: ['电影', '配乐', '画面', '大片', '叙事'], topic: '电影感叙事', phrase: '关于一段像电影一样的故事', mood: '有画面感', style: '电影配乐', vocal: 'MALE' },
  { keywords: ['治愈', '放松', '安静', '疗愈', '慢一点', '平静'], topic: '治愈与放松', phrase: '关于一段可以慢慢呼吸的时光', mood: '温柔平静', style: '治愈轻音乐', vocal: 'FEMALE' },
  { keywords: ['抒情', '慢歌', '伤感', '怀旧'], topic: '心里的话', phrase: '关于一段想慢慢说的心里话', mood: '温柔怅然', style: '抒情流行', vocal: 'FEMALE' },
  { keywords: [/(?<!抒)情歌/, '爱情', '恋人', '告白', '暗恋', '相思'], topic: '爱与陪伴', phrase: '关于爱与陪伴的心动', mood: '甜蜜真挚', style: '甜蜜情歌', vocal: 'FEMALE' },
  { keywords: ['生日', '祝福', '庆生', '快乐歌'], topic: '生日祝福', phrase: '关于今天最想祝福的那个人', mood: '轻快明亮', style: '轻快流行', vocal: 'FEMALE' },
  { keywords: ['夏日', '海岛', '夏天', '阳光', '海边'], topic: '夏日海岛', phrase: '关于夏日海边的风', mood: '清爽明快', style: '梦幻电子', vocal: 'FEMALE' },
  { keywords: ['摇滚', '热血', '态度', '自由'], topic: '自由态度', phrase: '关于不肯妥协的那股劲头', mood: '热烈有力', style: '独立摇滚', vocal: 'MALE' },
  { keywords: ['国风', '古风', '东方', '古韵', '山水'], topic: '东方意境', phrase: '关于东方意境里的一片留白', mood: '悠远留白', style: '国风古韵', vocal: 'FEMALE' },
  { keywords: ['家人', '妈妈', '爸爸', '陪伴', '回忆'], topic: '家人的陪伴', phrase: '关于家人始终在身后的陪伴', mood: '温暖怀念', style: '抒情流行', vocal: 'FEMALE' },
]
const fallback: Theme = { keywords: [], topic: '日常里的小情绪', phrase: '关于日常里的小情绪', mood: '真诚温暖', style: '温暖民谣', vocal: 'FEMALE' }

/** 剥掉「我想写一首」「帮我创作」这类说法，只留真正的主题描述。 */
function topicText(input: string): string {
  let text = input.trim()
  text = text.replace(/^(请|麻烦)(你)?(帮我)?/, '')
  text = text.replace(/^我?(想|要|希望|打算)?(再)?(写|创作|做|来)(一)?(首|个)?/, '')
  text = text.replace(/[，,。！!？?、\s]+$/, '').trim()
  return text || input.trim()
}

function keywordHit(keyword: string | RegExp, input: string): boolean {
  return typeof keyword === 'string' ? input.includes(keyword) : keyword.test(input)
}

/** 关键词命中优先按列表顺序；未命中时把用户原话整理成主题。 */
export function matchTheme(input: string): Theme {
  const matched = themes.find(theme => theme.keywords.some(keyword => keywordHit(keyword, input)))
  if (matched) return matched
  const text = topicText(input)
  return { ...fallback, topic: text.slice(0, 24) || fallback.topic, phrase: text.startsWith('关于') ? text : `关于${text}` }
}

/** 作品名候选：从用户原话里取出具体意象，去掉曲风词与并列的主题词。 */
export function extractWorkTitle(input: string, topic: string, fallbackTitle: string): string {
  let body = topicText(input).replace(/^关于/, '').replace(/的$/, '').trim()
  body = body.replace(/((的)?(抒情|流行|摇滚|民谣|电子|古风|国风|轻音乐|情歌|歌曲|音乐|歌))+$/, '').trim()
  const parts = body.split(/和|与|、|跟/).map(part => part.trim()).filter(Boolean)
  const picked = parts.find(part => part !== topic && !topic.includes(part) && !part.includes(topic)) || parts[0] || body
  return (picked || fallbackTitle).slice(0, 12)
}

export function bandWord(bands: AudioFeatures['bands']): string {
  const entries: Array<[string, number]> = [['低频偏重', bands.low], ['中频突出', bands.mid], ['高频偏亮', bands.high]]
  entries.sort((left, right) => right[1] - left[1])
  const [label, top] = entries[0]!
  const second = entries[1]![1]
  if (top >= 0.45 && top - second >= 0.08) return label
  return '三频较均衡'
}

export function toneWord(brightness: number): string {
  if (brightness <= 0.1) return '听感偏暗'
  if (brightness >= 0.3) return '听感偏亮'
  return ''
}

/** 只写有把握的实测值；AI 推断单独标注，避免被当成算法检测结果。 */
function referenceLine(reference: AgentReference): string {
  // 与页面上的时长展示保持一致（秒向下取整），避免描述里出现 02:09 而卡片写 02:08。
  const seconds = Number.isFinite(reference.duration) && reference.duration > 0 ? Math.floor(reference.duration) : 0
  const duration = seconds ? `，时长 ${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}` : ''
  const measured: string[] = []
  const features = reference.features
  if (features) {
    if (features.bpm > 0 && features.bpmConfidence >= BPM_CONFIDENCE_THRESHOLD) measured.push(`约 ${Math.round(features.bpm)} BPM`)
    measured.push(bandWord(features.bands))
    const tone = toneWord(features.brightness)
    if (tone) measured.push(tone)
  }
  const head = `参考音乐《${reference.name.slice(0, 80)}》${duration}${measured.length ? `，${measured.join('、')}` : ''}。`
  const insight = reference.insight
  const inferred = [insight?.style, insight?.mood].filter((value): value is string => !!value && value.trim().length > 0)
  const inference = inferred.length ? `AI 推断：${inferred.join('、')}。` : ''
  const summary = insight?.summary?.trim() ? insight.summary.trim() : ''
  return `${head}${inference}${summary}`
}

/**
 * 把参考音乐写进创作描述；同一份描述只保留一条参考，避免多轮补充时重复。
 * 参考块总是追加在末尾，因此从最后一个「参考音乐《」处截断即可整体替换；
 * 末尾没有「》」时视为用户原话里的引用，不做截断。
 */
export function withReference(brief: AgentBrief, reference?: AgentReference): AgentBrief {
  if (!reference?.name) return brief
  const marker = '参考音乐《'
  const index = brief.story.lastIndexOf(marker)
  const closed = index >= 0 && brief.story.indexOf('》', index) > 0
  const base = closed ? brief.story.slice(0, index).trimEnd() : brief.story
  const story = `${base}${referenceLine(reference)}`
  return { ...brief, story: story.slice(0, 4000), reference }
}

export function analyzeRequest(input: string, mode: AgentMode = 'write', interests: string[] = [], reference?: AgentReference): AgentReply {
  const clean = input.trim().slice(0, 400)
  const theme = matchTheme(clean)
  // 用户没提任何文字要求时，才让 AI 推断的参考风格作为起点；有文字时用户风格优先。
  const referenceStyle = clean ? '' : reference?.insight?.style || ''
  const style = referenceStyle ? styleFromInspiration(referenceStyle) : theme.style
  const suggestions = mode === 'style' ? [style, ...styleSuggestions.filter(item => item !== style).slice(0, 2)] : []
  const prefix = mode === 'rewrite' ? '改写这段歌词，保留原意但换一种表达：' : mode === 'extend' ? '把这首歌延长一段，延续原有情绪：' : ''
  const interestLine = interests.length ? `可以融入我喜欢的${interests.join('、')}元素。` : ''
  const story = `${prefix}以「${clean || theme.topic}」为灵感，写一首${style}风格的歌，突出${theme.mood}的情绪。${interestLine}`
  const params = extractParams(clean)
  // 用户没有指定人声时按主题推荐一个：方案卡与创作页给出同一个答案，不会前后不一致。
  const resolved = params.vocal === 'AUTO' ? { ...params, vocal: theme.vocal } : params
  return {
    topic: theme.topic,
    phrase: theme.phrase,
    input: clean,
    style,
    mood: theme.mood,
    suggestions,
    brief: withReference({ story, style, mood: theme.mood, tags: [...interests], params: resolved }, reference),
  }
}
