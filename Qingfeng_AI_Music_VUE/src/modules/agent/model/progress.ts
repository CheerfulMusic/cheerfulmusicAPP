import type { Message } from './conversation'

export interface AgentProgress { text: string; stage: 'planning' | 'tool_started' | 'tool_finished' | 'error'; requestKey: string; step?: number }
export function agentProgress(message: Message): AgentProgress | null {
  if (message.role !== 'assistant') return null
  try {
    const value = JSON.parse(message.contentJson || '{}')
    if (value?.kind !== 'progress' || !['planning', 'tool_started', 'tool_finished', 'error'].includes(value.stage)
      || typeof value.text !== 'string' || typeof value.requestKey !== 'string') return null
    return { text: value.text, stage: value.stage, requestKey: value.requestKey,
      ...(Number.isSafeInteger(value.step) && value.step >= 0 ? { step: value.step } : {}) }
  } catch { return null }
}
