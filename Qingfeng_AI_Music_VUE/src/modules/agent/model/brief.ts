import { styleFromInspiration } from '../../studio/model/suggestions'
import { ALLOWED_INSTRUMENTS, hasParams, type AgentBrief, type AgentParams, type AgentReference } from './assistant'

/** Agent 页 → 创作页的交接数据；只在创作页成功初始化后才清除，失败或刷新时仍可恢复。 */
const storageKey = 'qingfeng.agent.brief'

export function writeAgentBrief(brief: AgentBrief): void {
  try { sessionStorage.setItem(storageKey, JSON.stringify(brief)) } catch { /* 浏览器禁用会话存储时仍可直接进入创作页手动填写。 */ }
}

function validReference(value: unknown): AgentReference | undefined {
  if (!value || typeof value !== 'object') return undefined
  const reference = value as Partial<AgentReference>
  if (typeof reference.name !== 'string' || !reference.name.trim()) return undefined
  return {
    name: reference.name.slice(0, 120),
    duration: Number.isFinite(reference.duration) && Number(reference.duration) > 0 ? Number(reference.duration) : 0,
    size: Number.isFinite(reference.size) && Number(reference.size) > 0 ? Number(reference.size) : 0,
    type: typeof reference.type === 'string' ? reference.type.slice(0, 80) : '',
  }
}

const VOCALS: AgentParams['vocal'][] = ['AUTO', 'MALE', 'FEMALE']
const LANGUAGES: AgentParams['language'][] = ['', 'zh', 'en', 'yue']

/** 结构化参数同样只接受已知取值；非法一律丢弃，让创作页保持默认。 */
function validParams(value: unknown): AgentParams | undefined {
  if (!value || typeof value !== 'object') return undefined
  const raw = value as Partial<AgentParams>
  const bpm = Number.isFinite(raw.bpm) && Number(raw.bpm) >= 40 && Number(raw.bpm) <= 220 ? Number(raw.bpm) : 0
  const params: AgentParams = {
    bpm,
    vocal: VOCALS.includes(raw.vocal as AgentParams['vocal']) ? raw.vocal as AgentParams['vocal'] : 'AUTO',
    instruments: Array.isArray(raw.instruments)
      ? raw.instruments.filter((name): name is string => typeof name === 'string' && ALLOWED_INSTRUMENTS.includes(name)).slice(0, 5)
      : [],
    language: LANGUAGES.includes(raw.language as AgentParams['language']) ? raw.language as AgentParams['language'] : '',
    structure: typeof raw.structure === 'string' ? raw.structure.slice(0, 40) : '',
  }
  return hasParams(params) ? params : undefined
}

/**
 * Agent 的 brief 只预填“新建草稿”。带 work 参数说明是打开已有作品，
 * 此时绝不能用 brief 覆盖用户已经保存的内容。
 */
export function shouldApplyAgentBrief(query: Record<string, unknown>): boolean {
  return !query.work && query.from === 'agent'
}

/** 只读不删除；style 走既有白名单，避免会话存储里的任意值进入创作草稿。 */
export function readAgentBrief(): AgentBrief | undefined {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (!raw) return undefined
    const value = JSON.parse(raw) as Partial<AgentBrief>
    if (typeof value?.story !== 'string' || typeof value?.style !== 'string') return undefined
    const reference = validReference(value.reference)
    const params = validParams(value.params)
    return {
      story: value.story.slice(0, 4000),
      style: styleFromInspiration(value.style),
      mood: typeof value.mood === 'string' ? value.mood : '',
      tags: Array.isArray(value.tags) ? value.tags.filter((tag): tag is string => typeof tag === 'string').slice(0, 12) : [],
      ...(reference ? { reference } : {}),
      ...(params ? { params } : {}),
    }
  } catch { return undefined }
}

/** 创作页成功接手后再清除；初始化失败时保留，刷新可重试。 */
export function clearAgentBrief(): void {
  try { sessionStorage.removeItem(storageKey) } catch { /* 清除失败不影响已填入的草稿。 */ }
}
