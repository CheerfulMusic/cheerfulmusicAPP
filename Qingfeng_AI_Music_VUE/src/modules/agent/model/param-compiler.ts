/**
 * Agent 结构化参数 → 歌词侧语汇的编译。
 *
 * BPM、乐器、语言、段落里，只有段落能落到现成的歌词结构模板，
 * 语言也影响歌词本身的语言；两者都在这里编译，其余参数由 style-compiler 负责。
 */
import type { AgentParams } from './assistant'
import { templateForSections } from '../../works/model/lyrics-structure'

/**
 * Agent 识别到的演唱语言映射到现有的歌词语言选项。
 * 粤语在歌词侧仍走中文，风格描述里另行说明「Cantonese vocals」。
 */
export function agentLanguage(params?: AgentParams): 'zh-CN' | 'en' | undefined {
  if (!params) return undefined
  if (params.language === 'en') return 'en'
  if (params.language === 'zh' || params.language === 'yue') return 'zh-CN'
  return undefined
}

/** 段落优先编译为现成的歌词结构模板；认不出来就保持默认，绝不替用户编造段落。 */
export function agentStructure(params?: AgentParams): { templateId: 'AUTO' | 'AAB' | 'ABB' | 'ABC' | 'ABCD'; chorusFirst: boolean } | undefined {
  if (!params || !params.structure) return undefined
  const templateId = templateForSections(params.structure.split('-'))
  return templateId === 'AUTO' ? undefined : { templateId, chorusFirst: false }
}
