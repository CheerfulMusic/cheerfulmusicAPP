import { platform } from '../../../platform'
import { ApiError } from '../../../shared/http/api-client'
import { validResponse } from '../../../shared/http/response-contracts'
import type { ConversationSnapshot } from '../model/conversation'
import { readEventStream } from './sse'

/** Read-only observation: never creates a session, submits a turn, or retries a write. */
export async function observeConversation(conversationId: string, afterSeq: number, historyEpoch: number | undefined,
  onState: (snapshot: ConversationSnapshot) => void, signal: AbortSignal): Promise<void> {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  if (platform.kind !== 'web' && !base.startsWith('https://')) throw new Error('App需要配置HTTPS API地址')
  const path = `app/agent/conversations/${encodeURIComponent(conversationId)}`
  const query = historyEpoch === undefined ? '' : `?afterSeq=${afterSeq}&historyEpoch=${historyEpoch}`
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  let watchdog: ReturnType<typeof setTimeout> | undefined
  const touch = () => { clearTimeout(watchdog); watchdog = setTimeout(abort, 20000) }
  try {
    if (signal.aborted) return
    touch()
    const response = await fetch(`${base.replace(/\/$/, '')}/${path}/events${query}`, {
      method: 'GET', headers: { Accept: 'text/event-stream' }, cache: 'no-store',
      credentials: platform.kind === 'web' ? 'same-origin' : 'include', signal: controller.signal,
    })
    if (!response.ok) throw new ApiError('实时进度暂不可用，正在核对已保存的状态。', response.status)
    if (!response.body || !response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) throw new Error('Invalid progress stream response')
    await readEventStream(response.body, controller.signal, event => {
      touch()
      if (event.event === 'ping') return
      if (event.event === 'error') throw new Error('Progress subscription failed')
      if (event.event !== 'state') return
      const snapshot: unknown = JSON.parse(event.data)
      if (!validResponse(`${path}/state`, 'GET', snapshot)) throw new Error('Invalid progress state')
      const state = snapshot as ConversationSnapshot
      if ([state.latestSeq, state.oldestSeq, state.historyEpoch].some(value => value !== undefined && (!Number.isSafeInteger(value) || value < 0))) throw new Error('Invalid progress cursor')
      if (state.messages.some(message => message.conversationId !== conversationId)) throw new Error('Progress belongs to another conversation')
      onState(state)
    })
  } finally {
    clearTimeout(watchdog); signal.removeEventListener('abort', abort); controller.abort()
  }
}
