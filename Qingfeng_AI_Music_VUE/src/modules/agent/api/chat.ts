/** W1 会话 REST 封装：复用既有 requestJson / ensureCreator，不新增网络层。 */
import { ensureCreator, getJson, requestJson } from '../../../shared/http/api-client'
import { isMessage, type ConversationView, type Message, type ConversationSnapshot } from '../model/conversation'

const base = 'app/agent/conversations'

export async function getConversationState(conversationId: string): Promise<ConversationSnapshot> {
  await ensureCreator()
  return getJson<ConversationSnapshot>(`${base}/${encodeURIComponent(conversationId)}/state`)
}

export async function sendMessage(conversationId: string, text: string, requestKey: string, locale: string): Promise<ConversationSnapshot> {
  await ensureCreator()
  return requestJson<ConversationSnapshot>(`${base}/${encodeURIComponent(conversationId)}/messages`, 'POST', { text, requestKey, locale }, 120000)
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
