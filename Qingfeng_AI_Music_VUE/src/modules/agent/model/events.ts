/** SSE 事件契约（W1 只冻结类型，W3 由 stream.ts 消费）。 */
export type AgentEventType =
  | 'text_delta' | 'tool_start' | 'tool_result' | 'progress' | 'card' | 'error' | 'done'

export interface AgentEvent {
  type: AgentEventType
  conversationId: string
  messageId: string
  /** 会话内单调递增，前端按 (conversationId, seq) 去重。 */
  seq: number
  /** 事件负载：text_delta 用 delta；tool_start 用 tool；progress 用 status/stage；card 用 songCard。 */
  payload?: Record<string, unknown>
}

const EVENT_TYPES: readonly string[] = [
  'text_delta', 'tool_start', 'tool_result', 'progress', 'card', 'error', 'done',
]

export function isAgentEvent(value: unknown): value is AgentEvent {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const event = value as Record<string, unknown>
  return typeof event.type === 'string' && EVENT_TYPES.includes(event.type)
    && typeof event.conversationId === 'string'
    && typeof event.messageId === 'string'
    && typeof event.seq === 'number' && Number.isInteger(event.seq)
    && (event.payload === undefined
      || (!!event.payload && typeof event.payload === 'object' && !Array.isArray(event.payload)))
}

/** 按 (conversationId, seq) 去重，供 W3 断线回放使用。 */
export function dedupeEvents(events: AgentEvent[]): AgentEvent[] {
  const seen = new Set<string>()
  return events.filter(event => {
    const key = `${event.conversationId}:${event.seq}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
