/**
 * 结构化参数编译：把 Agent 识别的 BPM / 乐器 / 语言 / 段落编译成追加在风格后面的补充描述。
 *
 * 为什么放进 style：Kie 的生成请求只有 title / lyrics / style / negative_tags / vocal_gender 等字段，
 * 没有 BPM、乐器、语言的独立字段。改协议不在本轮范围，因此只能由 style 承载。
 * 格式约定：`基础风格 | 90 BPM, piano, strings, Cantonese vocals, verse-chorus structure`
 * 分隔符 ` | ` 由前后端共用（后端 StyleCompiler 同款），用于把基础风格与补充描述拆开，
 * 让基础风格仍能命中站内预设词典并被翻译成英文提示词。
 */
import type { AgentParams } from './assistant'

export const STYLE_TAIL_MARK = ' | '
/** 与后端 MusicPromptLimits.suno().style 一致（供应商提交的文本预算）。 */
export const STYLE_LIMIT = 1000
/**
 * 作品表 music_work.style 的列宽是 VARCHAR(80)，写入库前必须按这个预算裁剪。
 * 与 STYLE_LIMIT 是两件事：前者管落库，后者管提交给供应商。
 */
export const WORK_STYLE_LIMIT = 80

const INSTRUMENT_PROMPT: Record<string, string> = {
  钢琴: 'piano',
  吉他: 'guitar',
  弦乐: 'strings',
  小提琴: 'violin',
  大提琴: 'cello',
  贝斯: 'bass',
  鼓: 'drums',
  打击乐: 'percussion',
  合成器: 'synth',
  电子音色: 'electronic textures',
  古筝: 'guzheng',
  二胡: 'erhu',
  琵琶: 'pipa',
  长笛: 'flute',
  萨克斯: 'saxophone',
  手风琴: 'accordion',
  口琴: 'harmonica',
}

const LANGUAGE_PROMPT: Record<string, string> = {
  zh: 'Mandarin vocals',
  en: 'English vocals',
  yue: 'Cantonese vocals',
}

const STRUCTURE_PROMPT: Record<string, string> = {
  前奏: 'intro',
  主歌: 'verse',
  副歌: 'chorus',
  桥段: 'bridge',
  间奏: 'interlude',
  尾奏: 'outro',
}

/** 只编译用户真的提过的参数；没提就不写，避免污染提示词。 */
export function styleTail(params: AgentParams | undefined): string {
  if (!params) return ''
  const parts: string[] = []
  if (params.bpm > 0) parts.push(`${params.bpm} BPM`)
  for (const instrument of params.instruments) {
    const prompt = INSTRUMENT_PROMPT[instrument]
    if (prompt) parts.push(prompt)
  }
  const language = LANGUAGE_PROMPT[params.language]
  if (language) parts.push(language)
  const structure = params.structure
    .split('-')
    .map(name => STRUCTURE_PROMPT[name])
    .filter((name): name is string => !!name)
  if (structure.length) parts.push(`${structure.join('-')} structure`)
  return parts.join(', ')
}

/** 从已保存的风格里拆回基础风格与补充描述；没有分隔符时 tail 为空。 */
export function splitStyle(style: string): { base: string; tail: string } {
  const value = (style || '').trim()
  const index = value.lastIndexOf(STYLE_TAIL_MARK)
  if (index < 0) return { base: value, tail: '' }
  return { base: value.slice(0, index).trim(), tail: value.slice(index + STYLE_TAIL_MARK.length).trim() }
}

/**
 * 落库前的风格合成结果。
 * dropped 列出因为长度预算被丢掉的补充描述，让用户知道哪些参数没能带进去，
 * 避免"静默丢弃"——看起来像是参数生效了，其实没有。
 */
export interface StyleComposition {
  /** 最终落库的风格文本。 */
  style: string
  /** 被丢弃的补充描述项，按丢弃顺序排列。 */
  dropped: string[]
}

/**
 * 落库前的风格合成：基础风格必须完整保留，补充描述按落库预算逐项取舍。
 * 超出 80 列时从补充描述的尾部丢弃，绝不截断用户的基础风格。
 */
export function composeWorkStyle(base: string, tail: string, limit = WORK_STYLE_LIMIT): string {
  return composeStyleDetailed(base, tail, limit).style
}

/** 与 composeWorkStyle 同规则，但同时报告被丢弃的补充描述项。 */
export function composeStyleDetailed(base: string, tail: string, limit = WORK_STYLE_LIMIT): StyleComposition {
  const cleanBase = (base || '').trim()
  const cleanTail = (tail || '').trim()
  if (!cleanTail) return { style: cleanBase, dropped: [] }
  if (!cleanBase) {
    const style = cleanTail.slice(0, limit)
    return style === cleanTail ? { style, dropped: [] } : { style, dropped: [cleanTail.slice(limit).trim()] }
  }
  if (cleanBase.endsWith(`${STYLE_TAIL_MARK}${cleanTail}`)) return { style: cleanBase, dropped: [] }
  const segments = cleanTail.split(', ').filter(Boolean)
  for (let count = segments.length; count > 0; count--) {
    const candidate = `${cleanBase}${STYLE_TAIL_MARK}${segments.slice(0, count).join(', ')}`
    if (candidate.length <= limit) return { style: candidate, dropped: segments.slice(count) }
  }
  return { style: cleanBase, dropped: segments }
}

/**
 * 合并基础风格与补充描述，幂等且不超长：
 * 已在末尾时不再追加；超长时从尾部按需丢弃补充项，绝不截断基础风格。
 */
export function mergeStyle(base: string, tail: string, limit = STYLE_LIMIT): string {
  const cleanBase = (base || '').trim()
  const cleanTail = (tail || '').trim()
  if (!cleanTail) return cleanBase
  if (!cleanBase) return cleanTail.slice(0, limit)
  if (cleanBase.endsWith(`${STYLE_TAIL_MARK}${cleanTail}`)) return cleanBase
  const segments = cleanTail.split(', ').filter(Boolean)
  for (let count = segments.length; count > 0; count--) {
    const candidate = `${cleanBase}${STYLE_TAIL_MARK}${segments.slice(0, count).join(', ')}`
    if (candidate.length <= limit) return candidate
  }
  return cleanBase
}
