import type { AgentBrief } from './assistant'

/** Agent 页 → 创作页的一次性交接；读取即消费，刷新不会重复覆盖已编辑的灵感。 */
const storageKey = 'qingfeng.agent.brief'

export function writeAgentBrief(brief: AgentBrief): void {
  try { sessionStorage.setItem(storageKey, JSON.stringify(brief)) } catch { /* 浏览器禁用会话存储时仍可直接进入创作页手动填写。 */ }
}

export function readAgentBrief(): AgentBrief | undefined {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (!raw) return undefined
    sessionStorage.removeItem(storageKey)
    const value = JSON.parse(raw) as Partial<AgentBrief>
    if (typeof value?.story !== 'string' || typeof value?.style !== 'string') return undefined
    return {
      story: value.story.slice(0, 4000),
      style: value.style,
      mood: typeof value.mood === 'string' ? value.mood : '',
      tags: Array.isArray(value.tags) ? value.tags.filter((tag): tag is string => typeof tag === 'string') : [],
    }
  } catch { return undefined }
}
