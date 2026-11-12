import { computed, onScopeDispose, ref } from 'vue'
import { getConversationState, getCurrentConversation, resetConversation, sendMessage } from '../api/chat'
import { ApiError } from '../../../shared/http/api-client'
import type { ConversationSnapshot, Message, SongCard } from '../model/conversation'

interface PendingTurn { text: string; requestKey: string; locale: string; rejected?: boolean }
const recoveryKey = (id: string) => `qingfeng.agent.pending.${id}`

/** Only unacknowledged requests are kept locally, so retry reuses the original idempotency key. */
export function useAgentConversation() {
  const conversationId = ref(''), messages = ref<Message[]>([]), songCard = ref<SongCard | null>(null)
  const taskId = ref<string | null>(null), shareUrl = ref<string | null>(null), liked = ref(false)
  const loading = ref(false), sending = ref(false), error = ref(''), refreshError = ref('')
  const pending = ref<PendingTurn | null>(null)
  const taskRunning = computed(() => !!taskId.value && ['QUEUED', 'SUBMITTING', 'RUNNING', 'SAVING', 'SUBMISSION_UNKNOWN'].includes(songCard.value?.status || ''))
  let disposed = false, timer: ReturnType<typeof setTimeout> | undefined, refreshVersion = 0

  function remember() {
    try {
      if (pending.value) sessionStorage.setItem(recoveryKey(conversationId.value), JSON.stringify(pending.value))
      else sessionStorage.removeItem(recoveryKey(conversationId.value))
    } catch { /* In-memory retry still uses the original key when storage is unavailable. */ }
  }
  function restore() {
    pending.value = null
    try {
      const value = JSON.parse(sessionStorage.getItem(recoveryKey(conversationId.value)) || 'null') as PendingTurn | null
      if (value && typeof value.text === 'string' && typeof value.requestKey === 'string' && typeof value.locale === 'string') pending.value = value
    } catch { /* Ignore a corrupt local hint; authoritative history still loads. */ }
  }
  function apply(snapshot: ConversationSnapshot) {
    messages.value = [...snapshot.messages].sort((a, b) => a.seq - b.seq)
    songCard.value = snapshot.songCard
    taskId.value = snapshot.taskId
    shareUrl.value = snapshot.shareUrl
    liked.value = snapshot.liked
  }
  function schedulePoll() {
    clearTimeout(timer)
    if (!disposed && taskRunning.value && !sending.value) timer = setTimeout(() => { void poll() }, 3000)
  }
  async function poll() {
    const version = ++refreshVersion
    try {
      const snapshot = await getConversationState(conversationId.value)
      if (disposed || version !== refreshVersion) return
      apply(snapshot); refreshError.value = ''
    } catch (cause) {
      if (!disposed && version === refreshVersion) refreshError.value = cause instanceof Error ? cause.message : '暂时无法刷新进度，正在重试。'
    } finally { if (!disposed && version === refreshVersion) schedulePoll() }
  }
  async function refresh() {
    if (loading.value || sending.value) return
    loading.value = true; error.value = ''
    const version = ++refreshVersion
    try {
      const conversation = await getCurrentConversation()
      if (disposed || version !== refreshVersion) return
      conversationId.value = conversation.id
      restore()
      const snapshot = await getConversationState(conversation.id)
      if (disposed || version !== refreshVersion) return
      apply(snapshot); refreshError.value = ''
    } catch (cause) {
      if (!disposed && version === refreshVersion) error.value = cause instanceof Error ? cause.message : '会话加载失败，请重试。'
    } finally {
      if (!disposed && version === refreshVersion) { loading.value = false; schedulePoll() }
    }
  }
  async function deliver(): Promise<ConversationSnapshot | undefined> {
    if (!pending.value || sending.value || !conversationId.value) return
    sending.value = true; error.value = ''; const version = ++refreshVersion; clearTimeout(timer)
    const turn = pending.value
    try {
      const snapshot = await sendMessage(conversationId.value, turn.text, turn.requestKey, turn.locale)
      if (disposed || version !== refreshVersion) return
      apply(snapshot); pending.value = null; remember(); refreshError.value = ''
      return snapshot
    } catch (cause) {
      if (!disposed && version === refreshVersion) {
        error.value = cause instanceof Error ? cause.message : '发送未确认，请重试原请求。'
        // A rejected HTTP 400 has not begun a turn; transport errors must keep the original key.
        if (cause instanceof ApiError && cause.status === 400 && pending.value) { pending.value.rejected = true; remember() }
      }
    } finally { if (!disposed && version === refreshVersion) { sending.value = false; schedulePoll() } }
  }
  function editRejected(): string | undefined {
    if (sending.value || !pending.value?.rejected) return
    const text = pending.value.text
    pending.value = null; remember(); error.value = ''
    return text
  }
  async function reset(): Promise<boolean> {
    if (!conversationId.value || sending.value || loading.value) return false
    loading.value = true; const version = ++refreshVersion; clearTimeout(timer)
    try {
      await resetConversation(conversationId.value)
      if (disposed || version !== refreshVersion) return false
      pending.value = null; remember(); messages.value = []; songCard.value = null
      taskId.value = null; shareUrl.value = null; liked.value = false; error.value = ''; refreshError.value = ''
      return true
    } catch (cause) {
      if (!disposed && version === refreshVersion) error.value = cause instanceof Error ? cause.message : '清空会话失败，请重试。'
      return false
    } finally { if (!disposed && version === refreshVersion) { loading.value = false; schedulePoll() } }
  }
  /** A reply started under a different identity must never repopulate the new account's screen. */
  async function changeIdentity() {
    refreshVersion++; clearTimeout(timer)
    conversationId.value = ''; messages.value = []; songCard.value = null; taskId.value = null
    shareUrl.value = null; liked.value = false; pending.value = null; error.value = ''; refreshError.value = ''
    loading.value = false; sending.value = false
    await refresh()
  }
  async function send(text: string, locale: string): Promise<ConversationSnapshot | undefined> {
    if (!text.trim() || sending.value || loading.value || pending.value) return
    if (!conversationId.value) { await refresh(); if (!conversationId.value || error.value || pending.value) return }
    pending.value = { text: text.trim(), locale, requestKey: crypto.randomUUID() }
    remember()
    return deliver()
  }
  onScopeDispose(() => { disposed = true; refreshVersion++; clearTimeout(timer) })
  return { conversationId, messages, songCard, taskId, shareUrl, liked, taskRunning, loading, sending, error, refreshError, pending, refresh, send, retry: deliver, editRejected, reset, changeIdentity }
}
