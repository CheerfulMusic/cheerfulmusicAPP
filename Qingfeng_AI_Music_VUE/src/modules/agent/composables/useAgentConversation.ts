import { ref } from 'vue'
import { clearConversation, getCurrentConversation, listMessages } from '../api/chat'
import { stubUserMessage, type Message } from '../model/conversation'

/**
 * W1：只接 REST 回放；消息发送仍是离线 stub。
 * W3 会把 appendStub 换成 SSE 流，页面组件届时无需改动对外接口。
 */
export function useAgentConversation() {
  const conversationId = ref('')
  const messages = ref<Message[]>([])
  const loading = ref(false)
  const error = ref('')
  let stubCounter = 0

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      const conversation = await getCurrentConversation()
      conversationId.value = conversation.id
      messages.value = await listMessages(conversation.id)
      error.value = ''
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '会话加载失败'
    } finally {
      loading.value = false
    }
  }

  /** W1 stub：等价于发送一条用户消息，但不触发任何后端工具调用。 */
  function appendStub(text: string): Message {
    const message = stubUserMessage(`stub-${++stubCounter}`, conversationId.value,
      text, messages.value.length + 1)
    messages.value = [...messages.value, message]
    return message
  }

  async function reset(): Promise<void> {
    if (!conversationId.value) return
    await clearConversation(conversationId.value)
    messages.value = []
    error.value = ''
  }

  return { conversationId, messages, loading, error, refresh, appendStub, reset }
}
