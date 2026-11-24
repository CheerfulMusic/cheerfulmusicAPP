/** W1 会话 REST 封装：复用既有 requestJson / ensureCreator，不新增网络层。 */
import { ApiError, currentCreatorId, ensureCreator, getJson, requestJson } from '../../../shared/http/api-client'
import { isMessage, type ConversationView, type Message, type ConversationSnapshot, type ConversationHistory, type DisplayedContext } from '../model/conversation'

const base = 'app/agent/conversations'

export async function getConversationState(conversationId: string, afterSeq?: number, historyEpoch?: number): Promise<ConversationSnapshot> {
  await ensureCreator()
  const query = afterSeq !== undefined && historyEpoch !== undefined ? `?afterSeq=${afterSeq}&historyEpoch=${historyEpoch}` : ''
  return getJson<ConversationSnapshot>(`${base}/${encodeURIComponent(conversationId)}/state${query}`)
}

export async function getConversationHistory(conversationId: string, beforeSeq: number): Promise<ConversationHistory> {
  await ensureCreator()
  return getJson<ConversationHistory>(`${base}/${encodeURIComponent(conversationId)}/history?beforeSeq=${beforeSeq}&limit=40`)
}

export async function sendMessage(conversationId: string, text: string, requestKey: string, locale: string, displayedContext?: DisplayedContext): Promise<ConversationSnapshot> {
  await ensureCreator()
  // Sending accepts the inline price; the server owns allowance and idempotent billing.
  type Receipt = { requestKey: string; status: string }
  const owner = currentCreatorId()
  const endpoint = `${base}/${encodeURIComponent(conversationId)}/messages`
  let result = await requestJson<ConversationSnapshot | Receipt>(endpoint, 'POST', {
    text, requestKey, locale, paidConfirmed: text.trim() !== '继续已安排的操作', displayedContext,
  }, 180000, true)
  const deadline = Date.now() + 210_000
  while ('status' in result) {
    if (result.requestKey !== requestKey || owner !== currentCreatorId() || Date.now() >= deadline)
      throw new ApiError('请查看原消息的处理状态。', 409, 'AGENT_TURN_IN_PROGRESS')
    await new Promise(resolve => setTimeout(resolve, 1800 + Math.random() * 800))
    if (owner !== currentCreatorId()) throw new ApiError('账号已切换。', 409, 'AGENT_TURN_IN_PROGRESS')
    result = await getJson<ConversationSnapshot | Receipt>(`${endpoint}/${encodeURIComponent(requestKey)}/result`)
  }
  return result
}

export async function getCurrentConversation(): Promise<ConversationView> {
  await ensureCreator()
  return getJson<ConversationView>(`${base}/current`)
}

export async function listMessages(conversationId: string): Promise<Message[]> {
  await ensureCreator()
  const rows = await getJson<unknown>(`${base}/${encodeURIComponent(conversationId)}/messages`)
  return Array.isArray(rows) ? rows.filter(isMessage) : []
}

export async function resetConversation(conversationId: string): Promise<void> {
  await ensureCreator()
  await requestJson<unknown>(`${base}/${encodeURIComponent(conversationId)}/reset`, 'POST', {})
}

export async function clearConversation(conversationId: string): Promise<void> {
  await ensureCreator()
  await requestJson<unknown>(`${base}/${encodeURIComponent(conversationId)}`, 'DELETE')
}
