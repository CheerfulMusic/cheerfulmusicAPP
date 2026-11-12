import { computed, onScopeDispose, ref } from 'vue'
import { getConversationState, getConversationHistory, getCurrentConversation, resetConversation, sendMessage } from '../api/chat'
import { observeConversation } from '../api/events'
import { ApiError } from '../../../shared/http/api-client'
import { isAgentContinuation, type AgentContinuation, type ConversationSnapshot, type Message, type SongCard } from '../model/conversation'

interface PendingTurn { text: string; requestKey: string; locale: string; rejected?: boolean; historyEpoch?: number }
const recoveryKey = (id: string) => `qingfeng.agent.pending.${id}`

/** Only unacknowledged requests are kept locally, so retry reuses the original idempotency key. */
export function useAgentConversation(onContinuation?: (snapshot: ConversationSnapshot) => void) {
  const conversationId = ref(''), messages = ref<Message[]>([]), songCard = ref<SongCard | null>(null)
  const taskId = ref<string | null>(null), shareUrl = ref<string | null>(null), liked = ref(false)
  const loading = ref(false), sending = ref(false), error = ref(''), refreshError = ref('')
  const pending = ref<PendingTurn | null>(null)
  const hasOlderMessages = ref(false), loadingOlder = ref(false), historyError = ref('')
  let historyEpoch: number | undefined, latestSeq = 0, historyRequest = 0, hasNewerMessages = false
  const continuation = ref<AgentContinuation | null>(null)
  const autoStarted = new Set<string>()
  const taskRunning = computed(() => !!taskId.value && ['QUEUED', 'SUBMITTING', 'RUNNING', 'SAVING', 'SUBMISSION_UNKNOWN'].includes(songCard.value?.status || ''))
  let disposed = false, timer: ReturnType<typeof setTimeout> | undefined, refreshVersion = 0, pollRequest = 0
  let observation: AbortController | undefined, observationTimer: ReturnType<typeof setTimeout> | undefined
  const progressTransport = ref<'stream' | 'poll' | null>(null)

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
  function merge(rows: Message[]) {
    const byId = new Map(messages.value.map(message => [message.id, message]))
    for (const message of rows) {
      const existing = byId.get(message.id)
      // A delayed observer must not turn an acknowledged message back into RUNNING.
      if (existing && ['DONE', 'FAILED'].includes(existing.state) && ['PENDING', 'RUNNING'].includes(message.state)) continue
      byId.set(message.id, message)
    }
    messages.value = [...byId.values()].sort((a, b) => a.seq - b.seq)
  }
  function apply(snapshot: ConversationSnapshot, observeOnly = false) {
    if (snapshot.historyEpoch !== undefined && historyEpoch !== undefined && snapshot.historyEpoch < historyEpoch) return
    const changed = snapshot.historyEpoch !== undefined && historyEpoch !== undefined && snapshot.historyEpoch !== historyEpoch
    if (changed) { messages.value = []; historyRequest++; loadingOlder.value = false; continuation.value = null; autoStarted.clear() }
    if (snapshot.historyEpoch === undefined || historyEpoch === undefined || changed) messages.value = [...snapshot.messages].sort((a, b) => a.seq - b.seq)
    else merge(snapshot.messages)
    if (snapshot.historyEpoch !== undefined) {
      historyEpoch = snapshot.historyEpoch
      if (pending.value?.historyEpoch !== undefined && pending.value.historyEpoch !== historyEpoch) { pending.value = null; remember() }
    }
    latestSeq = changed ? snapshot.latestSeq ?? 0 : Math.max(latestSeq, snapshot.latestSeq ?? messages.value.at(-1)?.seq ?? 0)
    hasNewerMessages = snapshot.hasNewerMessages === true
    if (snapshot.messagesMode !== 'append' || changed) hasOlderMessages.value = snapshot.hasOlderMessages ?? false
    songCard.value = snapshot.songCard
    taskId.value = snapshot.taskId
    shareUrl.value = snapshot.shareUrl
    liked.value = snapshot.liked
    // Only normal recovery or the POST response may arrange the next authorized turn.
    if (!observeOnly) continuation.value = isAgentContinuation(snapshot.continuation) ? snapshot.continuation : null
  }
  function stopObservation() {
    observation?.abort(); observation = undefined
    clearTimeout(observationTimer); progressTransport.value = null
  }
  function startObservation(version: number) {
    stopObservation()
    const id = conversationId.value, controller = new AbortController()
    observation = controller; progressTransport.value = 'stream'
    const active = () => !disposed && !controller.signal.aborted && version === refreshVersion && id === conversationId.value && sending.value
    const receive = (snapshot: ConversationSnapshot) => {
      if (!active()) return
      const epoch = historyEpoch
      apply(snapshot, true); refreshError.value = ''
      if (epoch !== undefined && historyEpoch !== epoch) {
        // Another tab reset the conversation. Neither this POST nor its observer may revive it.
        refreshVersion++; sending.value = false; stopObservation(); schedulePoll()
      }
    }
    const fallback = async () => {
      if (!active()) return
      progressTransport.value = 'poll'
      let succeeded = false
      try {
        const snapshot = await getConversationState(id, latestSeq, historyEpoch)
        receive(snapshot); succeeded = true
      } catch (cause) {
        if (active()) refreshError.value = cause instanceof Error ? cause.message : '暂时无法刷新进度，正在重试。'
      } finally {
        if (active()) observationTimer = setTimeout(() => { void fallback() }, succeeded && hasNewerMessages ? 0 : 3000)
      }
    }
    // A closed or unsupported stream only changes the read transport. It never resubmits a turn.
    void observeConversation(id, latestSeq, historyEpoch, receive, controller.signal)
      .catch(() => undefined).then(() => { if (active()) void fallback() })
  }
  function schedulePoll(readFailed = false) {
    clearTimeout(timer)
    if (!disposed && !sending.value && !loading.value && !pending.value && hasNewerMessages) {
      timer = setTimeout(() => { void poll() }, readFailed ? 3000 : 0)
      return
    }
    if (!disposed && !sending.value && !loading.value && !pending.value && continuation.value && !autoStarted.has(continuation.value.requestKey)) {
      timer = setTimeout(() => {
        if (disposed || sending.value || loading.value || pending.value || !continuation.value) return
        const next = continuation.value
        if (autoStarted.has(next.requestKey)) return
        autoStarted.add(next.requestKey)
        pending.value = { text: next.text, requestKey: next.requestKey, locale: 'zh-CN', historyEpoch }; remember()
        void deliver().then(result => { if (result) onContinuation?.(result) })
      }, 0)
      return
    }
    if (!disposed && (taskRunning.value || (pending.value && !pending.value.rejected)) && !sending.value) timer = setTimeout(() => { void poll() }, 3000)
  }
  async function poll() {
    const version = refreshVersion, request = ++pollRequest
    let failed = false
    try {
      const snapshot = await getConversationState(conversationId.value, latestSeq, historyEpoch)
      if (disposed || version !== refreshVersion || request !== pollRequest) return
      apply(snapshot, !!pending.value); refreshError.value = ''
    } catch (cause) {
      failed = true
      if (!disposed && version === refreshVersion && request === pollRequest) refreshError.value = cause instanceof Error ? cause.message : '暂时无法刷新进度，正在重试。'
    } finally { if (!disposed && version === refreshVersion && request === pollRequest) schedulePoll(failed) }
  }
  async function loadOlder() {
    if (!conversationId.value || !hasOlderMessages.value || loadingOlder.value || !messages.value.length) return
    const id = conversationId.value, epoch = historyEpoch, request = ++historyRequest
    loadingOlder.value = true; historyError.value = ''
    try {
      const page = await getConversationHistory(id, messages.value[0]!.seq)
      if (disposed || request !== historyRequest || id !== conversationId.value || epoch !== historyEpoch) return
      if (page.historyEpoch !== historyEpoch) { await refresh(); return }
      merge(page.messages); hasOlderMessages.value = page.hasOlderMessages
    } catch (cause) { if (!disposed && request === historyRequest) historyError.value = cause instanceof Error ? cause.message : '历史消息加载失败，请重试。' }
    finally { if (!disposed && request === historyRequest) loadingOlder.value = false }
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
    continuation.value = null
    startObservation(version)
    try {
      const snapshot = await sendMessage(conversationId.value, turn.text, turn.requestKey, turn.locale)
      if (disposed || version !== refreshVersion) return
      stopObservation()
      apply(snapshot); pending.value = null; remember(); refreshError.value = ''
      return snapshot
    } catch (cause) {
      if (!disposed && version === refreshVersion) {
        error.value = cause instanceof Error ? cause.message : '发送未确认，请重试原请求。'
        // A rejected HTTP 400 has not begun a turn; transport errors must keep the original key.
        if (cause instanceof ApiError && cause.status === 400 && pending.value) { pending.value.rejected = true; remember() }
      }
    } finally { if (!disposed && version === refreshVersion) { stopObservation(); sending.value = false; schedulePoll() } }
  }
  function editRejected(): string | undefined {
    if (sending.value || !pending.value?.rejected) return
    const text = pending.value.text
    pending.value = null; remember(); error.value = ''
    return text
  }
  async function reset(): Promise<boolean> {
    if (!conversationId.value || sending.value || loading.value) return false
    loading.value = true; const version = ++refreshVersion; clearTimeout(timer); stopObservation()
    try {
      await resetConversation(conversationId.value)
      if (disposed || version !== refreshVersion) return false
      pending.value = null; remember(); continuation.value = null; autoStarted.clear(); messages.value = []; songCard.value = null
      historyRequest++; historyEpoch = undefined; latestSeq = 0; hasNewerMessages = false; hasOlderMessages.value = false; loadingOlder.value = false; historyError.value = ''
      taskId.value = null; shareUrl.value = null; liked.value = false; error.value = ''; refreshError.value = ''
      return true
    } catch (cause) {
      if (!disposed && version === refreshVersion) error.value = cause instanceof Error ? cause.message : '清空会话失败，请重试。'
      return false
    } finally { if (!disposed && version === refreshVersion) { loading.value = false; schedulePoll() } }
  }
  /** A reply started under a different identity must never repopulate the new account's screen. */
  async function changeIdentity() {
    refreshVersion++; clearTimeout(timer); stopObservation(); continuation.value = null; autoStarted.clear()
    historyRequest++; historyEpoch = undefined; latestSeq = 0; hasNewerMessages = false; hasOlderMessages.value = false; loadingOlder.value = false; historyError.value = ''
    conversationId.value = ''; messages.value = []; songCard.value = null; taskId.value = null
    shareUrl.value = null; liked.value = false; pending.value = null; error.value = ''; refreshError.value = ''
    loading.value = false; sending.value = false
    await refresh()
  }
  async function send(text: string, locale: string): Promise<ConversationSnapshot | undefined> {
    if (!text.trim() || sending.value || loading.value || pending.value) return
    if (!conversationId.value) { await refresh(); if (!conversationId.value || error.value || pending.value) return }
    pending.value = { text: text.trim(), locale, requestKey: crypto.randomUUID(), historyEpoch }
    remember()
    return deliver()
  }
  onScopeDispose(() => { disposed = true; refreshVersion++; clearTimeout(timer); stopObservation() })
  return { conversationId, messages, songCard, taskId, shareUrl, liked, taskRunning, loading, sending, error, refreshError, pending, progressTransport, hasOlderMessages, loadingOlder, historyError, loadOlder, refresh, send, retry: deliver, editRejected, reset, changeIdentity }
}
