/** W1 会话 REST 封装：复用既有 requestJson / ensureCreator，不新增网络层。 */
import { ApiError, ensureCreator, getJson, requestJson } from '../../../shared/http/api-client'
import { isMessage, type ConversationView, type Message, type ConversationSnapshot, type ConversationHistory } from '../model/conversation'

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

export async function sendMessage(conversationId: string, text: string, requestKey: string, locale: string): Promise<ConversationSnapshot> {
  await ensureCreator()
  try {
    return await requestJson<ConversationSnapshot>(`${base}/${encodeURIComponent(conversationId)}/messages`, 'POST', { text, requestKey, locale }, 180000)
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== 'AGENT_CHAT_PAYMENT_REQUIRED') throw error
    if (!window.confirm(error.message)) throw new ApiError('已取消积分对话，本次未扣积分。', 400, 'AGENT_CHAT_PAYMENT_CANCELLED')
    return requestJson<ConversationSnapshot>(`${base}/${encodeURIComponent(conversationId)}/messages`, 'POST', { text, requestKey, locale, paidConfirmed: true }, 180000)
  }
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
