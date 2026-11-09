import { styleFromInspiration } from '../../studio/model/suggestions'
import type { AgentBrief, AgentReference } from './assistant'

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

/** 只读不删除；style 走既有白名单，避免会话存储里的任意值进入创作草稿。 */
export function readAgentBrief(): AgentBrief | undefined {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (!raw) return undefined
    const value = JSON.parse(raw) as Partial<AgentBrief>
    if (typeof value?.story !== 'string' || typeof value?.style !== 'string') return undefined
    const reference = validReference(value.reference)
    return {
      story: value.story.slice(0, 4000),
      style: styleFromInspiration(value.style),
      mood: typeof value.mood === 'string' ? value.mood : '',
      tags: Array.isArray(value.tags) ? value.tags.filter((tag): tag is string => typeof tag === 'string').slice(0, 12) : [],
      ...(reference ? { reference } : {}),
    }
  } catch { return undefined }
}

/** 创作页成功接手后再清除；初始化失败时保留，刷新可重试。 */
export function clearAgentBrief(): void {
  try { sessionStorage.removeItem(storageKey) } catch { /* 清除失败不影响已填入的草稿。 */ }
}
